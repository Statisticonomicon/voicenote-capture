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
   - **Transcription provider** — pick one:
     - *Self-hosted (Whisper server)* — set the **Processing endpoint base URL** to
       your server, e.g. `http://<your-tailscale-host>:8457`. Auth token is optional.
       No per-use cost; audio stays inside your infrastructure.
     - *OpenAI Whisper (your API key)* — paste your **OpenAI API key** (`sk-…`).
       OpenAI bills your account per minute of audio. Quickest setup, no server to
       maintain, but audio is sent to OpenAI.
   - **Mock mode** is off by default. Tick it temporarily if you want to verify
     the watch → phone → vault chain end-to-end without involving a provider —
     handy for first-run sanity checks. Don't forget to un-tick before real use.
4. On the watch, long-press the watch face → Customize → pick a complication slot
   → choose **VoiceNote**. This is your one-tap shortcut to start a recording from
   any watch face. (On the original Pixel Watch the single crown isn't
   user-mappable to a third-party app, so the complication is the canonical
   launch path. The Pixel Watch 4's second side button will eventually allow a
   direct hardware-button launch as an additional option.)

---

## Capturing a note

1. **Tap the VoiceNote complication** on your watch face. A single firm thump
   confirms recording started; the screen can stay off after that.
2. **Speak.**
3. **Press the crown** to go back to the watch face. A double thump confirms the
   recording stopped. The audio is sent to your phone.
4. The phone transcribes it (via your server) and writes a Markdown note into
   your vault folder, named `note-<timestamp>.md` (imports are
   `imported-<timestamp>.md`).

You don't need to look at the watch during any of this — the haptics confirm
start and stop. Every complication tap is a fresh recording (it doesn't toggle),
so the flow is always: tap → speak → crown.

The on-watch screen is intentionally minimal: a red blinking dot + "RECORDING"
label at the top, a large monospaced m:ss timer in the middle, a live red
waveform reacting to your voice underneath, and a "Press crown to stop" hint
at the bottom. There is no on-screen stop button — the crown is the only stop
control (which is what you want one-handed). If the screen times out
mid-recording, the recording keeps running; pressing the crown again wakes the
screen without stopping it. A second crown press, with the screen already on
and the recording screen visible, is what actually stops the session.

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

- **Mock mode OFF** (default): the phone sends the audio to whichever provider
  you picked — your self-hosted Whisper server, or OpenAI — and writes the real
  transcript into the vault. Requires that the provider is reachable (you on the
  tailnet with your server up, or a valid OpenAI key with quota).
- **Mock mode ON** (opt-in, was default during Phase 1 development): no network
  used; the note contains placeholder text marked "MOCK". Useful for confirming
  the watch → phone → vault chain works before you configure a provider, or for
  short demos with no transcription cost. Turn it off again before real use.

---

## If something seems off

- **No note appeared.** Check the vault folder is set in settings, and check
  mock-mode / endpoint settings. If using the real server, confirm your phone can
  reach it (open `http://<host>:8457` in the phone browser).
- **Note contains placeholder/MOCK text when you wanted a real transcript.** Mock
  mode is still on, or the endpoint base URL isn't set.
- **Recording didn't start.** Re-check the Microphone permission on the watch.
- **No haptic on start/stop.** Check the watch's vibration setting and Do Not
  Disturb state. The app uses sonification audio attributes that survive most
  DND modes, but a "Total silence" / "Bedtime mode" can still suppress them.
- **Crown press didn't stop the recording.** The crown press triggers
  `onUserLeaveHint`. If you swiped away with a gesture instead of using the
  crown, recording continues by design (treated as a transient nav).
- **Long recording seems stuck.** Large files take time; the phone polls the server
  repeatedly and resumes if interrupted. Give it time before assuming failure.

For logs and deeper diagnostics, see `HOW_TO_RUN.md`.
