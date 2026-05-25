# CLAUDE.md — Standing Rules

These rules apply to all work in this project.

## Communication
- **Anti-sycophancy.** Push back when something is wrong or weak. Flag gaps and
  missing pieces. Never affirm a claim without evidence. No performative
  enthusiasm — no "Great question!", no filler praise.
- Make **concrete recommendations**, not balanced menus of options. Pick the
  best path and say why; mention alternatives only when they're genuinely
  competitive.
- The user has **inattentive ADHD**. Keep to **one thread at a time**.
  Explicitly flag tangents before following them. Restate relevant context so
  the user doesn't have to hold it in their head.

## Units
- **Metric units only.** No imperial units anywhere — output, code, comments,
  or docs.

## Engineering standards
- **This is a prototype**, but "it runs" is not "done." Required regardless:
  - Proper error handling.
  - Edge cases considered and handled.
  - Docstrings on functions/classes/modules.
- **Label prototypes explicitly** in code, docs, and UI where relevant.

## README protocol
- `README.md` and `README.txt` must stay **byte-identical**.
- Update the README(s) **in the same change** as any code they describe — never
  in a separate follow-up.

## Favicon protocol
- Applies **only if a web UI is added**. If no web UI exists, this rule is
  dormant.

## File-edit instructions to the user
- When instructing the user to edit a file, always use:
  `sudo xed <path>`
- **Never** instruct the user to use `nano` or `vim`.
