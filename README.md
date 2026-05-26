# VoiceNote Capture

Wrist-driven, eyes-free voice note capture that lands transcripts in your Obsidian
vault — privately, through your own server, with no cloud service and no
subscription.

**Status:** Phase 1 prototype. Software pipeline validated end-to-end against a
live home server. Remaining work (the watch→phone link, hardware-button binding,
battery, haptics) is Phase 2 and needs the physical watch.

---

## What it does

Press a button on the watch, speak, press to stop. The watch hands the audio to
your phone over the Wear Data Layer; the phone uploads it to your transcription
endpoint (your home server, reached over Tailscale), polls until transcription
finishes, downloads the text, and writes it as a Markdown note into your Obsidian
vault. The thinking — summarising, connecting — is left to you by design; the tool
only captures and transcribes.

Two cooperating apps in one Gradle project:

- **`:wear`** — the watch app. Single-button launch-toggle activation (first press
  starts recording, each subsequent press toggles stop/start, via
  onCreate/onNewIntent under `launchMode=singleTask`); distinct start/stop haptics;
  screen-off capture. Recording runs in a microphone foreground service (mandatory
  on Android 14+). The finished file is sent to the phone via `ChannelClient`.
  On-screen tap is a guaranteed fallback control.
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

Deferred to Phase 2 (require the physical watch):

- Watch→phone Data Layer transfer (`ChannelClient`) — never exercised; blocked in
  the emulator by the lack of a phone system image and the GUI-only Wear pairing
  assistant.
- Hardware-button binding to the app (OEM Settings → button mapping).
- Real battery behaviour and haptic feel (an emulator has no vibrator).

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

- Single-button launch-toggle activation; on-screen tap fallback.
- Microphone foreground service with correct Android-14+ type and permissions.
- Distinct start/stop haptics; screen-off capture.
- Optional max-duration auto-stop (off by default).
- Wear Data Layer file transfer (`ChannelClient`) keyed by a shared capability.
- Phone: SAF folder selection for raw audio and vault (no broad storage permission).
- WorkManager upload queue with retry/backoff and resume-on-retry (a long
  transcription resumes polling its job instead of re-uploading).
- Asynchronous transcription protocol (upload → poll → download).
- Configurable endpoint base URL and optional auth token.
- In-app mock mode (default on) for offline, server-free testing.
- Scoped cleartext: HTTP permitted only to the Tailscale host; all other hosts
  remain HTTPS-only.
- Manual "import audio" test path on the phone (exercises the full chain without a
  watch).

---

## Configuration (phone settings)

- **Processing endpoint base URL** — e.g. `http://<your-tailscale-host>:8457`.
  No path; the app appends `/upload`, `/status/{id}`, `/download/{id}`.
- **Auth token** (optional) — sent as a Bearer header if set.
- **Mock mode** — default ON; skips the network and writes canned text.
- **Raw-audio folder** (SAF) — independent backup of the captured audio.
- **Obsidian vault folder** (SAF) — where transcript notes are written.
- **Max-duration auto-stop** (watch) — off by default.

---

## Transcription protocol (what the phone speaks to the endpoint)

Asynchronous, against the configured base URL:

```
POST {base}/upload            (multipart field "audio")     -> { "job_id": "..." }
GET  {base}/status/{job_id}   (queued|loading_model|transcribing|done|error)
GET  {base}/download/{job_id}?format=plain                  -> transcript text
```

The note is written to the vault on `done`. On `error` the worker logs the server's
message, clears the job id, and retries. Robustness: per-call HTTP timeouts; the
job id is persisted per audio path so a resumed worker continues polling instead of
re-uploading; a per-execution poll budget keeps each run under WorkManager's ~10 min
cap; `MAX_RUN_ATTEMPTS = 5` bounds runaway retries.

---

## Cleartext / security note

`targetSdk` 34 blocks plain HTTP by default. `res/xml/network_security_config.xml`
permits cleartext **only** for the Tailscale host; everything else stays
HTTPS-only. This is acceptable because the Tailscale tunnel encrypts the transport —
"cleartext HTTP over Tailscale" is plaintext HTTP *inside* an encrypted tunnel, not
plaintext on the open wire. If the Tailscale hostname changes, update the config and
rebuild.

---

## File structure

```
settings.gradle.kts            include(":wear", ":mobile")
build.gradle.kts               root build config
gradle/libs.versions.toml      version catalogue (single source for versions)
gradlew, gradle/wrapper/        Gradle wrapper (8.14)

wear/                          watch module
  src/main/AndroidManifest.xml   mic FGS type, singleTask launcher activity
  src/main/java/com/notaricus/voicenote/
    WearMainActivity.kt          launch-toggle, permissions, haptics, service control
    RecordingService.kt          mic foreground service, MediaRecorder, auto-stop
    WearTransfer.kt              Data Layer ChannelClient send
  src/main/res/                  layout, strings, launcher icon

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
- **Note says "MOCK":** mock mode is on, or the endpoint isn't set. Turn mock mode
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
