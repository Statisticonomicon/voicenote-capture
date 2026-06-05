# Phase 2 — Plan (real-hardware work)

Everything here is gated on having the physical watch in hand. None of it could be
validated on the emulator, which is why it was deferred from Phase 1 rather than
skipped.

## Target hardware

**Google Pixel Watch 4** (decision rationale, for the record):
- Chosen for long software/security support and sustainability (Google's multi-year
  Wear OS update commitment; repairable design — screen and battery replaceable,
  iFixit partnership).
- Brand-agnostic: pairs with any Android phone, which matters because the owner's
  phone path is Samsung now → Sony later. A Samsung watch would partly lock to a
  brand being left behind.
- Wear OS, so it runs this app unchanged (the app targets the platform, not a
  vendor — nothing in the codebase is OnePlus/Pixel/Samsung-specific).
- Size: 45mm or 41mm to be decided by in-store fit (battery difference is minor and
  acceptable — owner is fine charging nightly; the deciding factor is how it sits on
  the wrist). Wi-Fi/Bluetooth model (not LTE — no need, avoids extra cost and a data
  plan).

The OnePlus Watch 3, previously considered for battery, was ruled out on software
support grounds.

## Work items (in suggested order)

1. **Install on the real watch.** Sideload `wear-debug.apk`; confirm capture works
   on hardware as it did on the emulator (record/stop, files saved).
   *(2026-06-05: DONE on the original Pixel Watch.)*

2. **Section D — watch → phone Data Layer transfer.** The one untested seam. Pair
   the watch with the phone, record on the watch, confirm `PhoneListenerService`
   receives the file via `ChannelClient` and `ProcessWorker` writes a note to the
   vault. This closes the end-to-end loop (capture → transfer → transcribe → vault)
   on real hardware.
   *(2026-06-05: DONE — see `PHASE1_ACCEPTANCE_TEST.md` Section D. Caveat: GMS
   Wearable on the Galaxy M33 silently ignored the manifest-declared
   `voicenote_phone` capability advert; switched to dynamic
   `CapabilityClient.addLocalCapability` in mobile `SettingsActivity.onCreate`.)*

3. **One-tap launch from the watch face — complication.** The original Pixel Watch
   has a single user-facing button (the rotating crown), and the platform does not
   let a third-party app bind it for arbitrary launch — so OEM-Settings button
   mapping isn't available on this hardware. The replacement (and on this watch,
   the canonical one-tap path) is a watch-face **complication**: a
   `ComplicationDataSourceService` in `:wear` (`VoiceNoteComplicationService`) that
   the user places on a complication slot of their preferred watch face. A single
   tap on the complication launches `WearMainActivity`; under `singleTask`, that is
   the same Activity-start path Phase 0.5 confirmed produces
   onCreate → onNewIntent toggling on each subsequent launch.

   Verified on real hardware (2026-06-05): complication appeared in the picker,
   tap-to-launch produced two complete capture → transfer → transcribe → vault
   cycles; toggle-via-on-screen-button used for stop (the complication second-tap
   path inherits from the Phase 0.5 `am start ×2` verification — same PendingIntent
   semantics).

   **Parked enhancement — Pixel Watch 4 side button.** The PW4 adds a second
   programmable side button alongside the crown. On that hardware the older
   hardware-button-binding approach becomes available again (assign the side button
   to launch the app directly, eliminating the trip through the watch face). When
   the owner upgrades to PW4 this is a small follow-up: nothing in the app needs to
   change — only the watch's button-mapping setting. The complication stays as the
   universal fallback (and remains the only path on the original Pixel Watch).

4. **Battery behaviour.** Measure real drain during and around recording; tune the
   optional max-duration auto-stop default if needed.

5. **Haptic feel.** Tune the start (single pulse) and stop (double pulse) patterns
   by feel — impossible on an emulator (no vibrator). The logic and call path are
   already in place and fired correctly in testing.

## Optional / parked

- **"notaricus" wake word** — true no-hands activation via on-device keyword
  spotting (e.g. Picovoice Porcupine / openWakeWord). Parked deliberately: the
  button launch-toggle already delivers eyes-free capture, and an always-on listener
  is the most battery-expensive, least-emulatable component. Revisit only if no-hands
  capture proves genuinely needed on the real watch.

- **Summarisation / to-do extraction** — explicitly NOT planned. The owner's
  position: summarising is where internalisation happens, so auto-summarising would
  defeat the purpose. Transcription-only by design. (If ever revisited, it would run
  as a separate LLM step on the server, not in the app.)

## Dependencies / environment notes for Phase 2

- The transcription endpoint (faster-whisper Flask server in Docker) is reachable
  over Tailscale at a stable MagicDNS hostname. If that hostname ever changes, both
  the phone's endpoint setting and the app's `network_security_config.xml` cleartext
  scope must be updated (and the APK rebuilt for the latter).
- A dedicated always-on server machine is planned (a couple of months out) to host
  the transcriber and other containers, freeing the desktop. The endpoint hostname
  approach means clients won't need reconfiguring when containers migrate, provided
  the MagicDNS name is preserved.
