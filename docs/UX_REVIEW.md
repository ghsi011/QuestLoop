# UX Review — QuestLoop

A heuristics-based UX review of the current Android UI, focused on the product's
core promise: a motivating, *low-effort, non-shaming* daily loop. Findings are
ranked by impact with concrete locations and recommendations. This is advisory;
nothing here is implemented yet.

## TL;DR
The tone, safety framing, and information design are genuinely strong (supportive
copy, disclaimers, gentle penalties). The biggest UX risks are **structural, not
cosmetic**: there's nothing to bring the user back for the morning/evening loop
(no reminders), no **undo** for a mis-tapped completion, and the AI/quick-add and
sub-screen navigation have rough edges. Fixing the High items would do more for
retention than any amount of visual polish.

---

## High impact

### H1. Nothing prompts the daily loop (no reminders/notifications)
The product is built around a ~2-minute morning check-in and evening wrap-up
(`RoutineQuestFactory`), but the app never reaches out — the user must remember
to open it. This is the single biggest gap between the vision and the experience.
**Recommend:** opt-in local notifications at user-chosen morning/evening times
(privacy-safe, non-sensitive content per SPEC §9). Highest-leverage next UX work.

### H2. No undo for completing or skipping a quest
`TodayScreen` completion is a single tap that immediately changes XP and removes
the quest. A mis-tap — especially **Skip** (gentle penalty) or marking something
done you didn't — has no recovery path. For a system that's meant to feel *fair*,
this is important.
**Recommend:** add an **Undo** action to the result snackbar (the engine is
already idempotent, so reversing a record is clean).

### H3. AI quick-add gives no feedback about what happened
`AddQuestViewModel.quickAddFromText` → `repository.suggestQuests` silently routes
to AI or the deterministic fallback and then `popBackStack()`. The user can't
tell whether AI was used, whether their key/model worked, or that it fell back on
error. A wrong key just yields generic quests with no explanation.
**Recommend:** surface a short result ("Added 4 AI suggestions" vs "AI
unavailable — added safe defaults"), and show errors (invalid key, rate limit)
rather than swallowing them.

---

## Medium impact

### M1. Sub-screens lack back navigation and context
`QuestLoopApp` keeps a static `TopAppBar(title = "QuestLoop")` and the pushed
routes (Add, Habits) have **no back arrow or contextual title** — users rely on
the system back gesture with no visual cue they're in a sub-screen.
**Recommend:** per-destination titles + a back arrow on pushed routes.

### M2. The FAB appears everywhere, even where it doesn't belong
The "Add quest" FAB is in the root `Scaffold`, so it shows on the Add screen
itself (navigates to Add again), and on Settings/Habits where it's out of context.
**Recommend:** scope the FAB to Today (and maybe Reviews).

### M3. Numeric inputs use the text keyboard
Budget (`RewardsScreen`), estimated/target minutes & target count
(`AddQuestScreen`), and daily limit (`HabitsScreen`) are number entries with no
`KeyboardOptions(keyboardType = Number/Decimal)`, so users get the alphabetic
keyboard.
**Recommend:** set numeric keyboards; it's a small change with outsized friction
reduction.

### M4. The Add screen optimises for the rare path
`AddQuestScreen` leads with a long manual form (title + 5 chip groups + minutes)
and puts the faster "Suggest quests ✨" capture at the bottom. For a
minimal-effort product, the quick path should be primary.
**Recommend:** lead with quick capture; make the detailed form secondary/
expandable ("Add details").

### M5. No first-run onboarding for a sensitive concept
First launch drops the user onto seeded sample quests with no introduction to the
quest/XP model, the **money/rewards disclaimer**, or the local-first privacy
stance. The reward-economy framing in particular deserves up-front context.
**Recommend:** a short (2–3 pane) intro or a dismissible Today banner covering
"how it works", the money disclaimer, and "your data stays on device".

### M6. Completing a quest reshuffles the list
Each completion triggers a full plan refresh, so the finished item disappears and
the remaining quests can re-sort/scroll-jump with no transition.
**Recommend:** optimistic removal + item animation for a calmer feel.

---

## Low impact / polish

- **L1. Uneven empty states.** Today has a warm "All clear"; `HabitsScreen` has
  no empty-state copy; `ReviewScreen` shows an all-zeros card to brand-new users.
- **L2. No save confirmation** for budget or AI settings (`RewardsScreen`,
  `SettingsScreen`) — a brief toast would reassure.
- **L3. No loading indicators** on Reviews/Rewards/Settings (`loading` exists in
  state but isn't shown). Fine for local data, but inconsistent with Today.
- **L4. Currency/locale.** Budget and allowance render as bare `%.2f` numbers
  with no currency symbol.
- **L5. Energy check-in** chips can't be deselected, and a true "rest day" isn't
  distinct from "Low".
- **L6. Accessibility niceties.** Glyph buttons ("+", "−", rating "1–5") read as
  raw symbols to TalkBack; add `contentDescription`s like "add one". Nav/delete/
  FAB icons already have good descriptions.
- **L7. Achievements have no home.** They only appear inline on Today; a dedicated
  collection screen (SPEC §6 collections/titles) would aid discovery.

---

## What's working well (keep)
- **Supportive, non-shaming copy** throughout ("Log honestly", "recovery beats
  perfection", gentle penalty wording) — exactly the spec's intent.
- **Reward disclaimers** are prominent and repeated where money is mentioned.
- **Clear primary screen.** Today leads with level/XP, then safety signals, then
  quests — a sensible hierarchy.
- **Non-binary completion controls** (counter / minutes / 1–5 rating) match the
  task types and keep logging light.
- **Privacy affordances** (delete-all with confirmation, export) are easy to find
  in Settings.

## Suggested order
1. H2 Undo + H3 AI feedback (cheap, high trust impact).
2. M3 numeric keyboards + M1 back-nav + M2 FAB scoping (small, broad wins).
3. H1 reminders (larger; the core-loop unlock).
4. M5 onboarding, M4 Add-screen reorder, then the Low items.
