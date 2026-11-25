import http from 'node:http';
import https from 'node:https';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { WebSocketServer, WebSocket } from 'ws';
import { quietNow, secretEquals } from './store.mjs';

const TERMINAL = new Set(['declined', 'missed', 'ended', 'failed']);
const json = (res, code, value) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
async function body(req) {
  let size = 0, parts = [];
  for await (const part of req) { size += part.length; if (size > 8192) throw new Error('Request too large'); parts.push(part); }
  return JSON.parse(Buffer.concat(parts).toString());
}
export class CallService {
  constructor({ store, config, adapters, tls }) {
    this.store = store; this.config = config; this.adapters = adapters;
    this.phones = new Map(); this.calls = new Map(); this.closed = false;
    const handler = (req, res) => this.request(req, res, false).catch(() => json(res, 400, { error: 'Invalid request' }));
    this.server = tls ? https.createServer(tls, handler) : http.createServer(handler);
    this.control = http.createServer((req, res) => this.request(req, res, true).catch(() => json(res, 400, { error: 'Invalid request' })));
    this.sockets = new WebSocketServer({ noServer: true, maxPayload: 1_000_000, perMessageDeflate: false });
    this.server.on('upgrade', (req, socket, head) => {
      const device = this.authenticate(req);
      if (req.url !== '/v1/listen' || !device || req.headers.origin) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return; }
      this.sockets.handleUpgrade(req, socket, head, ws => this.connected(device, ws));
    });
    this.timer = setInterval(() => this.tick(), 1000);
    this.timer.unref();
    store.prune(config.policy.historyDays);
  }
  authenticate(req) {
    const header = req.headers.authorization || '';
    return header.startsWith('Bearer ') ? this.store.device(header.slice(7)) : undefined;
  }
  async request(req, res, local) {
    if (local) {
      if (!secretEquals(req.headers.authorization, 'Bearer ' + this.config.controlToken)) return json(res, 401, { error: 'Unauthorized' });
      if (req.method !== 'POST' || req.url !== '/v1/call') return json(res, 404, { error: 'Not found' });
      const data = await body(req);
      const call = this.invite(data);
      // The plugin waits for answer/decline/timeout. It does not submit another
      // agent turn while the originating tool call holds that session's lane.
      const result = await call.invitationResult;
      return json(res, 200, result);
    }
    if (req.method === 'POST' && req.url === '/v1/pair') {
      const address = req.socket.remoteAddress || 'unknown';
      if (!this.store.admit('pair:' + address, 2000)) return json(res, 429, { error: 'Try later' });
      const data = await body(req);
      if (typeof data.code !== 'string' || data.code.length > 128 || typeof data.name !== 'string') return json(res, 400, { error: 'Invalid pairing request' });
      try { return json(res, 200, this.store.pair(data.code, data.name)); }
      catch { return json(res, 401, { error: 'Invalid or expired code' }); }
    }
    const device = this.authenticate(req);
    if (!device) return json(res, 401, { error: 'Unauthorized' });
    if (req.method === 'GET' && req.url === '/v1/health') return json(res, 200, { ok: true, protocol: 1, speech: 'local-utterances', incoming: this.config.policy.allowIncoming });
    if (req.method === 'GET' && req.url === '/v1/history') return json(res, 200, this.store.history(device.id));
    return json(res, 404, { error: 'Not found' });
  }
  emit(device, value) {
    const ws = this.phones.get(device);
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
  }
  create(device, direction, sessionKey) {
    if ([...this.calls.values()].some(c => !TERMINAL.has(c.state))) throw new Error('Another call is in progress');
    const now = Date.now();
    const call = { id: randomUUID(), device, direction, sessionKey, state: direction === 'incoming' ? 'ringing' : 'active', created: now, expires: now + this.config.policy.ringSeconds * 1000, turn: null, turnNumber: 0 };
    call.invitationResult = new Promise(resolve => { call.resolveInvitation = resolve; });
    this.calls.set(call.id, call); return call;
  }
  invite(data) {
    const policy = this.config.policy;
    if (!policy.allowIncoming || quietNow(policy) || (policy.highPriorityOnly && data.priority !== 'high')) throw new Error('Call policy blocks invitation');
    if (typeof data.reason !== 'string' || !data.reason.trim() || data.reason.length > 500 || !['normal', 'high'].includes(data.priority)) throw new Error('Invalid invitation');
    if (typeof data.sessionKey !== 'string' || !/^agent:[a-zA-Z0-9_-]+:.{1,200}$/.test(data.sessionKey)) throw new Error('Missing agent session');
    if (typeof data.idempotencyKey !== 'string' || data.idempotencyKey.length > 200) throw new Error('Missing idempotency key');
    const device = this.store.firstDevice();
    if (!device || !this.phones.has(device.id)) throw new Error('Phone is offline');
    if (!this.store.admit('invite:' + data.idempotencyKey, 86400_000) || !this.store.admit('device:' + device.id, policy.cooldownSeconds * 1000)) throw new Error('Duplicate invitation or cooldown');
    const call = this.create(device.id, 'incoming', data.sessionKey);
    // Sensitive reason/context never appears on a lock screen or wake transport.
    this.emit(device.id, { type: 'invite', callId: call.id, expires: call.expires, name: 'Jarvis' });
    return call;
  }
  connected(device, ws) {
    ws.deviceId = device.id;
    const previous = this.phones.get(device.id);
    this.phones.set(device.id, ws); previous?.close(4001, 'Replaced connection');
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    this.emit(device.id, { type: 'ready', protocol: 1 });
    for (const call of this.calls.values()) {
      if (call.device !== device.id || TERMINAL.has(call.state)) continue;
      call.disconnectedAt = undefined;
      if (call.state === 'ringing') this.emit(device.id, { type: 'invite', callId: call.id, expires: call.expires, name: 'Jarvis' });
      else this.emit(device.id, { type: 'active', callId: call.id });
    }
    ws.on('message', (data, binary) => {
      if (this.phones.get(device.id) !== ws) return;
      Promise.resolve().then(() => this.message(device, ws, data, binary)).catch(() => this.emit(device.id, { type: 'error', message: 'Request rejected' }));
    });
    ws.on('close', () => {
      if (this.phones.get(device.id) !== ws) return;
      this.phones.delete(device.id);
      for (const call of this.calls.values()) if (call.device === device.id && !TERMINAL.has(call.state)) { call.disconnectedAt = Date.now(); call.turn?.abort(); }
    });
    ws.on('error', () => {});
  }
  async message(device, ws, data, binary) {
    if (!this.store.validDevice(device.id)) { ws.close(4003, "Pairing expired or revoked"); return; }
    if (binary) {
      const call = [...this.calls.values()].find(c => c.device === device.id && c.state === 'active');
      if (!call || call.turn || data.length < 64 || data.subarray(0, 4).toString() !== 'OggS') throw new Error('Audio not allowed');
      const number = ++call.turnNumber, controller = new AbortController(); call.turn = controller;
      const started = performance.now();
      try {
        const result = await this.adapters.turn(data, call.sessionKey, controller.signal, stage => this.emit(device.id, { type: 'progress', stage }));
        if (call.state === 'active' && call.turnNumber === number && !controller.signal.aborted && this.phones.get(device.id) === ws) {
          if (ws.bufferedAmount > 4_000_000) throw new Error('Client too slow');
          ws.send(result.audio);
          this.emit(device.id, { type: 'metrics', ...result.timings, responseMs: performance.now() - started });
        }
      } catch {
        if (!controller.signal.aborted) this.emit(device.id, { type: 'error', message: 'Speech or agent processing failed; check tablet diagnostics' });
      } finally { if (call.turn === controller) call.turn = null; }
      return;
    }
    if (data.length > 4096) throw new Error('Message too large');
    const message = JSON.parse(data.toString());
    if (message.type === 'outgoing') {
      if (typeof message.requestId !== 'string' || !/^[a-f0-9-]{36}$/.test(message.requestId)) throw new Error('Missing call request ID');
      const existing = [...this.calls.values()].find(c => c.device === device.id && c.requestId === message.requestId);
      if (existing) { this.emit(device.id, { type: 'active', callId: existing.id }); return; }
      if (!this.store.admit('outgoing:' + device.id, 3000)) throw new Error('Call rate limit');
      const call = this.create(device.id, 'outgoing', `agent:${this.config.agentId}:jarvis-call-${randomUUID()}`);
      call.requestId = message.requestId;
      this.emit(device.id, { type: 'active', callId: call.id }); return;
    }
    const call = this.calls.get(message.callId);
    if (!call || call.device !== device.id || TERMINAL.has(call.state)) throw new Error('Unknown call');
    if (message.type === 'answer' && call.state === 'ringing') {
      if (Date.now() >= call.expires) { this.finish(call, 'missed'); return; }
      call.state = 'active'; call.resolveInvitation({ callId: call.id, status: 'answered' });
      this.emit(device.id, { type: 'active', callId: call.id });
    } else if (message.type === 'decline' && call.state === 'ringing') this.finish(call, 'declined');
    else if (message.type === 'hangup') this.finish(call, 'ended');
    else if (message.type === 'interrupt' && call.state === 'active') {
      ++call.turnNumber; call.turn?.abort(); this.emit(device.id, { type: 'interrupted' });
    } else throw new Error('Invalid call transition');
  }
  finish(call, outcome) {
    if (TERMINAL.has(call.state)) return;
    call.state = outcome; call.turn?.abort(); this.store.record(call, outcome); this.store.prune(this.config.policy.historyDays);
    call.resolveInvitation({ callId: call.id, status: outcome });
    this.emit(call.device, { type: 'ended', callId: call.id, outcome });
    this.calls.delete(call.id);
    // Telegram fallback is deliberately opt-in and delegated to the plugin's
    // existing authorized agent context. No bot token or second channel is used.
  }
  tick() {
    const now = Date.now();
    for (const [device, ws] of this.phones) if (!this.store.validDevice(device)) ws.close(4003, "Pairing expired or revoked");
    for (const call of this.calls.values()) {
      if (call.state === 'ringing' && now >= call.expires) this.finish(call, 'missed');
      else if (call.disconnectedAt && now - call.disconnectedAt > 20_000) this.finish(call, 'failed');
    }
    if (Math.floor(now / 1000) % 20 === 0) for (const ws of this.phones.values()) {
      if (!ws.alive) ws.terminate(); else { ws.alive = false; ws.ping(); }
    }
  }
  async close() {
    this.closed = true; clearInterval(this.timer);
    for (const call of this.calls.values()) this.finish(call, 'failed');
    for (const ws of this.phones.values()) ws.terminate();
    await Promise.all([new Promise(r => this.server.close(r)), new Promise(r => this.control.close(r))]);
    this.sockets.close();
  }
}
