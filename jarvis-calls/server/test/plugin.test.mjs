import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createCallTool } from '../../plugin/call-tool.mjs';
import { Store } from '../src/store.mjs';
import { CallService } from '../src/service.mjs';

test('call_user: external input has no calling authority; authorized tool waits for phone decline', async t => {
  const untrusted = createCallTool({ pluginConfig: {} }, { sessionKey: 'agent:main:test', senderIsOwner: false });
  await assert.rejects(untrusted.execute('one', { reason: 'test' }), /requires an owner/);
  const dir = await mkdtemp(join(tmpdir(), 'jarvis-plugin-test-'));
  const original = process.env.JARVIS_CALLS_HOME; process.env.JARVIS_CALLS_HOME = dir;
  const store = new Store(':memory:');
  const phone = store.pair(store.pairingCode(), 'phone');
  const config = { agentId: 'main', controlToken: 'private-test-control-token', policy: { allowIncoming: true, highPriorityOnly: true, ringSeconds: 30, cooldownSeconds: 0, historyDays: 7, telegramFallback: false } };
  const service = new CallService({ store, config, adapters: {} });
  await Promise.all([new Promise(r => service.server.listen(0, '127.0.0.1', r)), new Promise(r => service.control.listen(0, '127.0.0.1', r))]);
  config.controlPort = service.control.address().port;
  await writeFile(join(dir, 'config.json'), JSON.stringify(config), { mode: 0o600 });
  const ws = new WebSocket(`ws://127.0.0.1:${service.server.address().port}/v1/listen`, { headers: { Authorization: 'Bearer ' + phone.token } });
  t.after(async () => { ws.terminate(); await service.close(); store.close(); if (original === undefined) delete process.env.JARVIS_CALLS_HOME; else process.env.JARVIS_CALLS_HOME = original; await rm(dir, { recursive: true, force: true }); });
  const ready = once(ws, 'message'); await once(ws, 'open'); await ready;
  const tool = createCallTool({ pluginConfig: {} }, { sessionKey: 'agent:main:existing-task', senderIsOwner: true });
  const invitation = once(ws, 'message');
  const result = tool.execute('two', { reason: 'Need attention', priority: 'high', requires_response: true });
  const call = JSON.parse((await invitation)[0]);
  assert.equal(call.type, 'invite'); assert.equal(call.reason, undefined);
  assert.equal([...service.calls.values()][0].sessionKey, 'agent:main:existing-task');
  ws.send(JSON.stringify({ type: 'decline', callId: call.callId }));
  assert.equal((await result).details.status, 'declined');
});

test('manifest advertises the runtime tool to the Gateway activation planner', async () => {
  const manifest = JSON.parse(await readFile(new URL('../../plugin/openclaw.plugin.json', import.meta.url), 'utf8'));
  const tool = createCallTool({ pluginConfig: {} }, {});
  assert.ok(manifest.contracts.tools.includes(tool.name));
  assert.equal(manifest.toolMetadata[tool.name].sideEffecting, true);
  assert.equal(manifest.toolMetadata[tool.name].replaySafe, false);
});
