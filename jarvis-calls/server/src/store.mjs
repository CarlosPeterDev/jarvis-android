import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { chmodSync } from 'node:fs';

export const digest = value => createHash('sha256').update(value).digest('hex');
export function secretEquals(a, b) {
  const left = Buffer.from(String(a)), right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}
export class Store {
  constructor(path, now = Date.now) {
    this.now = now;
    this.db = new DatabaseSync(path);
    if (path !== ':memory:') chmodSync(path, 0o600);
    this.db.exec(`PRAGMA journal_mode=WAL;
      CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL);
      CREATE TABLE IF NOT EXISTS pairing (hash TEXT PRIMARY KEY, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS history (id TEXT PRIMARY KEY, device TEXT NOT NULL, direction TEXT NOT NULL, outcome TEXT NOT NULL, created INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS attempts (key TEXT PRIMARY KEY, created INTEGER NOT NULL);`);
  }
  pairingCode() {
    const code = randomBytes(24).toString('base64url');
    this.db.prepare('DELETE FROM pairing').run();
    this.db.prepare('INSERT INTO pairing VALUES (?,?)').run(digest(code), this.now() + 300_000);
    return code;
  }
  pair(code, name) {
    const consumed = this.db.prepare('DELETE FROM pairing WHERE hash=? AND expires>? RETURNING hash').get(digest(code), this.now());
    if (!consumed) throw new Error('Invalid or expired pairing code');
    const token = randomBytes(32).toString('base64url'), id = randomUUID();
    this.db.prepare('INSERT INTO devices VALUES (?,?,?)').run(id, name.slice(0, 80), digest(token));
    return { id, token };
  }
  device(token) { return this.db.prepare('SELECT id,name FROM devices WHERE token_hash=?').get(digest(token)); }
  firstDevice() { return this.db.prepare('SELECT id,name FROM devices ORDER BY rowid LIMIT 1').get(); }
  record(call, outcome) {
    this.db.prepare('INSERT OR REPLACE INTO history VALUES (?,?,?,?,?)').run(call.id, call.device, call.direction, outcome, call.created);
  }
  history(device) { return this.db.prepare('SELECT id,direction,outcome,created FROM history WHERE device=? ORDER BY created DESC LIMIT 50').all(device); }
  prune(days) {
    this.db.prepare('DELETE FROM history WHERE created<?').run(this.now() - days * 86400_000);
    this.db.prepare('DELETE FROM attempts WHERE created<?').run(this.now() - 86400_000);
  }
  admit(key, cooldownMs) {
    const existing = this.db.prepare('SELECT created FROM attempts WHERE key=?').get(key);
    if (existing && this.now() - existing.created < cooldownMs) return false;
    this.db.prepare('INSERT OR REPLACE INTO attempts VALUES (?,?)').run(key, this.now());
    return true;
  }
  close() { this.db.close(); }
}
export function quietNow(policy, now = new Date()) {
  if (!policy.quietHours) return false;
  const hour = Number(new Intl.DateTimeFormat('en', { timeZone: policy.timeZone || 'UTC', hour: 'numeric', hourCycle: 'h23' }).format(now));
  const { start, end } = policy.quietHours;
  return start === end || (start < end ? hour >= start && hour < end : hour >= start || hour < end);
}
