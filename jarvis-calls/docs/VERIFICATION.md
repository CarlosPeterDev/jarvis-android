# Verification ledger

The integration is not yet accepted as a working end-to-end calling system.
Source code, adapter tests, signaling tests and physical-device results are
reported separately. Tests below never use synthetic audio as proof of an AI call.

## Automated evidence

- `node --test jarvis-calls/server/test/*.test.mjs`: four passing tests, roughly
  two seconds on the tablet. Real HTTP/WebSocket boundaries cover authentication,
  one-time/expired pairing, credential expiration, quiet hours, duplicate outgoing
  requests/invitations, rejection of pre-answer audio, answer/decline/missed,
  interruption/hang-up, history, tool admission and retained originating session.
  The audio response in this suite is an explicitly labeled adapter fixture.
- Installed local voice engines: Piper generated a short test utterance in
  5.162 s, Whisper recognized it in 7.073 s. The Ogg Opus output was 15,216 bytes.
  This verifies adapter executables/formats only, not microphone or call audio.
- The deployed tablet companion passed actual HTTPS hostname/certificate
  verification, returned 401 without a bearer token, returned 200 after pairing,
  and completed WSS `ready → active → ended`. This was a transport test without
  microphone input or AI audio. Its temporary test credential was revoked.
- The existing Gateway emitted `connect.challenge` on loopback. An isolated
  real agent request did not complete within 150 seconds. Logs matched that
  test session to DNS failures and included rate-limit entries. No LLM response,
  tool execution, approval flow or spoken conversation is claimed verified.
- GitHub Actions service job passed. Android setup now installs the actual
  published `android-37.0` package. Formatting passed; the first Kotlin compile
  found a ScrollView property error, corrected in the next commit. Final Android
  build/unit/lint/release results remain pending until a successful exact-head run.
- Production signing secrets are absent. A production-signed release is blocked.
  Debug prereleases must remain clearly labeled and must not be marked Latest.
- Samsung A23/Android 14 was reached through authorized wireless ADB pairing.
  Merely connecting ADB is not an app installation, microphone, or call test.

## Acceptance criteria

| Criterion | Evidence status | Current limit |
|---|---|---|
| Modified Android app buildable/installable | Implemented but not verified | Exact-head build and install pending |
| Phone connects to tablet through Tailscale | Implemented but not verified | Tablet endpoint verified; phone path pending |
| Existing OpenClaw agent receives call requests | Blocked | Live model request failed to complete; microphone path pending |
| Existing STT processes speech | Verified automatically | Real file adapter test; call microphone not verified |
| Existing LLM generates call responses | Blocked | DNS/provider connectivity |
| Existing local TTS generates audio | Verified automatically | Real adapter test; call output pending |
| Phone plays responses during a call | Implemented but not verified | Physical playback pending |
| Outgoing call end to end | Blocked | Build/device/model proof pending |
| Incoming call end to end | Blocked | Build/device/model proof pending |
| Android-supported incoming calling experience | Implemented but not verified | Telecom/CallStyle/full-screen source; device proof pending |
| Agent invokes call_user | Implemented but not verified | Real plugin tool core tested; live registration/agent invocation pending |
| Declined/missed calls handled | Verified automatically | Socket and plugin-core boundary tests; physical ringing pending |
| Telegram preserved | Implemented but not verified | Existing settings preserved; no live Telegram message test |
| Composio preserved | Implemented but not verified | Existing settings preserved; no live tool invocation |
| Security controls implemented | Implemented but not verified | Tested transport/auth boundaries; no independent audit or packaged-app proof |
| GitHub Actions creates APK/AAB | Implemented but not verified | Workflow present; successful outputs pending |
| Release has separate APK/AAB | Blocked | No verified build/release yet; production signing absent |

## Physical checklist

All items remain **unverified** until results are recorded from the actual app.
Use the real microphone and existing tablet engines, with no prerecorded clips
substituted for a conversation. Keep test requests harmless and tool permissions
unchanged. Do not delete files, publish, deploy or message others to demonstrate tools.

1. Install APK; validate package/minimum SDK, launch, pair, authenticate and test
   invalid host, wrong certificate fingerprint and wrong/expired credentials.
2. Outgoing call: two natural spoken turns establishing a reference on turn one
   and resolving it on turn two. Verify tablet STT/LLM/TTS and phone playback.
3. Incoming call with app open; Answer and complete a two-way conversation.
4. Incoming call while app backgrounded and while another app is foregrounded.
5. Incoming call locked/screen on, then locked/screen off. Record permission,
   notification/battery settings and observed UI; never infer success from source.
6. Decline, ring timeout/missed history, generic Telegram fallback on an existing
   authorized Telegram route, remote hang-up, local hang-up, duplicate invitation.
7. Same Wi-Fi direct Tailscale path, then phone mobile data/tablet home Wi-Fi.
   Record direct/DERP details. Measure signaling and transport separately.
8. Interrupt the network temporarily; verify reconnect within 20 seconds and
   clean failure beyond the grace period. Restart only the companion and repeat.
9. Mute/unmute, earpiece, speaker and Bluetooth. Confirm no microphone audio is
   sent before Answer or while muted. Check return from background and audio focus.
10. Interrupt generated playback; confirm playback stops, processing cancels,
    and the next utterance retains prior context. Do not label this full duplex.
11. Another call already active: verify Android's accepted/rejected/held behavior
    without disrupting unrelated calls. Do not call third parties for testing.
12. Harmless existing tool request, pending sensitive action approval through the
    existing approval UI, and confirmation that unrelated speech cannot approve it.
13. Existing Telegram voice/media regression and a harmless existing Composio
    operation. Verify the configured LLM/STT/TTS and plugin settings were preserved.
14. Long call and idle listener battery/thermal/resource measurement. Force-stop
    and notification-disabled cases should show the documented delivery limits.
15. Wake phrase “Hey Jarvis” on supported upstream surfaces; verify foreground,
    background and screen-off separately. Call activation via wake is not claimed.

## Timing coverage

| Measurement | Result |
|---|---|
| Call signaling | Not measured on phone |
| Audio transport | Not measured on phone |
| STT | 7.073 s, one isolated adapter utterance |
| LLM | Blocked; no completed response measured |
| Tools | Not measured |
| TTS | 5.162 s, one isolated adapter utterance |
| Total spoken response | Not measured |

Aggregate agent duration includes tool time; finer LLM/tool/transport timing
instrumentation is not yet implemented.
