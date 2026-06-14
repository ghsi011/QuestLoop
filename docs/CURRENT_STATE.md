# Current State

An honest map of the codebase against the product spec, as of this snapshot.
Legend: ✅ implemented (logic + UI + tests) · ◑ partial · ✗ not yet.

> Build caveat: this environment has no Android SDK and blocks Google's Maven,
> so the **`:app` module is verified only by CI**, while the pure-Kotlin
> `:core` logic compiles and is unit-tested everywhere.

## ✅ Fully implemented

- **Quest model & categories** (§4) — 8 categories, frequencies, difficulty,
  priority, origin, completion styles.
- **Reward economy** (§6, §8) — XP = difficulty × priority × consistency;
  same-day anti-farming decay; meta-maintenance daily cap; gentle capped
  penalties (XP never negative); bad-habit relapse rewarded for honesty.
  *(Most heavily tested area.)*
- **Levels & streaks** (§6) — quadratic XP curve; streaks with grace days.
- **Achievements** (§6) — data-driven unlock engine, surfaced on Today.
- **Daily quest generation** (§3, §4) — time/energy budgeting, deadline
  urgency, avoidance resurfacing, category-variety caps.
- **Completion tracking** (§8) — manual + non-binary (quantitative / duration /
  subjective); partial progress credited, never penalised.
- **Minimal daily loop** — morning micro-quest + evening wrap-up via day-part
  routines.
- **Recurrence scheduling** (§4) — `QuestScheduler` gates daily/weekly/monthly/
  one-off cadence so a weekly quest doesn't reappear until it's due again.
- **Settings** — Settings screen wires max-daily quests, default available
  minutes, and focus categories (previously had setters but no UI).
- **Habit, bad-habit & goal management** (§4, §7) — a screen to add/remove
  habits, habits-to-reduce, and goals (persisted as JSON); `HabitQuestFactory`
  turns them into recurring quests that feed the daily plan (goals → a weekly
  subjective check-in).
- **Delete all data** (§9) — Settings → "Delete all my data" wipes quests,
  history, XP, and settings on-device (with confirmation).
- **Weekly & monthly reviews** (§3) — aggregation + Review screen.
- **Real-world reward planning** (§6) — suggested allowance (% of a self-set
  affordable budget, difficulty-weighted, capped) with mandatory disclaimers.
- **Safety guard** (§9) — rest / overdrive / meta-heavy / recovery signals.
- **Privacy posture** (§9) — local-first (Room + DataStore), backups off by
  default, opt-in sensitive-notification flag.
- **Engineering process** (§11) — multi-module split, ~90 core + Compose UI
  tests, green CI (tests + APK assemble + lint), docs, downloadable APK release.

## ◑ Partial

- **AI quest generation** (§5) — real scaffolding (versioned prompts, output
  guardrails, dedup, clamping) but backed by a **deterministic
  `FallbackSuggester`, not a live LLM**. No model wired in; no network calls.
- **Weekly/monthly quest *lists*** (§4) — recurrence cadence is now enforced
  (`QuestScheduler`), but the app still renders one **daily** plan; there are no
  dedicated weekly/monthly planning screens.
- **Admin quests for reward funds** (§6) — one seed quest + static guidance on
  the Rewards screen, not a dynamic flow.
- **Energy/mood check-in** (§7) — energy check-in shapes the plan and is now
  **persisted per day** (survives restarts); a dedicated mood check-in isn't
  added yet.

## ✗ Not implemented (spec future / §10 deferred)

- Calendar / health-app / todo-app integrations.
- Location-based or passive completion; real timers / checklists / calendar
  confirmation (the `TIMER`/`CHECKLIST` verification values are just labels).
- Social / party / household / accountability modes.
- Seasonal events, narrative storylines, boss battles, template marketplace,
  deeper analytics.
- Titles, collections, unlockable themes/cosmetics (only XP/levels/streaks/
  achievements exist).
- Cloud sync; in-app data **export** (delete-all is implemented).

## Biggest gaps vs. spec
Live AI (scaffolded but deterministic), habit/goal management UI, true
weekly/monthly scheduling, and all external integrations.
