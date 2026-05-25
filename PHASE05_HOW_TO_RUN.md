# Phase 0.5 - How to run and what to check

## The question this answers
Phase 0 proved hardware buttons do NOT reach the app as key events. This spike
tests the workaround: can we drive the app through the LAUNCH path, so that a
button assigned to the app toggles record/stop by being pressed twice?

The mechanism: launchMode=singleTask. First launch -> onCreate -> START.
Re-launch -> onNewIntent -> STOP. A hardware launch button would trigger exactly
these launches; we simulate the button with `am start` so we can test the
software mechanism on the emulator without needing real button assignment.

## Run
1. Open the project in Android Studio (Panda 4), let Gradle sync, accept any AGP
   upgrade prompt.
2. Start a Wear OS emulator (API 34) and Run the `app` config.
3. On first launch the status shows RECORDING (onCreate fired START) and the log
   shows "onCreate -> START".

## The actual test (simulating the hardware launch button)
With the app already running, from a terminal:

    adb shell am start -n com.notaricus.phase05/.MainActivity

Watch the device and the log. PASS if:
- the log shows "onNewIntent received -> treating as toggle", and
- the status flips RECORDING <-> STOPPED on each `am start`.

Run the command several times; it should toggle every time. Filter Logcat by
the tag `Phase05Toggle` to confirm.

## Also worth trying (optional, closer to the real button)
If the emulator's Settings exposes hardware-button assignment
(Settings > Personalization / System > hardware buttons), assign a button to
"Phase 0.5 Launch Toggle" and press it twice. If it toggles, that is the real
end-to-end confirmation. If the emulator does not offer assignment, the `am
start` test above is sufficient for the software mechanism; button assignment
itself is re-checked on the OnePlus in Phase 2.

## Interpreting the outcome
- PASS (onNewIntent fires, status toggles): the eyes-free single-button toggle
  is viable via the launch path. Phase 1 is built on this, with on-screen tap as
  the guaranteed fallback.
- FAIL (a second launch creates a fresh start instead of toggling): we drop to
  "launch = start, on-screen tap = stop" for Phase 1. Still works, just less
  eyes-free for stop.

The on-screen "Tap toggle" button works regardless and is the fallback either
way.
