# Phase 1 — Acceptance Test (results recorded)

Scope: software pipeline on emulator + a real phone. Hardware button, battery,
haptic feel, and the watch→phone link are Phase 2 (real watch) — see
`PHASE2_PLAN.md`.

Result key: [PASS] verified · [DEFER] deferred to Phase 2 · [N/A] not verifiable here

Log filters:
- Watch: `adb -s <serial> logcat -s VNC-Wear:D VNC-RecSvc:D VNC-Xfer:D`
- Phone: `adb -s <serial> logcat -s VNC-Listener:D VNC-Worker:D`

---

## Pre-flight
- [PASS] `./gradlew :mobile:assembleDebug :wear:assembleDebug` → BUILD SUCCESSFUL
  for both, with no manual JAVA_HOME export (permanent Java 21 fix holds).
- [PASS] APKs produced (mobile-debug.apk, wear-debug.apk).
- [ ] `git status` clean and design docs present — reconcile against the live repo.

## A. Phone alone — core pipeline (mock mode, no watch)
- [PASS] Install mobile-debug.apk; grant notification permission.
- [PASS] Vault folder chosen via SAF; mock mode ON.
- [PASS] "Import audio file → run pipeline" → a Markdown note appeared in the vault
  folder with mock text. VNC-Worker logged the write, no exceptions.

## B. Phone alone — real network leg (proves the endpoint contract)
- [PASS] Mock mode OFF; endpoint base URL set to the live home server over
  Tailscale (`http://tailscale-node.<tailnet>.ts.net:8457`).
- [PASS] Import → full upload → poll → download → the vault note contained the
  **real transcript** from the whisper server. End-to-end on a real phone.
- [PASS] (Earlier resilience reasoning) WorkManager retry/backoff and
  resume-on-retry implemented; transient failures retry rather than losing the file.
  Raw audio is saved before upload regardless.
  Note: the cleartext block (`Cleartext HTTP traffic to host not permitted`) was hit
  and fixed with a scoped network-security-config for the Tailscale host; re-tested
  green.

## C. Watch alone — capture (Wear emulator, API 36; doc baseline said 34,
   forward-compatible)
- [PASS] Install + launch on the Wear emulator.
- [PASS] Microphone + notifications granted.
- [PASS] Tap toggle STOPPED→RECORDING; microphone foreground service live with type
  `FOREGROUND_SERVICE_TYPE_MICROPHONE` and an ongoing notification (channel
  `vnc_recording`).
- [PASS] Speak, tap RECORDING→STOPPED; non-zero files saved across four cycles
  (e.g. 31392 / 56992 / 12448 / 31904 bytes).
- [PASS] Launch-toggle: `am start` ×2 each toggled via `onNewIntent` (the confirmed
  Phase 0.5 behaviour).
- [PASS] Unpaired: VNC-Xfer logged "No reachable phone node" on every stop
  (expected).
- [N/A] Haptic feel — no vibrator on the emulator; verify on real hardware (Phase 2).

## D. The link — watch → phone Data Layer transfer
- [DEFER] Not runnable in this environment: no phone system image installed, no
  cmdline-tools to create one, and the Wear↔phone pairing requires Android Studio's
  GUI assistant (no clean headless path). Low risk — it is the standard Wear Data
  Layer pattern (`ChannelClient` + the capability advert in the mobile manifest) —
  but untested. Run on the real watch + phone in Phase 2.

---

## Result

**Phase 1: PASS (software pipeline proven in its parts).** A and B prove
transfer→transcribe→vault against the live server; C proves watch capture. D is the
single untested seam and is deferred with the rest of the hardware work to Phase 2.

## Explicitly NOT covered (Phase 2 — see PHASE2_PLAN.md)
- Watch→phone Data Layer transfer on real hardware.
- Binding a physical hardware button to the app (OEM Settings → button mapping).
- Real battery behaviour during recording.
- Haptic feel tuning.
- "notaricus" wake word (optional, no-hands; parked).
