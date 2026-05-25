# Phase 0 — How to run and what to check

## Prerequisites
- Android Studio (recent), with the Android SDK and a Wear OS system image.
- adb on your PATH (Android SDK platform-tools). On Linux Mint it is usually at
  ~/Android/Sdk/platform-tools/adb.

## Open the project
1. Android Studio -> Open -> select this folder.
2. Let Gradle sync. If prompted to upgrade AGP/Gradle, accept.
   (If sync complains about a missing wrapper, run `gradle wrapper` in the folder,
   or let Android Studio generate it.)

## Create a Wear OS emulator
1. Device Manager -> Add a device -> Wear OS -> e.g. "Wear OS Large Round", API 34.
2. Start the emulator.

## Run
1. Select the `app` configuration and the Wear emulator, then Run.
2. The app shows a big IDLE/ACTIVE status, a fallback "Tap toggle" button, and a
   scrolling event log.

## Test the hardware-button path (the actual Phase 0 question)
The reliable way to test stem keys in the emulator is to inject them with adb:

    adb devices                  # confirm the emulator is listed
    adb shell input keyevent 264 # KEYCODE_STEM_PRIMARY
    adb shell input keyevent 265 # KEYCODE_STEM_1
    adb shell input keyevent 266 # KEYCODE_STEM_2
    adb shell input keyevent 267 # KEYCODE_STEM_3

After each, check that:
- the on-screen log shows `onKeyDown code=... name=...`, and
- the status toggles IDLE <-> ACTIVE.

Also try the emulator's own side-button controls (the buttons on the emulator
skin / extended controls) and note which, if any, produce events.

## Watch Logcat
Filter Logcat by the tag `Phase0Spike` to see every key event and toggle.

## Acceptance (Phase 0 passes if)
- At least one stem keycode reliably reaches onKeyDown and toggles the status.
- You have recorded which keycode(s) appeared.
- The tap fallback toggles independently.

If no stem key reaches onKeyDown, that is a documented negative result: Phase 1
uses the on-screen tap to stop, and we re-test the physical buttons on the OnePlus
in Phase 2.
