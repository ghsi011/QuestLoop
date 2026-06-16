# Content & Voice Guide

How QuestLoop talks. Every user-facing string should pass this guide. The goal:
copy that feels considered and human — never developer-facing, never "AI slop".

## Voice
- **Warm, calm, plain.** Talk like a supportive friend, not a coach or a robot.
- **Confident and finished.** State what is, not what isn't or what's "not done
  yet". Never expose implementation status, limitations, or roadmap to users.
- **Brief.** One line where possible. Trust the UI; don't over-explain.
- **Non-shaming.** Rough days are fine; recovery beats perfection.

## Hard rules (these are the "AI-slop" tells — never ship them)
1. **No developer/implementation language.** Banned: "(not after a reboot yet)",
   "safe defaults", "fallback", "config", "MVP", "for now", "TODO", "synced",
   parenthetical caveats about how the feature works internally.
2. **No meta references to the app's own design.** Banned: "part of the system",
   "the app will…", "this feature".
3. **No hedging or apologies** unless the user hit a real error. Avoid "simply",
   "just", "please note".
4. **No restating the obvious** under controls (a labelled slider needs no
   sentence explaining it).
5. **Sentence case** for everything (titles, buttons). No Title Case, no ALL CAPS.
6. **One emoji max** per line, and only when it adds meaning (🔥 streak, ✨ AI).
7. **Numbers and specifics** over vague praise ("3-day streak", not "great job").

## Patterns
- **Buttons:** a verb. "Save", "Add quest", "Get started", "Delete".
- **Empty states:** reassure + one action. "All clear. Add a quest, or rest."
- **Errors:** what happened + what to do. "AI didn't respond. Check your key in
  Settings."
- **Disclaimers (money):** keep the one compliance line ("Not financial advice")
  short and factual; details live behind a tap, not on every screen.
- **Reminders/notifications:** a friendly nudge, not instructions.

## Examples (before → after)
- "Gentle local reminders to check in. They re-arm when you open the app (not
  after a reboot yet)." → "A friendly nudge each morning and evening."
- "Uses your AI provider if configured in Settings, otherwise safe defaults." →
  "AI turns your list into quests when it's on in Settings."
- "Added 4 AI suggestions ✨ — review & edit anytime." → "Added 4 quests ✨"
- "Rescheduled — no penalty. Adjusting plans is part of the system." →
  "Rescheduled — no penalty."
- "QuestLoop never holds, moves, or invests your money. It only helps you track a
  self-imposed reward budget you manage entirely outside the app." → "You manage
  your own money. QuestLoop just tracks what you've earned. Not financial advice."
