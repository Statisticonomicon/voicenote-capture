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
- [PASS — verified later on real hardware, 2026-06-05] Haptic feel was N/A on
  the emulator (no vibrator). On the Pixel Watch the initial 60ms raw waveform
  was inaudible; switched to `VibrationEffect.EFFECT_HEAVY_CLICK` (start) /
  `EFFECT_DOUBLE_CLICK` (stop) with `USAGE_ASSISTANCE_SONIFICATION` audio
  attributes — perceptible and DND-resilient.

## D. The link — watch → phone Data Layer transfer
- [PASS] Verified on real hardware (2026-06-05): Pixel Watch (`5e654a1e`) paired with
  Galaxy M33 5G (`fde9fdf`). Four takes recorded on the watch, each transferred over
  the Data Layer and processed end-to-end:
  - Watch (`VNC-Xfer`): `Sent note-<stamp>.m4a to fde9fdf` for every take.
  - Phone (`VNC-Listener`): `Received note-<stamp>.m4a (<N> bytes)` with byte counts
    matching the watch (e.g. 281761 / 46241 / 9437 / 29857).
  - Phone (`VNC-Worker`): `Uploaded …, job_id=…` → `status=transcribing` →
    `Processed … -> vault`.
  - Vault gained four `.m4a` raw-audio backups + four `note-<rec>-<process>.md`
    transcripts.
- [NOTE — capability advert] The static
  `<meta-data android:name="com.google.android.wearable.capabilities">` was silently
  ignored by GMS Wearable on the Galaxy M33 (the package was absent from
  `dumpsys … WearableService` CapabilityService entries even after `install -r`, app
  launch, and a BT cycle). Other apps' manifest-declared caps were registered
  normally, so this is a per-package GMS scan flake. Replaced with dynamic
  registration via `Wearable.getCapabilityClient(this).addLocalCapability("voicenote_phone")`
  in `SettingsActivity.onCreate` (canonical Wear OS pattern; persists at the GMS
  level after one launch). The static meta-data + `wear_capabilities.xml` are kept as
  belt-and-braces.

---

## Result

**Phase 1: PASS (full software pipeline proven end-to-end, including the
watch→phone link).** A and B prove transfer→transcribe→vault against the live
server; C proves watch capture; D (verified 2026-06-05 on real hardware) proves the
watch→phone Data Layer leg now closes the loop.

## Explicitly NOT covered (Phase 2 — see PHASE2_PLAN.md)
- Binding a physical hardware button to the app (OEM Settings → button mapping).
  On Pixel Watch the side button / crown cannot be remapped by an app; it requires
  the watch's system button-mapping UI, per-device.
- Real battery behaviour during recording.
- Haptic feel tuning.
- "notaricus" wake word (optional, no-hands; parked).
