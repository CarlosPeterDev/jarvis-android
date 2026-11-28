import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';

// Arguments are arrays, never shell templates. Output and diagnostics remain private.
export function execute(command, args, { signal, timeout = 300_000, input } = {}) {
  return new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'], detached: true });
    let output = '', size = 0, failure, killTimer;
    const kill = sig => { try { process.kill(-child.pid, sig); } catch (error) { if (error.code !== 'ESRCH') throw error; } };
    const stop = reason => {
      if (failure) return;
      failure = reason; kill('SIGTERM');
      killTimer = setTimeout(() => kill('SIGKILL'), 5000);
    };
    const timer = setTimeout(() => stop(new Error('Adapter deadline exceeded')), timeout);
    const aborted = () => stop(new Error('Adapter cancelled'));
    signal?.addEventListener('abort', aborted, { once: true });
    child.stdout.on('data', chunk => {
      size += chunk.length;
      if (size > 2_000_000) stop(new Error('Adapter output limit exceeded'));
      else output += chunk.toString();
    });
    child.stderr.resume();
    child.on('error', error => { failure = error; });
    child.on('close', code => {
      clearTimeout(timer); clearTimeout(killTimer); signal?.removeEventListener('abort', aborted);
      if (failure || code !== 0) reject(failure || new Error('Adapter failed: ' + command.split('/').at(-1)));
      else resolve(output);
    });
    child.stdin.on('error', () => {});
    child.stdin.end(input);
  });
}
export class LocalAdapters {
  constructor(config) { this.config = config; }
  async turn(audio, sessionKey, signal, progress) {
    const config = this.config, dir = await mkdtemp(join(tmpdir(), 'jarvis-call-'));
    const timings = {};
    let started = performance.now();
    try {
      const input = join(dir, 'input.ogg'), output = join(dir, 'output.ogg');
      await writeFile(input, audio, { mode: 0o600 });
      progress('transcribing');
      const text = (await execute(config.python || 'python3', [config.voiceAdapter, 'stt', input], { signal })).trim();
      timings.sttMs = performance.now() - started;
      if (!text || text.length > 12_000) throw new Error('Invalid STT output');
      progress('thinking'); started = performance.now();
      const [cli, ...prefix] = config.openclawCommand || ['openclaw'];
      const result = JSON.parse(await execute(cli, [...prefix, 'agent', '--session-key', sessionKey, '--message-file', '-', '--json', '--timeout', '240'], { signal, input: text, timeout: 260_000 }));
      timings.agentMs = performance.now() - started;
      if (result.status !== 'ok') throw new Error('Agent did not complete; inspect the session in OpenClaw');
      const reply = (result.result?.payloads || []).map(p => p.text || '').filter(Boolean).join('\n');
      if (!reply.trim()) throw new Error('Agent returned no spoken text');
      progress('synthesizing'); started = performance.now();
      // The existing local adapter handles its own normalization and engine limits.
      await execute(config.python || 'python3', [config.voiceAdapter, 'tts', '--text=' + reply.slice(0, 12_000), '--output', output], { signal, timeout: 150_000 });
      timings.ttsMs = performance.now() - started;
      const bytes = await readFile(output);
      if (bytes.length > 4_000_000) throw new Error('Synthesized audio exceeds limit');
      return { audio: bytes, timings };
    } finally { await rm(dir, { recursive: true, force: true }); }
  }
}
