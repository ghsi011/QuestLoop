# Design Direction — streamline & next-gen UI

A review of the current UI with a plan to **reduce text/visual noise** and a set
of **next-gen UI** concepts. Pairs with `UX_REVIEW.md` (which covers
behaviour/flow); this one is about density, glanceability, and ambition.

## Review: where we are

Feature-complete for the MVP and then some (today loop, AI, habits/goals,
recurrence, rewards, reminders, onboarding, export/delete). The **weak point is
density**: nearly every surface is a stack of `Card`s with a title + a full
sentence or paragraph. The app *explains* a lot instead of *showing*.

Worst offenders:
- **Today** stacks: level card → one full card *per* safety signal → energy card
  with a question → "Today's quests" header → quest cards (title + category chip +
  "difficulty · N min" + a rationale sentence + two buttons) → "Notes" bullets →
  "Achievements (n)" cards → "Deferred (n)" list. A lot to scan for "what do I do
  now?".
- **Rewards / Settings / Onboarding / Habits** repeat explanatory `bodySmall`
  under most controls and restate the money/privacy disclaimers as paragraphs.
- Everything is the same visual weight (text on cards), so nothing pops.

## Streamlining principles
1. **Show, don't tell.** Replace sentences with icons, color, rings, counts.
2. **Progressive disclosure.** Hide rationale/help behind a tap or an "ⓘ"; say
   each thing once (onboarding/help), not on every screen.
3. **One clear next action per screen.** Today should answer "what now?" instantly.
4. **Consistent visual language.** Category color/icon, difficulty as pips, XP as
   a ring — reused everywhere.

## Streamlining plan (concrete, per screen)

**Today**
- Quest row: leading **category color dot/icon**; **difficulty as pips** (●●○○○)
  + a small clock + minutes instead of words; **rationale hidden** until the row
  is tapped (expand). Primary action = a single check affordance; "skip/defer"
  behind an overflow or swipe.
- **Collapse safety signals** into one slim, dismissible banner (icon + one line;
  "+2 more" if several) rather than a card each.
- Energy: a compact **emoji segmented control** (🔋 low/ok/high) with no question
  text.
- Drop the "Notes"/"Deferred" sections from the main flow (move under a "More"
  expander); they're rarely the next action.
- Achievements: a single horizontal **badge strip**, not stacked cards.

**Rewards** — lead with the allowance as a **number/ring**; one-line disclaimer +
"Why?" link to the full text; collapse "setting up your fund" into a checklist.

**Settings** — remove per-row helper sentences; keep labels; one "About & privacy"
entry holds the longer copy.

**Onboarding** — 3 swipeable panes, each **one icon + one line** (not 4 text
cards). Keep the money disclaimer as its own pane.

Target: roughly halve on-screen words on Today, Rewards, and Settings.

## Next-gen UI ideas (ambitious)

1. **Swipe-stack Today.** The day as a deck of quest cards: swipe right = done
   (confetti + haptic), left = skip, up = defer. Near-zero text, gesture-first,
   one quest in focus at a time — perfect for the "2-minute loop".
2. **"Your next quest" focal mode.** Instead of a list, surface a single
   recommended quest big and bold ("Next: 15-min walk"), reducing decision load;
   a small "see all" reveals the rest.
3. **Home-screen & lock-screen widget (Glance) + notification actions.** Complete
   today's top 1–3 quests **without opening the app** — the true minimal-
   interaction unlock. One-tap "Done" in the reminder notification too.
4. **Progress as a visual journey.** Replace the XP bar with a **level ring**, and
   render long-term progression as a **path/constellation** of completed quests &
   achievement nodes — a screen worth revisiting.
5. **Celebration & motion.** Completion ripple + haptic; level-up takeover with a
   short animation; streak shown as a **flame chip** in the top bar.
6. **Context-aware home.** Morning = just the check-in card; midday = the plan;
   evening = the wrap-up. The screen has one job at a time (matches the routines).
7. **Conversational capture.** A single "What do you need to do?" box (text or
   voice) as the primary add path; AI structures it (we already have the AI). The
   detailed form becomes "advanced".
8. **Calm, adaptive theming.** Material 3 *expressive* surfaces; a softer palette
   on low-energy days; dark-first.

## Roadmap status
- ✅ **Phase 1 — Streamline:** quest-row pips/dots + collapsible rationale, safety
  banner, compact energy control, achievement strip; trimmed Rewards/Settings/
  Onboarding copy.
- ✅ **Phase 2 — Glanceable progression:** XP ring + streak flame, completion
  haptic, dedicated Achievements screen.
- ✅ **Phase 3 — Minimal-interaction reach:** Glance home-screen widget;
  reminder "Mark done" notification action + boot-persistence.
- ◑ **Phase 4 — Bolder bets:** Focus mode (next-quest collapse) shipped;
  swipe-stack deck, progression journey view, and conversational capture remain
  as future exploration (larger, gesture/animation-heavy).

## Sequenced next steps

- **Phase 1 — Streamline (low risk, do now):** quest-row redesign (pips +
  category color + collapsible rationale), safety-signal banner, compact energy
  control, trim Rewards/Settings/Onboarding copy. Pure visual; no new deps.
- **Phase 2 — Glanceable progression:** XP **ring**, streak **flame** in the top
  bar, completion **haptic/animation**, dedicated **Achievements** screen.
- **Phase 3 — Minimal-interaction reach:** **Glance widget** + **notification
  "Done" action** (complete without opening the app); reminder **boot-persistence**.
- **Phase 4 — Bolder bets:** swipe-stack / focal "next quest" mode; progression
  journey view; conversational capture.

Recommended start: **Phase 1** (it directly answers "reduce text"), then a quick
**Phase 2 ring + flame** for visual payoff, then the **widget** (Phase 3) as the
highest-leverage next-gen feature.
