# VoiceNote Capture

Wrist-driven, eyes-free voice note capture that lands transcripts in your Obsidian
vault — privately, through your own server, with no cloud service and no
subscription.

**Status:** Phase 1 prototype, with Section D (watch→phone link), the watch-face
complication launch shortcut, the always-start / crown-stop activation model,
and real haptics now all verified on real hardware. The remaining Phase 2 item
is real-world battery measurement; direct hardware-button binding is parked
until the PW4 upgrade.

---

## What it does

Tap the VoiceNote complication on your watch face → speak → press the crown to
stop and go home. The watch hands the audio to your phone over the Wear Data
Layer; the phone uploads it to your transcription endpoint (your home server,
reached over Tailscale), polls until transcription finishes, downloads the text,
and writes it as a Markdown note into your Obsidian vault. The thinking —
summarising, connecting — is left to you by design; the tool only captures and
transcribes.

Two cooperating apps in one Gradle project:

- **`:wear`** — the watch app. Two-input activation model: a **complication tap**
  always starts a recording (idempotent — onCreate / onNewIntent both route to
  the same `ensureRecording` path, and `finish()` after every stop guarantees the
  next launch is a clean onCreate); a **crown press** (via `onUserLeaveHint`)
  always stops and exits. Screen timeout still keeps capture alive (we use
  `onUserLeaveHint`, not onStop, so only deliberate user navigation triggers the
  stop). Haptic confirmation on every transition (`EFFECT_HEAVY_CLICK` start /
  `EFFECT_DOUBLE_CLICK` stop, with `USAGE_ASSISTANCE_SONIFICATION` audio
  attributes so DND profiles don't suppress them). The complication
  (`VoiceNoteComplicationService`) is the canonical launch path on the original
  Pixel Watch, whose single crown isn't user-mappable to a third-party launch.
  Recording itself runs in a microphone foreground service (mandatory on
  Android 14+); the finished file is sent to the phone via `ChannelClient`. The
  on-screen UI is the Option A "Pure Minimal" recording screen: blinking red
  status dot + "RECORDING" label, hero m:ss timer with tabular digits, live
  mic-amplitude waveform, and a quiet "Press crown to stop" hint. There is no
  idle / stopped screen variant — every entry surface (complication, launcher,
  future PW4 side button) reaches the same recording state.
- **`:mobile`** — the phone companion. Receives the audio, saves a raw copy to a
  chosen folder, then (via WorkManager, with retry) runs the asynchronous
  transcription protocol against the endpoint and writes the returned text into the
  chosen Obsidian vault folder.

The transcription endpoint itself is a separate project (a faster-whisper Flask
server in Docker). This repo treats it as a black box that returns text.

---

## Current status (Phase 1)

Validated:

- **Phone pipeline, end-to-end, on real hardware:** a manual audio import uploaded
  to the live home server, polled, downloaded, and wrote the transcript into the
  Obsidian vault. Confirmed working over Tailscale.
- **Watch capture, on the Wear emulator:** record/stop toggling (tap and
  launch-toggle), non-zero audio files produced, microphone foreground service with
  the correct Android-14 type and ongoing notification, expected "no reachable phone
  node" when unpaired.

Verified on real hardware (Phase 2 work, done 2026-06-05):

- Watch→phone Data Layer transfer (`ChannelClient`) — end-to-end on real Pixel Watch
  + Galaxy phone; transcripts landed in the vault. See `PHASE1_ACCEPTANCE_TEST.md`
  Section D for the byte-level evidence. Caveat: manifest-declared
  `voicenote_phone` capability was ignored by GMS Wearable on the test phone;
  switched to dynamic `addLocalCapability` registration in :mobile.
- Watch-face complication (`VoiceNoteComplicationService`) — one-tap launch
  shortcut from any watch face that supports `MONOCHROMATIC_IMAGE` / `SHORT_TEXT` /
  `SMALL_IMAGE` complication types. Replaces the hardware-button approach on the
  original Pixel Watch (single crown, not user-mappable to a third-party launch).
- Always-start / crown-stop activation — three full cycles in the watch log
  showed every complication tap firing onCreate→`ensureRecording`→
  `vibrate(start)`, every crown press firing onUserLeaveHint→`stopAndExit`→
  `vibrate(stop)`, no toggle drift.
- Haptic feel — `EFFECT_HEAVY_CLICK` / `EFFECT_DOUBLE_CLICK` are perceptible on
  the Pixel Watch motor (the original 60ms waveform was not).

Still deferred to Phase 2 (require the physical watch):

- Real battery behaviour during sustained recording.
- **Parked** — direct hardware-button binding: the Pixel Watch 4 ships with a
  second programmable side button; when the owner upgrades, the watch-side OEM
  button mapping becomes available and the complication becomes the universal
  fallback rather than the only path. No app-code change required at that point.

See `PHASE1_ACCEPTANCE_TEST.md` for the per-section pass/defer record.

---

## Install / run / test

See `HOW_TO_RUN.md` for full build, install, and the section-by-section test
procedure. In brief:

- Open the project in Android Studio (Panda 4 baseline) or build from the command
  line with `./gradlew :mobile:assembleDebug` and `:wear:assembleDebug`.
- Install `:mobile` on an Android phone; install `:wear` on a Wear OS device or
  emulator. Always target a specific device with `adb -s <serial>` when more than
  one is attached.
- Configure the phone app (endpoint base URL, vault folder; mock mode for offline
  testing).

---

## Features

- Complication-tap **always-start** + crown-press **always-stop** activation;
  no on-screen toggle button (single-screen "Pure Minimal" recording UI).
- Live mic-amplitude waveform + monospaced m:ss timer + blinking status dot on
  a pure-black background; nothing clips the round edge.
- Microphone foreground service with correct Android-14+ type and permissions.
- Distinct start (`EFFECT_HEAVY_CLICK`) / stop (`EFFECT_DOUBLE_CLICK`) haptics
  with sonification audio attributes (survive DND); screen-off capture
  (recording continues through screen timeout — only user-initiated navigation
  via the crown stops it).
- Optional max-duration auto-stop (off by default).
- Wear Data Layer file transfer (`ChannelClient`) keyed by a shared capability.
- Phone: SAF folder selection for raw audio and vault (no broad storage permission).
- WorkManager upload queue with retry/backoff and resume-on-retry (a long
  transcription resumes polling its job instead of re-uploading).
- Asynchronous transcription protocol (upload → poll → download).
- **Transcription provider choice**: self-hosted Whisper server (the original
  async upload/poll/download path; full privacy, no per-use cost) or **BYOK
  OpenAI Whisper** (the user supplies their own OpenAI API key; sync POST to
  `api.openai.com`, OpenAI bills the user, faster setup but audio leaves the
  owned infrastructure).
- In-app mock mode (default **off**; opt-in) for offline, server-free testing
  of the watch → phone → vault chain without a provider configured.
- Scoped cleartext: HTTP permitted only to the Tailscale host; all other hosts
  remain HTTPS-only.
- Manual "import audio" test path on the phone (exercises the full chain without a
  watch).

---

## Configuration (phone settings)

- **Transcription provider** — radio choice at the top of settings:
  - *Self-hosted (Whisper server)* — default; talks the async protocol below.
  - *OpenAI Whisper (your API key)* — sync POST to `api.openai.com`; BYOK.
- **Processing endpoint base URL** *(self-hosted)* — e.g.
  `http://<your-tailscale-host>:8457`. No path; the app appends `/upload`,
  `/status/{id}`, `/download/{id}`.
- **Auth token** *(self-hosted, optional)* — sent as a Bearer header if set.
- **OpenAI API key** *(BYOK)* — `sk-…` from your OpenAI account; password-masked
  in the UI; persisted only in SharedPreferences on the phone.
- **Mock mode** — default **OFF** (was ON during Phase 1 emulator development).
  Skips the network and writes canned text; useful for verifying the chain on a
  phone before configuring a provider.
- **Raw-audio folder** (SAF) — independent backup of the captured audio.
- **Obsidian vault folder** (SAF) — where transcript notes are written.
- **Max-duration auto-stop** (watch) — off by default.

---

## Transcription protocols

### Self-hosted (default) — asynchronous

Against the configured base URL:

```
POST {base}/upload            (multipart field "audio")     -> { "job_id": "..." }
GET  {base}/status/{job_id}   (queued|loading_model|transcribing|done|error)
GET  {base}/download/{job_id}?format=plain                  -> transcript text
```

The note is written to the vault on `done`. On `error` the worker logs the
server's message, clears the job id, and retries. Robustness: per-call HTTP
timeouts; the job id is persisted per audio path so a resumed worker continues
polling instead of re-uploading; a per-execution poll budget keeps each run
under WorkManager's ~10 min cap; `MAX_RUN_ATTEMPTS = 5` bounds runaway retries.

### OpenAI Whisper (BYOK) — synchronous

```
POST https://api.openai.com/v1/audio/transcriptions
     Authorization: Bearer <user's API key>
     multipart: model=whisper-1, response_format=text, file=<.m4a>
     -> 200, body = transcript text
```

No job_id, no polling. The response is the transcript, written to the vault
verbatim. Failures share the same retry path as the self-hosted branch
(IOException → WorkManager backoff up to `MAX_RUN_ATTEMPTS`). Note: this
provider's code path is exercised by the same `ProcessWorker` mock-mode tests,
but has not yet been hit against a real OpenAI key — first BYOK user is the
real end-to-end check.

---

## Cleartext / security note

`targetSdk` 34 blocks plain HTTP by default. `res/xml/network_security_config.xml`
permits cleartext **only** for the Tailscale host; everything else stays
HTTPS-only. This is acceptable because the Tailscale tunnel encrypts the transport —
"cleartext HTTP over Tailscale" is plaintext HTTP *inside* an encrypted tunnel, not
plaintext on the open wire. If the Tailscale hostname changes, update the config and
rebuild.

The OpenAI BYOK provider uses HTTPS to `api.openai.com` directly; nothing in the
cleartext-allow list applies to it. Switching providers does not require a
network-security-config change.

---

## File structure

```
settings.gradle.kts            include(":wear", ":mobile")
build.gradle.kts               root build config
gradle/libs.versions.toml      version catalogue (single source for versions)
gradlew, gradle/wrapper/        Gradle wrapper (8.14)

wear/                          watch module
  src/main/AndroidManifest.xml   mic FGS type, singleTask launcher activity, complication service
  src/main/java/com/notaricus/voicenote/
    WearMainActivity.kt              launch-toggle, permissions, haptics, service control
    RecordingService.kt              mic foreground service, MediaRecorder, auto-stop
    WearTransfer.kt                  Data Layer ChannelClient send
    VoiceNoteComplicationService.kt  watch-face complication: one-tap launch shortcut
  src/main/res/                  layout, strings, launcher icon, ic_voicenote_mic.xml

mobile/                        phone module
  src/main/AndroidManifest.xml   listener service, capability advert, NSC reference
  src/main/java/com/notaricus/voicenote/
    SettingsActivity.kt          settings UI + SAF pickers + manual import test
    Settings.kt                  prefs + SAF tree URIs
    PhoneListenerService.kt      receives audio over the Data Layer
    ProcessWorker.kt             async upload/poll/download, writes note to vault
  src/main/res/xml/network_security_config.xml   scoped cleartext
  src/main/res/                  layout, strings, theme, icon, wear_capabilities.xml

tools/mock_endpoint.py         optional local mock server for network testing

docs at root:
  README.md, README.txt          (byte-identical, per protocol)
  HOW_TO_RUN.md                  build, install, test procedure
  USER_GUIDE.md                  day-to-day use
  ARCHITECTURE.md                components, contract, data flow
  BUILD_PLAN.md                  phases and acceptance criteria
  PHASE1_ACCEPTANCE_TEST.md      per-section pass/defer record
  PHASE2_PLAN.md                 the real-hardware work, gated on the watch
  CLAUDE.md                      standing rules for AI-assisted work in this repo
docs/spikes/                    PHASE0_README.md, PHASE05_HOW_TO_RUN.md (history)
```

---

## Build / toolchain

- Gradle 8.14 (wrapper); AGP 8.13.0; Kotlin 2.1.20 (all via the version catalogue).
- Modules pin Java source/target 21.
- Requires a JDK **21 with a compiler** (not a JRE). The system default may be
  Java 8; Gradle is pointed at JDK 21 via `org.gradle.java.home` in the *global*
  `~/.gradle/gradle.properties`, with `JAVA_HOME` also set in `~/.bashrc`. After a
  failed-then-fixed `JAVA_HOME`, stop stale daemons: `./gradlew --stop`.
- Android SDK path lives in `local.properties` (git-ignored, machine-specific).

---

## Troubleshooting

- **`Could not resolve ... gradle:8.x ... requires JVM 11` / `JAVA_COMPILER`
  missing:** Gradle is using Java 8 or a JRE. Ensure a JDK 21 with `javac`, that
  `org.gradle.java.home` points at it, then `./gradlew --stop` and rebuild.
- **`Cleartext HTTP traffic to host not permitted`:** the endpoint host isn't in
  `network_security_config.xml`. Add the host and rebuild.
- **Note says "MOCK":** mock mode is on (it's opt-in but persists once you check it),
  or the provider isn't configured. Turn mock mode
  off and set the base URL.
- **Note never appears, log shows retries with connection errors:** the phone can't
  reach the endpoint. Confirm both devices are on the tailnet and the server is up
  (`curl http://<host>:8457/` from the phone browser).
- **Watch logs "No reachable phone node":** expected when no phone is paired.
- **Mic FGS won't start:** it must be started from the foreground (the launch
  press), never the background — an Android-14+ while-in-use restriction, not a bug.

---

## Not applicable

- **Favicon protocol:** N/A. Both UIs are native Android, not web; there is no web
  settings page. Applies only if a web UI is added later.

---

## Open items

1. **Git remote** — deferred; user decision pending. Nothing pushed.
2. **`MAX_RUN_ATTEMPTS = 5`** also bounds total job duration (~5 poll windows);
   raise it or switch to an error-only counter if single jobs run longer.
3. **No auth token set** — requests go unauthenticated (fine if the server allows).
4. **Phase 2** — see `PHASE2_PLAN.md`: watch→phone link, button binding, battery,
   haptics; all gated on the physical watch (chosen: Google Pixel Watch 4).

---

## Change history

- Phase 1: two-module scaffold (watch + phone); launch-toggle activation confirmed
  via spikes (Phase 0 disproved hardware key events; Phase 0.5 confirmed the
  launch/onNewIntent toggle); mic foreground service; Data Layer transfer;
  asynchronous upload/poll/download transcription in ProcessWorker; SAF vault write;
  scoped cleartext config; version catalogue; core library desugaring. Toolchain
  brought to the Android Studio Panda 4 baseline. Phone pipeline validated
  end-to-end on real hardware against the live home server. Watch capture validated
  on the Wear emulator. Watch→phone link and other hardware items deferred to
  Phase 2.

> NOTE: Git commit hashes and any file changes made after the last `state.txt`
> snapshot are authoritative in the live repository, not in this document. Reconcile
> the commit list against `git log` before treating this README as final.
