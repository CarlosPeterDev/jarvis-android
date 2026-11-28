# Jarvis Calls inspection

Upstream: https://github.com/openclaw/openclaw, inspected commit
28d47b9be7380da46c0f5ca175afb68d740aeaef. MIT license and upstream notices retained.
This fork retains the monorepo because the Android app generates shared Gateway
protocol/localization assets. A shallow/sparse checkout avoids downloading history.
Only Android, the companion directory, and the Jarvis workflow are changed.

The installed tablet runs OpenClaw 2026.11.8, Node 24.22.3, a loopback Gateway on
18789, Telegram, Composio, and tts-local-cli. No main configuration changes are
required to run the call service. Baseline configuration SHA256:
42a9e29d95baa245522fd3b3ecd2789e76b5926be8ca99fcc1f1fa6ff7a575be.

Installed voice adapter: ~/.local/lib/openclaw-voice/local-voice.py.
STT: ffmpeg converts input to mono 16 kHz PCM, whisper.cpp whisper-cli loads
existing ggml-base.en-q5_1.bin with four threads, CPU, English, a per-engine flock,
and a 240 second timeout. Five minute maximum; calls impose a much shorter bound.
TTS: existing Piper en_US-hfc_male-medium.onnx generates WAV, then ffmpeg encodes
Ogg Opus at 32 kbit/s. It accepts up to 12000 text characters and has 90/45 second
subprocess timeouts. Both interfaces process whole files, with model load overhead.
Calls therefore use compressed, bounded utterances and complete synthesized
segments. They do not claim streaming recognition or full duplex.

Android VoiceWakeManager uses on-device SpeechRecognizer and a foreground state
gate. TalkModeManager includes Android recognizer/native and realtime provider
paths; these must not be silently used for the new tablet speech path. Original
Talk and wake screens remain separate. No claim of background/screen-off wake word.

GatewaySession implements challenge-bound device auth and operator/node scopes.
The new phone never receives the Gateway token. The tablet adapter uses the
installed CLI's documented `gateway call` API; an authenticated phone can submit
speech only into the call-owned session, not arbitrary Gateway RPCs.

Transport decision: WSS + Ogg Opus utterances matches the actual engines and uses
Android MediaRecorder's native Opus encoder (minimum SDK 31). WebRTC would add
native dependencies without making the file engines stream. Local TLS with an
explicit certificate fingerprint supports Tailscale IP/MagicDNS and local Wi-Fi
SANs without disabling certificate/hostname verification. Tailscale's own direct
path selection already favors peer-to-peer connections where possible.

Incoming calls use self-managed Telecom, CallStyle notifications, a lock-screen
activity and a foreground signaling listener. This avoids mandatory Firebase
configuration/subscription, at the cost of persistent-listener battery usage and
Android/OEM delivery limits. Force stop, revoked notification/full-screen access,
Doze and an unavailable Tailscale tunnel can prevent ringing. FCM is a future
optional wake adapter, not implemented or advertised as configured.
