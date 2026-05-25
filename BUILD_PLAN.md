# Voice Note Capture — Build Plan

**Status:** Prototype / living document.
**Phase 1 target:** validate the concept end-to-end on the Wear OS emulator at zero hardware cost.

## Goal

Distraction-free, eyes-free, phone-free voice note capture from the wrist. Press to record on the watch, hand the audio off to the phone, the phone ships it to a configurable processing endpoint (home server by default), and writes the returned text into the Obsidian vault.

## Scope

**In scope:** Wear OS watch app (capture + handoff); Android phone companion app (receive, persist raw audio, call endpoint, write result to vault); settings; the audio→text endpoint contract; an emulator test harness with a mock endpoint.

**Out of scope:** server-side processing internals (transcription, summarisation, action lists, to-do population); backups; cloud-synced folders; Tailscale setup (already deployed); Phase 2 wake word.

## Phases

### Phase 0 — Gating spike (COMPLETE — result: hardware keys NOT delivered)
- Question: can a foreground Wear app receive hardware button key events?
- **Result (emulator, API 34):** NO. `onKeyDown` never fired. adb stem keyevents
  264 (STEM_PRIMARY) routed to Recents; 265/266/267 fired launch intents
  ("Starting…") and returned. Wear OS reserves the hardware buttons; apps cannot
  intercept them as key presses.
- **Consequence:** the original hardware-key activation (one-button toggle AND
  two-button start/stop, plus the one-vs-two-button setting) is INVALID and has
  been removed. Do not rely on key events.

### Phase 0.5 — Launch-toggle spike (COMPLETE — result: PASS)
- Question: can the LAUNCH path drive the app instead? With launchMode=singleTask,
  first launch = onCreate (start), re-launch = onNewIntent (toggle).
- **Result (emulator, API 34):** PASS. Across 5 simulated button presses
  (`am start`), every re-launch hit `onNewIntent` on the SAME instance (PID
  stable) and toggled RECORDING<->STOPPED reliably.
- **Confirmed activation model:** single hardware button assigned to the app;
  first press starts recording, each subsequent press toggles stop/start, via the
  launch/onNewIntent path. On-screen tap is the guaranteed fallback.
- **Still open (Phase 2, real hardware):** whether the OnePlus permits binding a
  physical button to the app (its Settings → button mapping). Software mechanism
  is proven; the wiring is a watch-only question.

### Phase 1 — Concept validation on emulator (£0 hardware)

**Watch app**
- Activation: single hardware button (assigned to the app) via the launch path —
  first press starts, each subsequent press toggles stop/start (onCreate /
  onNewIntent, launchMode=singleTask). On-screen tap is the guaranteed fallback.
- Distinct start/stop haptics; screen stays off.
- Record mono 16 kHz AAC/m4a (configurable) to local watch storage.
- Optional max-duration auto-stop (off by default).
- Transfer audio to phone via Wear Data Layer `ChannelClient`; then idle.

**Phone companion app**
- Receive audio over the Data Layer.
- Persist raw audio immediately to a user-chosen folder (before any upload).
- Upload queue with retry: POST audio to the configured endpoint; async job + poll for long jobs; queue when endpoint unreachable.
- On response, write the returned text into the user-chosen Obsidian vault folder.
- Hold all settings.

**Test harness**
- Mock endpoint returning canned text, so the full pipeline is testable without the real server.

**Acceptance criteria (Phase 1 done = all true)**
- Press → speak → press produces an audio file on the watch, transferred to the phone.
- Raw audio persisted to the chosen folder; survives an endpoint failure.
- Mock endpoint round-trip writes a text file into the chosen vault folder.
- Queue retries after a simulated offline period.
- Both activation paths work: launch-toggle (start, then toggle on re-launch) and the on-screen tap fallback.
- Auto-stop toggle works.
- Runs entirely in the Wear OS emulator + a paired phone (real or emulated).

### Phase 2 — Real hardware (OnePlus in hand)
- Tune haptic patterns by feel.
- Point the endpoint at the real home server over Tailscale.
- Validate battery, button ergonomics, transfer reliability.
- Optional: "notaricus" custom wake word (Porcupine / openWakeWord) for true no-hands capture; battery and reliability tuned here, not on the emulator.

### Phase 3 — Polish
- Settings UX, error states, edge cases, notifications.
- Fill in READMEs and user guide; record change history.

## Engineering standards (per project protocol)
- Proper error handling, edge cases, docstrings; prototypes explicitly labelled; "it runs" is not "done"; check against established Wear patterns.
- READMEs (`.txt` + `.md`, identical content) kept current with any code or asset change.
- Favicon protocol: N/A unless a web settings UI is added later.

## Open items / risks
- RESOLVED: Phase 0 button-event question (hardware keys not delivered; launch-toggle adopted instead, confirmed in Phase 0.5).
- Phase 2 (real hardware): whether the OnePlus permits binding a physical button to the app (Settings → button mapping).
- Local-LLM latency on long recordings → async job design (handled).
- OnePlus global availability / warranty (purchase-time consideration).
