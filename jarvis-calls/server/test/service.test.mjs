import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { Store, quietNow } from '../src/store.mjs';
import { CallService } from '../src/service.mjs';

// These tests prove the real HTTP/WSS handler and call transitions with an explicit
// adapter fixture. The fixture is not evidence of live STT/LLM/TTS or a phone call.
const listen = server => new Promise(r => server.listen(0, '127.0.0.1', r));
const receive = ws => new Promise((resolve, reject) => {
  const timer = setTimeout(() => { ws.off('message', message); reject(new Error('No frame')); }, 1500);
  const message = (data, binary) => { clearTimeout(timer); resolve(binary ? data : JSON.parse(data)); };
  ws.once('message', message);
});
test('pairing is single-use, expires, and does not grant control API access', async () => {
  let now = 1;
  const store = new Store(':memory:', () => now);
  const code = store.pairingCode(); now += 300001;
  assert.throws(() => store.pair(code, 'phone'));
  const fresh = store.pairingCode(), device = store.pair(fresh, 'phone');
  assert.throws(() => store.pair(fresh, 'replay'));
  assert.equal(store.device(device.token).id, device.id);
  assert.equal(store.device('wrong'), undefined);
  store.close();
});
test('quiet hours wrap midnight in configured time zone', () => {
  const policy = { quietHours: { start: 22, end: 8 }, timeZone: 'UTC' };
  assert.equal(quietNow(policy, new Date('2026-01-01T23:00:00Z')), true);
  assert.equal(quietNow(policy, new Date('2026-01-01T12:00:00Z')), false);
  assert.equal(quietNow(policy, new Date('2026-01-01T07:00:00Z')), true);
});
test('real socket: authenticate, outgoing audio gate, interrupt, hangup, incoming answer/decline/expiry', async t => {
  const store = new Store(':memory:');
  const phone = store.pair(store.pairingCode(), 'phone');
  const config = { agentId: 'main', controlToken: 'control-secret', policy: { allowIncoming: true, highPriorityOnly: true, ringSeconds: 30, cooldownSeconds: 0, historyDays: 7 } };
  const service = new CallService({ store, config, adapters: { async turn() { return { audio: Buffer.from('fixture-output'), timings: {} }; } } });
  await Promise.all([listen(service.server), listen(service.control)]);
  t.after(async () => { await service.close(); store.close(); });
  const url = `http://127.0.0.1:${service.server.address().port}`;
  assert.equal((await fetch(url + '/v1/health')).status, 401);
  assert.equal((await fetch(url + '/v1/health', { headers: { authorization: 'Bearer ' + phone.token } })).status, 200);
  assert.equal((await fetch(`http://127.0.0.1:${service.control.address().port}/v1/call`, { method: 'POST', headers: { authorization: 'Bearer ' + phone.token }, body: '{}' })).status, 401);
  const ws = new WebSocket(url.replace('http:', 'ws:') + '/v1/listen', { headers: { authorization: 'Bearer ' + phone.token } });
  let frame = receive(ws); await once(ws, 'open'); assert.equal((await frame).type, 'ready');
  frame = receive(ws); ws.send(Buffer.from('OggS'.padEnd(100))); assert.equal((await frame).type, 'error');
  frame = receive(ws); ws.send(JSON.stringify({ type: 'outgoing', requestId: 'aabbccdd-0000-0000-0000-000000000000' })); const active = await frame; assert.equal(active.type, 'active');
  frame = receive(ws); ws.send(Buffer.from('OggS'.padEnd(100))); assert.equal((await frame).toString(), 'fixture-output');
  // Consume metrics before the next assertion.
  await new Promise(r => setImmediate(r));
  frame = receive(ws); ws.send(JSON.stringify({ type: 'interrupt', callId: active.callId })); assert.equal((await frame).type, 'interrupted');
  frame = receive(ws); ws.send(JSON.stringify({ type: 'hangup', callId: active.callId })); assert.equal((await frame).outcome, 'ended');
  const invite = key => service.invite({ reason: 'test', priority: 'high', sessionKey: 'agent:main:original-task', idempotencyKey: key });
  frame = receive(ws); const call = invite('one'); assert.equal((await frame).type, 'invite');
  assert.throws(() => invite('one'));
  frame = receive(ws); ws.send(Buffer.from('OggS'.padEnd(100))); assert.equal((await frame).type, 'error');
  frame = receive(ws); ws.send(JSON.stringify({ type: 'answer', callId: call.id })); assert.equal((await frame).type, 'active');
  assert.equal((await call.invitationResult).status, 'answered'); assert.equal(call.sessionKey, 'agent:main:original-task');
  frame = receive(ws); service.finish(call, 'ended'); assert.equal((await frame).type, 'ended');
  frame = receive(ws); const declined = invite('two'); await frame;
  frame = receive(ws); ws.send(JSON.stringify({ type: 'decline', callId: declined.id })); assert.equal((await frame).outcome, 'declined');
  frame = receive(ws); const missed = invite('three'); await frame;
  frame = receive(ws); missed.expires = Date.now() - 1; service.tick(); assert.equal((await frame).outcome, 'missed');
  frame = receive(ws); ws.send(JSON.stringify({ type: 'answer', callId: missed.id })); assert.equal((await frame).type, 'error');
  assert.deepEqual(new Set(store.history(phone.id).map(x => x.outcome)), new Set(['ended', 'declined', 'missed']));
  ws.terminate();
});
