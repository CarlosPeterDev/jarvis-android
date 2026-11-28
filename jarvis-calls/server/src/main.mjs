#!/usr/bin/env node
import { readFile, mkdir, writeFile, chmod, access } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, dirname } from 'node:path';
import { randomBytes } from 'node:crypto';
import { isIP } from 'node:net';
import { Store } from './store.mjs';
import { LocalAdapters } from './adapters.mjs';
import { CallService } from './service.mjs';

process.umask(0o077);
const directory = process.env.JARVIS_CALLS_HOME || join(homedir(), '.config/jarvis-calls');
const configPath = join(directory, 'config.json');
const mode = process.argv[2] || 'serve';
await mkdir(directory, { recursive: true, mode: 0o700 });
if (mode === 'init') {
  try { await access(configPath); throw new Error('Configuration exists; refusing to overwrite'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const config = {
    host: '127.0.0.1', port: 19190, controlPort: 19191,
    controlToken: randomBytes(32).toString('base64url'),
    certificate: join(directory, 'server.crt'), key: join(directory, 'server.key'),
    voiceAdapter: join(homedir(), '.local/lib/openclaw-voice/local-voice.py'),
    agentId: 'main',
    openclawCommand: [process.execPath, join(dirname(process.execPath), '../lib/node_modules/openclaw/openclaw.mjs')],
    policy: { allowIncoming: false, highPriorityOnly: true, quietHours: { start: 22, end: 8 }, timeZone: 'UTC', ringSeconds: 30, cooldownSeconds: 300, historyDays: 7, telegramFallback: false },
  };
  await writeFile(configPath, JSON.stringify(config, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  console.log('Created private service configuration. Existing OpenClaw configuration was not changed.');
} else {
  const config = JSON.parse(await readFile(configPath, 'utf8'));
  if (!isIP(config.host) || config.host === '0.0.0.0' || config.host === '::') throw new Error('Bind to a specific loopback/Tailscale/LAN IP');
  if (!/^[a-zA-Z0-9_-]+$/.test(config.agentId)) throw new Error('Invalid agent ID');
  if (typeof config.controlToken !== 'string' || config.controlToken.length < 40) throw new Error('Invalid control credential');
  for (const key of ['port', 'controlPort']) if (!Number.isInteger(config[key]) || config[key] < 1024 || config[key] > 65535) throw new Error('Invalid port');
  const p = config.policy;
  if (![p.allowIncoming, p.highPriorityOnly, p.telegramFallback].every(x => typeof x === 'boolean') || !Number.isInteger(p.ringSeconds) || p.ringSeconds < 10 || p.ringSeconds > 120 || !Number.isInteger(p.cooldownSeconds) || p.cooldownSeconds < 30 || !Number.isInteger(p.historyDays) || p.historyDays < 0 || p.historyDays > 365) throw new Error('Invalid call policy');
  if (p.quietHours && ![p.quietHours.start, p.quietHours.end].every(x => Number.isInteger(x) && x >= 0 && x <= 23)) throw new Error('Invalid quiet hours');
  new Intl.DateTimeFormat('en', { timeZone: p.timeZone });
  const store = new Store(join(directory, 'calls.sqlite'));
  if (mode === 'pair') { console.log(store.pairingCode()); store.close(); }
  else if (mode === 'devices') { console.log(JSON.stringify(store.devices(), null, 2)); store.close(); }
  else if (mode === 'revoke') { if (!process.argv[3]) throw new Error('Device ID required'); store.revoke(process.argv[3]); store.close(); console.log('Device revoked'); }
  else if (mode === 'serve') {
    const service = new CallService({ config, store, adapters: new LocalAdapters(config), tls: { cert: await readFile(config.certificate), key: await readFile(config.key) } });
    await Promise.all([
      new Promise((resolve, reject) => service.server.once('error', reject).listen(config.port, config.host, resolve)),
      new Promise((resolve, reject) => service.control.once('error', reject).listen(config.controlPort, '127.0.0.1', resolve)),
    ]);
    console.log('Jarvis call service listening with TLS. Incoming calls: ' + p.allowIncoming);
    let closing = false;
    const stop = async () => { if (closing) return; closing = true; await service.close(); store.close(); };
    process.on('SIGINT', stop); process.on('SIGTERM', stop);
  } else { store.close(); throw new Error('Use init, pair, or serve'); }
}
