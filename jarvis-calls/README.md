# Jarvis Calls

Jarvis is a derivative of the MIT-licensed [OpenClaw Android app](https://github.com/openclaw/openclaw).
**The tablet remains the AI server. The phone runs only the calling client.**
No second OpenClaw instance, cloud speech provider, calling subscription, or exit node is needed.

This is an integration under verification. Read [the evidence ledger](docs/VERIFICATION.md)
before treating any Android or end-to-end behavior as working.

## Tablet installation

Requires the existing OpenClaw Gateway, Node >=24.22.1, Python, ffmpeg, openssl,
and the existing local voice adapter. Do not run OpenClaw onboarding again.

From this checkout on the tablet:

```sh
bash jarvis-calls/scripts/install-termux.sh
```

The script refuses an existing installation/configuration. It installs a small
companion into `~/.local/lib/jarvis-calls`, creates a separate private
`~/.config/jarvis-calls/config.json`, and does not change `~/.openclaw`.
The manual equivalent is: copy `jarvis-calls/server` and `jarvis-calls/plugin`
(without node_modules) into that directory, run `npm ci --omit=dev --ignore-scripts`
in `server`, then run `node server/src/main.mjs init` from the installation directory.
Keep private configuration/key files mode 600, their directory mode 700.

Find the tablet's actual Tailscale IPv4 address in the Tailscale Android app.
Set `host` in the **new companion configuration** to that address. Keep the
existing OpenClaw Gateway on loopback. Set `agentId` to the existing agent ID
(normally `main`; its display name may be Jarvis). The detected Node/CLI command
uses the existing OpenClaw installation, avoiding a second model or Gateway.
Check `voiceAdapter` points to the existing local adapter.

Generate an endpoint-specific certificate:

```sh
bash jarvis-calls/scripts/create-certificate.sh "$TABLET_TAILSCALE_HOST"
```

`TABLET_TAILSCALE_HOST` is your real IPv4 address or MagicDNS hostname, not a
literal example address. The certificate's SAN must match what you enter in
the phone. The script refuses to overwrite a certificate. Verify the displayed
SHA-256 fingerprint on the tablet and enter it on the phone. The phone checks
that exact certificate, its validity dates, and the hostname; certificate
verification is never disabled. Renew before the one-year expiry and update the
fingerprint deliberately. Alternatively, use a valid trusted certificate;
leave the fingerprint empty only for a system-trusted certificate.

Start the single companion service in a Termux session:

```sh
node ~/.local/lib/jarvis-calls/server/src/main.mjs serve
```

It provides TLS on the configured private address/port (default 19190), and a
separately authenticated control API on **127.0.0.1 only** (default 19191).
Never forward either port through a public router. No Tailscale API key is used.
For unattended use, use an existing Termux service supervisor or a dedicated
Termux:Boot script invoking that same command after its prerequisites are ready.
For an existing Termux:services installation, the optional
`bash jarvis-calls/scripts/install-termux-service.sh` adds only a Jarvis supervisor
and private logs. It refuses an existing service and backs off after failed
startup, such as an absent Tailscale interface. No existing OpenClaw supervisor
is replaced. No Termux:Boot hook is installed automatically. A wake lock and battery
exemption can help, but Android can still stop Termux or Tailscale.

## Phone pairing and use

Install the APK on the Samsung A23. Debug builds use package
`ai.jarvis.calls.debug`; production uses `ai.jarvis.calls`. The application
retains upstream chat, Talk, Gateway pairing, and voice settings via
**Chat & Talk Mode**. Companion pairing is deliberately separate from Gateway
operator pairing. A companion token cannot configure the Gateway.

1. On the tablet run `node ~/.local/lib/jarvis-calls/server/src/main.mjs pair`.
2. In Jarvis → Server & call settings enter `https://YOUR_HOST:YOUR_PORT`,
   the verified certificate fingerprint, and the one-time pairing code.
3. Tap Pair, then Save & test connection. A code expires after five minutes and
   can be consumed once. The resulting token is encrypted with Android Keystore.
4. Enable notifications, microphone, and (if used) Bluetooth access. Review
   **Full-screen incoming call access** on Android 14. Choose the ringtone using
   **Call notifications & ringtone** after starting the listener once.
5. Tap **Receive calls / reconnect**. The persistent connection notification is
   intentional. Tap **Call Jarvis** for an outgoing call.

The app uses Telecom self-managed calls, a CallStyle incoming notification and
full-screen intent. Ringing does not open the microphone. Answering establishes
audio; Decline informs the tool. End call stops microphone/playback and cancels
in-flight processing. Mute stops recording. Audio route lists the device's actual
communication devices. **Interrupt Jarvis** stops playback and requests agent
cancellation. This is explicit interruption, not acoustic full-duplex barge-in.

If the app was force stopped or Android stopped its foreground listener, reopen
it and enable Receive calls. This implementation does not configure FCM and does
not promise WhatsApp-level wake reliability or delivery in every Android state.
Use Samsung's app battery settings carefully; test locked-screen delivery on the
actual phone. Notifications disabled, Doze, other calls, missing full-screen
access, offline Tailscale, and OEM limits may suppress the UI or the invitation.

Paired call credentials expire after 30 days. Review/revoke them locally:

```sh
node ~/.local/lib/jarvis-calls/server/src/main.mjs devices
node ~/.local/lib/jarvis-calls/server/src/main.mjs revoke DEVICE_ID
```

A revoked/expired phone loses the socket and must pair again.

## Agent-initiated calls

The separate `jarvis-calls/plugin` implements the documented OpenClaw plugin API.
Install/link that directory with the existing CLI's plugin installation command
(after reviewing `openclaw plugins install --help` for your installed version).
Use OpenClaw's supported config writer to enable `jarvis-calls` and allow the
optional `call_user` tool in the intended agent's existing tool policy. Do not
replace the policy, config file, Telegram plugin, Composio plugin or skills.
This installation is not performed automatically and the live Gateway is not
restarted automatically.

In the companion configuration, opt in with `policy.allowIncoming: true`.
Defaults: high-priority only, quiet 22:00–08:00 UTC, 30-second ring,
300-second cooldown, 7-day metadata retention, Telegram fallback off.
Change these **service-specific** settings to your preferences before startup.
Quiet-hour bounds are integer hours in `policy.timeZone`; equal bounds mean
quiet all day. Ring duration supports 10–120 seconds. Retention supports 0–365
days; zero retains no completed history. Restart only this companion after edits.

A direct tool request requires an owner-authenticated turn. Scheduled/internal
turns additionally require an explicit agent ID in plugin config
`authorizedInternalAgents`. External sender content cannot set this authority.
The service enforces policy, unique tool-attempt IDs and per-device cooldown.
An offline phone fails promptly. Duplicate attempts do not ring again.

The tool accepts reason, priority (`normal`/`high`), optional context, and
requires_response. It returns answered/declined/missed/failure. It returns on
answer so the original agent turn can release its lane; subsequent call speech
uses that exact session key. The caller should speak first. Answering a call
never approves a deployment, file deletion, or other pending sensitive action.
Use the existing precise approval UI/tools; voice-only approval resolution is
not implemented.

With `policy.telegramFallback: true`, a missed call can send a generic message
through the originating tool's existing authorized Telegram delivery route.
If the turn has no such Telegram route, it reports that limitation and does not
invent a destination or duplicate bot credentials. Declines do not trigger fallback.

## Speech, networking, and data

Microphone Opus → authenticated WSS → existing Whisper CLI → existing Gateway
agent/LLM/tools → existing Piper CLI → Ogg Opus → phone playback. No replacement
speech engine is used. Capture uses a simple amplitude/silence detector with a
20-second utterance cap and about 900 ms silence endpoint. These settings require
hardware/noise testing; they are not neural VAD. Audio is paused during generated
reply playback. Whole-file recognition/synthesis and model load dominate latency.

One isolated adapter check measured TTS 5.16 s and STT 7.07 s for a short generated
test sentence. These are adapter measurements, **not live-call latency results**.
Agent latency was not established because the actual provider request hit DNS
failures/rate-limit entries. UI metrics separate STT, aggregate agent and TTS
work; separate LLM/tool/transport timings are not yet implemented.

Temporary audio is deleted after each turn/playback or normal cancellation.
Call history contains direction/outcome/timestamp, not audio or text. Existing
OpenClaw session history still follows its normal text retention and approval
rules. No additional call recordings or conversation logging are enabled.

Tailscale chooses direct peer-to-peer paths on Wi-Fi where available and falls
back to relays. No exit node, unrelated routing, or PocketServer change is made.
Use the Tailscale app's peer details/connection diagnostics; on a host with its
CLI, `tailscale ping "$TABLET_TAILSCALE_HOST"` and `tailscale status` distinguish
`direct` from DERP/relay. LAN certificate SAN/bind configuration is needed for a
separate direct-LAN URL; automatic LAN/Tailscale URL failover is not implemented.
The native Tailscale path optimization works without adding that second URL.

## Builds, releases, and updates

[Jarvis Calls workflow](../.github/workflows/jarvis-calls.yml) installs the upstream
Java/Android/Node toolchain, runs service/plugin tests and Android lint/unit tests,
and builds a ThirdParty APK and AAB. Debug testing releases are explicitly
prereleases with separate `Jarvis-debug.apk` and `Jarvis-debug.aab` files.
They are not production releases and not marked Latest.

Production requires repository secrets `JARVIS_KEYSTORE_BASE64`,
`JARVIS_STORE_PASSWORD`, `JARVIS_KEY_ALIAS`, and `JARVIS_KEY_PASSWORD`. Keep that
same keystore backed up privately for future updates. Never commit it. A
production workflow invocation/tag fails closed when secrets are missing, and
only verified signed outputs become separate `Jarvis.apk` and `Jarvis.aab`
release assets marked Latest. An AAB is a store bundle, not a directly installable APK.

Retain `upstream=https://github.com/openclaw/openclaw.git`. Future updates:
fetch upstream, create an update branch from your Jarvis development branch,
merge the selected upstream release, resolve only affected Android/companion
contracts, run this workflow plus the physical checklist, review, then publish
with the same signing identity. Do not reset the tablet's installed configuration.
