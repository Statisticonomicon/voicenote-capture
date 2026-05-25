# Voice Note Capture - How to build, run and test (Phase 1)

Prototype. Two Gradle modules in one project: `:wear` (watch) and `:mobile` (phone).
Built for Android Studio Panda 4 (AGP 8.13.0 / Kotlin 2.1.20 / Gradle 8.14 / Java 21).

## Open and sync
1. Android Studio -> Open -> select the `VoiceNoteCapture` folder.
2. Let Gradle sync. Accept any AGP upgrade prompt. If it reports a missing wrapper
   jar, let Studio regenerate it or run `gradle wrapper` once in the folder.
3. Confirm `gradle/libs.versions.toml` versions; in particular check
   `desugarJdkLibs` against Maven Central and bump if a newer 2.x exists.

## Honest note on the watch<->phone link
The Data Layer transfer (watch -> phone) needs the two devices PAIRED. Pairing a
Wear EMULATOR with a PHYSICAL phone is possible but fiddly. The most reliable
setup for testing the transfer is a Wear emulator + a phone emulator, both on the
PC, paired via Android Studio's device pairing. So test in three independent
slices rather than fighting pairing up front:

### Slice A - watch alone (Wear emulator)
- Run `:wear` on a Wear OS emulator (API 34).
- Grant mic + notification permissions on first launch.
- Press the on-screen "Tap toggle" (and, if you assign a hardware button later,
  that). Status flips RECORDING/STOPPED with distinct haptics; a recording file is
  written to the watch app's internal storage. With no paired phone, WearTransfer
  logs "No reachable phone node" - expected.
- Logcat: `adb logcat -s VNC-Wear:D VNC-RecSvc:D VNC-Xfer:D`

### Slice B - phone alone (your physical phone)
- Install `:mobile` on your phone.
- Open settings: set the vault folder (and optionally raw folder) via the folder
  pickers; leave Mock mode ON for the first run.
- Tap "Import audio file -> run pipeline" and pick any audio file. This runs the
  exact save -> process -> vault chain the watch would trigger, and writes a
  Markdown note into your chosen vault folder. Confirm the note appears.
- Then turn Mock mode OFF and either point the endpoint at the mock server below
  or your real home server over Tailscale, and import again to test the network leg.
- Logcat: `adb logcat -s VNC-Listener:D VNC-Worker:D`

### Slice C - the link (emulator + emulator)
- Pair a Wear emulator with a phone emulator in Android Studio (Device Manager).
- Install `:wear` on the watch emulator and `:mobile` on the phone emulator.
- Record on the watch; confirm the phone's `VNC-Listener` logs the received file
  and the worker writes a note. This proves the Data Layer leg.

## Optional: realistic network test with the mock server
On your PC:  `python3 tools/mock_endpoint.py`  (listens on :8099)
In the phone app: Mock mode OFF, endpoint = http://<PC-ip>:8099/process
Import an audio file; the note written to the vault is the server's response.

## What "pass" looks like for Phase 1
- Watch: press toggles record/stop with haptics; file produced (Slice A).
- Phone: imported/received audio -> note written into the vault folder (Slice B).
- Link: watch recording arrives at the phone and produces a vault note (Slice C).
