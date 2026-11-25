# Security review and limits

This is the author's source review, not an independent audit or a claim of
production readiness. Physical call behavior, packaged Android behavior and
live model/tool integration need the evidence listed in VERIFICATION.md.

## Boundaries reviewed

- The existing Gateway stays on loopback. The phone receives only a companion
  token, not Gateway/admin, Telegram, Composio or model credentials.
- Production service startup requires TLS material and a specific bind IP.
  App URLs require HTTPS and reject embedded credentials, paths, fragments and
  query strings. Normal system trust or an explicit certificate trust anchor
  with validity/hostname checks is used. No permissive TLS/hostname verifier.
- Pairing codes have 192 bits of entropy, expire in five minutes and are consumed
  once. Per-device 256-bit bearer tokens are hashed in SQLite, expire in 30 days,
  can be revoked and are Keystore-encrypted on Android. Pair attempts are limited.
- The agent control listener binds loopback and uses a separate random credential.
  The paired socket exposes call operations only. No generic command/RPC proxy.
- The plugin derives sender/session authority from OpenClaw runtime context.
  Internal agents need explicit local authorization. Call policies, quiet hours,
  expiration, cooldown, idempotency and one active call limit repeated rings.
- Phone audio is rejected before acceptance and cannot select another device's
  session. Server-selected keys isolate outgoing calls; incoming calls retain
  the tool's original session. Reconnects never blindly replay a speech turn.
- The answer Activity is private; an exported launcher alias opens the dashboard.
  Only an intent targeting the private Activity can dispatch notification Answer.
  PendingIntents are explicit and immutable; call IDs must match current state.
- Commands use argv arrays/stdin, not shell substitution. Cancellation kills
  owned process groups and awaits exit before removing temporary audio. Audio,
  request and subprocess outputs are bounded. Errors omit engine output/secrets.
- The service stores metadata only. The default logs omit credentials, raw
  transcripts and audio. Existing OpenClaw text history is explicitly disclosed.

## Material unresolved risks

- No physical Android test or independent audit yet. Telecom/audio focus,
  full-screen permissions, Samsung battery rules and competing wake listeners
  must be verified before relying on attention-critical calls.
- A foreground listener is less reliable than a configured push wake path. FCM
  is not implemented. Persistent TLS has an unmeasured idle battery cost.
- Existing agent/tool approval boundaries remain authoritative. Voice does not
  automatically resolve pending approvals. An authorized agent can still be
  influenced by untrusted material; the owner/internal admission plus cooldown
  bounds calling but does not solve prompt injection for every existing tool.
- STT/TTS process sandboxes are the existing Termux UID. The configured local
  adapters are trusted executables. Keep OpenClaw, ffmpeg, Whisper/Piper and the
  Android OS patched. Temporary files may survive sudden process death until
  cleanup; the phone removes abandoned private audio on its next cold start.
- Self-signed certificate renewal requires a deliberate fingerprint update.
  Compromised tablet/phone or the same Termux UID is outside this boundary.
- No automatic certificate enrollment, recovery after force stop, remote audio
  recording, arbitrary spoken approvals, or public internet exposure.
