# Phase 1 — Acceptance Test

Run top to bottom. Tick each box. Phase 1 PASSES only if every box in sections A–D is ticked.
Scope: software pipeline on emulator + your phone. Hardware button / battery / real server = Phase 2 (NOT tested here).

Log filters (run in a terminal alongside):
- Watch:  `adb logcat -s VNC-Wear:D VNC-RecSvc:D VNC-Xfer:D`
- Phone:  `adb logcat -s VNC-Listener:D VNC-Worker:D`

---

## Pre-flight (must all be true before starting)
- [ ] `./gradlew --stop` then `./gradlew :mobile:assembleDebug :wear:assembleDebug` → BUILD SUCCESSFUL for both, with no manual JAVA_HOME export (i.e. the permanent Java 21 fix holds).
- [ ] `git status` clean; ARCHITECTURE.md and BUILD_PLAN.md present in repo; build/ and .gradle/ git-ignored.
- [ ] APKs exist: mobile/build/outputs/apk/debug/mobile-debug.apk, wear/build/outputs/apk/debug/wear-debug.apk.

## A. Phone alone — the core pipeline (your physical phone; no watch needed)
- [ ] Install mobile-debug.apk on the phone.
- [ ] Open the companion app; grant notification permission if asked.
- [ ] In settings: choose an Obsidian vault folder via the picker (SAF permission persists). Leave **Mock mode ON**.
- [ ] Tap "Import audio file → run pipeline"; pick any audio file.
- [ ] **CHECK:** a Markdown note appears in the chosen vault folder within a few seconds (filename like `imported-<ts>-<ts>.md`), containing the MOCK transcript text.
- [ ] Logcat shows VNC-Worker processing and writing to vault, no exceptions.

## B. Phone alone — real network leg (proves the endpoint contract, still no watch)
- [ ] On your PC: `python3 tools/mock_endpoint.py` (listens on :8099).
- [ ] In app settings: **Mock mode OFF**; endpoint = `http://<PC-LAN-or-tailscale-ip>:8099/process`. Save.
- [ ] Import an audio file again.
- [ ] **CHECK:** the new vault note contains the *server's* response text ("Voice note (mock server)…", with the byte count), not the in-app mock text. This proves the POST + response + vault-write path.
- [ ] **CHECK (resilience):** stop the mock server, import again → note does NOT appear immediately and VNC-Worker logs a retry; restart the server → the note appears once WorkManager retries. (Confirms queue/retry.)

## C. Watch alone — capture (Wear emulator on the PC)
- [ ] Install wear-debug.apk on a Wear OS emulator (API 34).
- [ ] Launch; grant microphone + notification permissions.
- [ ] Tap the on-screen "Tap toggle" (fallback control). **CHECK:** status flips STOPPED→RECORDING, start haptic logged/felt; a foreground-service notification appears.
- [ ] Speak a few seconds; tap again. **CHECK:** status RECORDING→STOPPED, distinct stop haptic; VNC-RecSvc logs a saved file with non-zero bytes.
- [ ] **CHECK (launch-toggle):** with the app open, run `adb shell am start -n com.notaricus.voicenote/.WearMainActivity` twice → each re-launch toggles state via onNewIntent (mirrors the confirmed Phase 0.5 behaviour).
- [ ] With no phone paired, VNC-Xfer logs "No reachable phone node" — expected, not a failure.

## D. The link — watch → phone (Wear emulator + phone emulator, both on PC, paired)
- [ ] Pair a Wear emulator with a phone emulator (Device Manager). Install wear on the watch, mobile on the phone emulator; set vault folder + Mock mode ON on the phone.
- [ ] Record on the watch (toggle start, speak, toggle stop).
- [ ] **CHECK:** VNC-Listener on the phone logs a received file; VNC-Worker writes a note into the vault folder. End-to-end watch→phone→vault confirmed.

---

## Result
- [ ] All of A–D ticked → **Phase 1 PASS.** The concept works end to end in software.

## Explicitly NOT covered (Phase 2, real OnePlus/Wear hardware)
- Binding a physical hardware button to the app (Settings → button mapping).
- Battery behaviour during real recording.
- Haptic *feel* tuning (emulator can't reproduce it).
- The real home server over Tailscale (transcription + summarisation quality).
- "notaricus" wake word (optional, no-hands).

## If a box fails
Note which section + the VNC-* log line, and bring it back. Don't tick partially —
a half-working leg is a fail for that box; that's the point of the checklist.
