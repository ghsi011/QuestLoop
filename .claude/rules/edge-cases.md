---
paths:
  - "app/src/main/**"
  - "core/src/main/**"
---

# Edge-case matrix — sweep before coding, verify at review

For any change touching quests/completions/UI surfaces, state per dimension:
**in-scope** (handled where?), **out-of-scope** (degrades how?), or **N/A**
(why). Reviewers check this list; "didn't consider it" is itself a finding.

- **Completion styles (×4)** — BINARY, QUANTITATIVE, DURATION, SUBJECTIVE.
  Anything that displays or completes a quest must handle all four; non-binary
  means partial progress, targets, steppers, and accumulation. (The widget once
  shipped BINARY-only — this class of gap lives here.)
- **Quest sources** — the `quests` table AND derived quests (habits /
  bad habits / goals via `HabitQuestFactory.deriveAll`). Derived quests are NOT
  in the DB; include them in every style/progress/dismissal map. Bad-habit
  quests invert success (success = NOT doing the thing).
- **Completion results (×5)** — COMPLETED, PARTIAL, SKIPPED, FAILED,
  RESCHEDULED. Partial is credited and never penalised; a skip penalty can dip
  ledger XP below zero (coerce ≥0, never `require`).
- **Recurrence & accumulation** — daily vs. interval quests (progress
  accumulates across the calendar interval, resets on its boundary); one-off
  measured quests; over-completion mode (loggable past target, small capped
  bonus).
- **Time** — epoch-day boundaries and midnight rollover (the widget must not
  show yesterday's plan); timezone/clock changes (fixed-instant alarms re-arm);
  a late "Mark done" credits the stamped day, not today.
- **Lifecycle** — code reachable without the app open (widget, reminders,
  boot) cannot assume warm state; process death; concurrent completes
  (repository `Mutex`); double-tap (in-flight flag + disabled button).
- **AI (×2 providers)** — OPENROUTER and OPENAI paths both work; provider
  errors surface (never silently substitute the deterministic fallback as AI
  output); dedup against the user's existing quests.
- **Error/empty states** — every busy/loading flag reset in `try/finally`;
  failures show an error state, never a stuck or blank screen; empty lists,
  zero XP, no quests due.
