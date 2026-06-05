# VoiceNote Capture — How to build, run and test

Two Gradle modules in one project: `:wear` (watch) and `:mobile` (phone).
Built for Android Studio Panda 4 baseline: AGP 8.13.0 / Kotlin 2.1.20 / Gradle 8.14
/ Java 21.

---

## Prerequisites

- Android Studio (Panda 4 / 2025.3 line) **or** command-line Gradle.
- **A JDK 21 with a compiler (`javac`)** — not a JRE, and not Java 8. This is the
  single most common setup failure (see Toolchain gotchas).
- Android SDK with platform 34+ and a Wear OS system image for the emulator.
- `adb` on PATH (Android SDK `platform-tools`).

---

## Open / build

From Android Studio: **File → Open** the project folder, let Gradle sync, accept any
AGP upgrade prompt.

From the command line:

```
./gradlew :mobile:assembleDebug
./gradlew :wear:assembleDebug
```

APKs land at:

```
mobile/build/outputs/apk/debug/mobile-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

---

## Toolchain gotchas (read before first build)

The system default Java may be 8, and some JDK 21 packages are JRE-only (no
`javac`). Both break the build. The durable fix used in this project:

1. Install a full JDK 21 (`sudo apt install openjdk-21-jdk`), confirm
   `javac -version` shows 21. If not: `sudo update-alternatives --config javac`.
2. Point Gradle at it, globally (never committed):
   ```
   echo "org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64" >> ~/.gradle/gradle.properties
   ```
3. Set `JAVA_HOME` in `~/.bashrc` too:
   ```
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   ```
4. If a build failed earlier in the same session under Java 8, **stop stale
   daemons** before retrying — they cache the old JVM:
   ```
   ./gradlew --stop
   ```

Verify Gradle picked 21: `./gradlew --version` should show
`Daemon JVM: ... java-21-openjdk-amd64 (from org.gradle.java.home)`.

`local.properties` holds the machine-specific `sdk.dir` and is git-ignored — create
it if missing: `echo "sdk.dir=$HOME/Android/Sdk" > local.properties` (adjust path).

The Gradle wrapper jar is generated on first Android Studio import; from the command
line run `gradle wrapper` once if `gradlew` is absent.

---

## Devices and `adb -s`

When more than one device is attached (e.g. your phone **and** a Wear emulator),
**every** adb/install command must target one explicitly, or you will install to the
wrong device:

```
adb devices                                   # list serials
adb -s <phone-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s emulator-5554  install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Log filters:

```
adb -s <serial> logcat -s VNC-Wear:D VNC-RecSvc:D VNC-Xfer:D     # watch
adb -s <serial> logcat -s VNC-Listener:D VNC-Worker:D            # phone
```

---

## Test procedure (Phase 1 sections)

The full pass/defer record is in `PHASE1_ACCEPTANCE_TEST.md`. Summary of how to run
each:

> Note: as of Phase 2 the mock-mode default is **OFF** for new installs (was ON
> during Phase 1 dev). For the mock-mode-based Section A below, toggle the
> **Mock mode** switch on inside the redesigned settings screen before running
> it. (The original checkbox was replaced by a switch in the companion redesign.)

### Section A — phone alone, mock mode (core pipeline, no watch)
1. Install `:mobile` on the phone.
2. Settings: pick a vault folder; leave **Mock mode ON**.
3. In the Test card at the bottom, tap **Run pipeline test**; pick any audio
   file. (This was labelled "Import audio file → run pipeline" before the
   companion redesign; the underlying handler is unchanged.)
4. PASS: a Markdown note (mock text) appears in the vault folder.

### Section B — phone alone, real network leg
1. Run the endpoint (your transcriber, or `python3 tools/mock_endpoint.py` on
   :8099 for a network stand-in).
2. Settings: **Mock mode OFF**; endpoint base URL =
   `http://<host>:<port>` (no path).
3. Import an audio file.
4. PASS: the note contains the *server's* response (the real transcript from your
   whisper server). Resilience check: stop the server, import (worker logs a retry,
   no note), restart the server, the note appears on retry.

### Section C — watch alone
1. Install `:wear` on the watch (or Wear emulator); grant mic + notification
   permissions.
2. **Launch starts recording (no toggle button):** since the Phase-2 redesign,
   the activity has no idle state — every external launch starts a recording.
   On the watch, tap the VoiceNote complication; on emulator/ADB, run
   `adb -s <serial> shell am start -n com.notaricus.voicenote/.WearMainActivity`.
   The screen renders RECORDING + a counting m:ss timer + a live waveform.
3. **End the session:** on the watch, press the crown (fires `onUserLeaveHint`
   → `stopAndExit` → finish). On ADB, `adb -s <serial> shell am force-stop
   com.notaricus.voicenote` reaches the same outcome. Expected log lines:
   `vibrate(stop) fired` and `stop -> STOPPED via user-leave`.
4. With no phone paired, `VNC-Xfer` logs "No reachable phone node" (expected).
   On an emulator, haptics are a no-op (no vibrator); verifiable only on real
   hardware (done 2026-06-05 with `EFFECT_HEAVY_CLICK` / `EFFECT_DOUBLE_CLICK`).

### Section D — watch → phone link
*(2026-06-05: PASS on real hardware — see `PHASE1_ACCEPTANCE_TEST.md`. Originally
deferred because the emulator had no phone system image and the Wear pairing
assistant is GUI-only.)*

---

## What "Phase 1 pass" means

A, B, C pass → the pipeline is proven in its parts: capture (C), and
transfer→transcribe→vault against the real server (B). D was the original
Phase 2 seam; as of 2026-06-05 it is also verified on real hardware along with
the complication-driven activation model, the always-start / crown-stop UX, and
real haptics. Remaining Phase 2 item: long-form battery measurement.
