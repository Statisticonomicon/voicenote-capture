# VoiceNote Capture — Build Plan

Status: Phase 1 complete (software pipeline validated). Phase 2 gated on the
physical watch — see `PHASE2_PLAN.md`.

## Goal

Distraction-free, eyes-free, phone-free voice capture from the wrist → phone →
transcription endpoint (home server over Tailscale) → text note in the Obsidian
vault. Transcription only; the user does the summarising.

## Phases and outcomes

### Phase 0 — Gating spike (COMPLETE)
- Question: can a foreground Wear app receive hardware button key events?
- Result: **No.** Hardware keys are reserved by Wear OS; `onKeyDown` never fired.
- Consequence: the original key-event activation (two-button, and one-vs-two-button
  setting) was invalid and removed.

### Phase 0.5 — Launch-toggle spike (COMPLETE)
- Question: can the launch path drive the app instead? `singleTask`: first launch =
  `onCreate` (start), re-launch = `onNewIntent` (toggle).
- Result: **PASS** — re-launches hit the same instance via `onNewIntent` (PID
  stable), toggling reliably.
- Confirmed activation model: single hardware button launch-toggle, on-screen tap
  fallback.

### Phase 1 — Concept validation (COMPLETE)
Built: `:wear` (launch-toggle + mic foreground service + Data Layer send) and
`:mobile` (receive → save raw → async upload/poll/download → write to vault), a
version catalogue, scoped cleartext config, core library desugaring, a mock mode and
a manual import test harness.

Acceptance (see `PHASE1_ACCEPTANCE_TEST.md`):
- A (phone, mock) PASS · B (phone, real server) PASS · C (watch capture, emulator)
  PASS · D (watch→phone link) PASS on real hardware 2026-06-05.
- Phone pipeline validated end-to-end on a real phone against the live home server.

### Phase 2 — Real hardware (IN PROGRESS)
Done on the original Pixel Watch (2026-06-05): Section D, watch-face complication
as launch shortcut, always-start / crown-stop activation, real haptics
(`EFFECT_HEAVY_CLICK` / `EFFECT_DOUBLE_CLICK`). Still open: real-world battery
measurement. Parked until the Pixel Watch 4 upgrade: direct hardware-button
binding via the side button (no app change needed at that point). Full detail in
`PHASE2_PLAN.md`.

### Phase 3 — Polish (future)
Settings UX, error states, broader edge cases; keep docs current.

## Engineering standards (per CLAUDE.md)
- Proper error handling, edge cases, docstrings; prototypes labelled; "it runs" is
  not "done"; check against established Wear/Android patterns.
- README.md + README.txt byte-identical, updated with any code/asset change.
- Versions live in `gradle/libs.versions.toml`; bump deliberately ("keep current").
- Favicon protocol: N/A (native apps, no web UI).

## Open items / risks
- RESOLVED: Phase 0 button-event question (launch-toggle adopted, confirmed in 0.5;
  later superseded by always-start / crown-stop after on-watch testing — see
  ARCHITECTURE.md).
- RESOLVED: JDK 21 toolchain (system JDK 21 + `org.gradle.java.home` + `.bashrc`).
- RESOLVED: cleartext (scoped network-security-config for the Tailscale host).
- RESOLVED (2026-06-05): Section D (Data Layer link), watch-face complication,
  always-start / crown-stop activation, real haptics.
- Phase 2 remaining: real-world battery measurement. Parked for PW4: direct
  hardware-button binding via the side button.
- `MAX_RUN_ATTEMPTS=5` also bounds total job duration — adjust for very long jobs.
- Git remote: deferred, user decision pending.
