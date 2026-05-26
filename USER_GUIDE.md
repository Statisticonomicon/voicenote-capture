# VoiceNote Capture — User Guide

Everyday use of the watch + phone voice-note system. (Prototype; Phase 1.)
For building, installing, and testing, see `HOW_TO_RUN.md`.

---

## What this is for

Capturing a thought or a passage by voice, from your wrist, without pulling out
your phone — and having it arrive as text in your Obsidian vault. The point is
frictionless *capture*; the thinking and summarising are deliberately left to you
(that is where reading and ideas actually get internalised). The system transcribes;
you process.

---

## One-time setup

1. Install both apps:
   - the watch app (`:wear`) on your Wear OS watch,
   - the companion app (`:mobile`) on your phone.
2. On the watch, on first launch, grant **Microphone** and **Notifications**
   permissions.
3. On the phone, open **VoiceNote Companion** and set:
   - **Obsidian vault folder** — required; this is where notes are written. Pick a
     folder inside your vault (a dedicated subfolder such as `VoiceNotes` keeps
     things tidy).
   - **Raw-audio folder** (optional) — an independent backup copy of each recording.
   - **Processing endpoint base URL** — your transcription server, e.g.
     `http://<your-tailscale-host>:8457`. Leave **Mock mode ON** until you want to
     use the real server; turn it off once the endpoint is set.
4. (Phase 2, real watch) Assign a hardware button to launch the watch app, so a
   press starts/stops recording without using the touchscreen.

---

## Capturing a note

1. Press your assigned watch button — or tap the on-screen **Tap toggle** — once.
   A single buzz means recording started; the screen can stay off.
2. Speak.
3. Press again. A double buzz means stopped. The audio is sent to your phone.
4. The phone transcribes it (via your server) and writes a Markdown note into your
   vault folder, named `note-<timestamp>.md` (imports are `imported-<timestamp>.md`).

You don't need to look at the watch during any of this — the haptics confirm start
and stop.

---

## Reviewing and triaging

- Recordings are **never deleted automatically** on the watch. Capture is cheap;
  deletion is irreversible — so you triage later, on the phone or in Obsidian, not
  in the moment.
- Transcripts appear in your chosen vault folder. Process them the way you would any
  literature note: read, distil in your own words, link what matters, discard the
  rest.

---

## Mock mode vs real transcription

- **Mock mode ON** (default): no network used; the note contains placeholder text.
  Useful for confirming the capture-and-write flow works before involving the
  server.
- **Mock mode OFF**: the phone uploads to your endpoint, waits for transcription,
  and writes the real transcript. Requires the endpoint reachable (you on the
  tailnet, the server running).

---

## If something seems off

- **No note appeared.** Check the vault folder is set in settings, and check
  mock-mode / endpoint settings. If using the real server, confirm your phone can
  reach it (open `http://<host>:8457` in the phone browser).
- **Note contains placeholder/MOCK text when you wanted a real transcript.** Mock
  mode is still on, or the endpoint base URL isn't set.
- **Recording didn't start.** Re-check the Microphone permission on the watch.
- **Long recording seems stuck.** Large files take time; the phone polls the server
  repeatedly and resumes if interrupted. Give it time before assuming failure.

For logs and deeper diagnostics, see `HOW_TO_RUN.md`.
