# Voice Note Capture - User Guide (Phase 1 prototype)

## One-time setup
1. Install the watch app (`:wear`) and the phone app (`:mobile`).
2. On the watch, grant microphone and notification permissions on first launch.
3. On the phone, open Voice Note Companion:
   - Choose your Obsidian vault folder (required for notes to be written).
   - Optionally choose a raw-audio backup folder.
   - Leave Mock mode ON to try the flow with no server; turn it OFF and set your
     endpoint (home server over Tailscale, or the mock server) when ready.

## Capturing a note
- Press your assigned watch button (or the on-screen Tap toggle). A single buzz =
  recording started; the screen can stay off. Speak.
- Press again. A double buzz = stopped. The audio is sent to the phone.
- The phone processes it and writes a Markdown note into your vault folder.

## Reviewing
- Recordings are never deleted on the watch automatically; triage later.
- Notes land in your vault folder as `note-<timestamp>.md`.

## If something seems off
- No note appeared: check the vault folder is set, and Mock/endpoint settings.
- Recording didn't start: re-check microphone permission on the watch.
- See HOW_TO_RUN.md for log filters and the three test slices.
