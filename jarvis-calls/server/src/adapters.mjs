import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';

// Arguments are arrays, never shell templates. Output and diagnostics remain private.
export function execute(command, args, { signal, timeout = 300_000, input } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'], signal });
    let output = '', size = 0, settled = false;
    const timer = setTimeout(() => { child.kill('SIGTERM'); }, timeout);
    const killTimer = setTimeout(() => { child.kill('SIGKILL'); }, timeout + 5000);
    const done = (error, value) => { if (settled) return; settled = true; clearTimeout(timer); clearTimeout(killTimer); error ? reject(error) : resolve(value); };
    child.stdout.on('data', chunk => {
      size += chunk.length;
      if (size > 2_000_000) { child.kill(); done(new Error('Adapter output limit exceeded')); }
      else output += chunk.toString();
    });
    child.stderr.resume();
    child.on('error', error => done(error));
    child.on('close', code => done(code === 0 ? null : new Error('Adapter failed: ' + command.split('/').at(-1)), output));
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
      const result = JSON.parse(await execute(config.openclaw || 'openclaw', ['agent', '--session-key', sessionKey, '--message-file', '-', '--json', '--timeout', '240'], { signal, input: text, timeout: 260_000 }));
      timings.agentMs = performance.now() - started;
      if (result.status !== 'ok') throw new Error('Agent did not complete; inspect the session in OpenClaw');
      const reply = (result.result?.payloads || []).map(p => p.text || '').filter(Boolean).join('\n');
      if (!reply.trim()) throw new Error('Agent returned no spoken text');
      progress('synthesizing'); started = performance.now();
      // The existing local adapter handles its own normalization and engine limits.
      await execute(config.python || 'python3', [config.voiceAdapter, 'tts', '--text', reply.slice(0, 12_000), '--output', output], { signal, timeout: 150_000 });
      timings.ttsMs = performance.now() - started;
      const bytes = await readFile(output);
      if (bytes.length > 4_000_000) throw new Error('Synthesized audio exceeds limit');
      return { audio: bytes, timings };
    } finally { await rm(dir, { recursive: true, force: true }); }
  }
}
