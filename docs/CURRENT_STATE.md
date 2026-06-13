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
- **Habit entry** (§10) — quests can be created, but there is **no UI for the
  `Habit` / `BadHabit` / `Goal` models** (they exist but are unused by the app).
  Bad-habit tracking only happens via reduction-category quests.
- **Daily/weekly/monthly quests** (§4) — frequencies are modelled and reviews
  are weekly/monthly, but only a **daily plan is generated**; recurrence cadence
  beyond same-day dedup is not enforced.
- **Admin quests for reward funds** (§6) — one seed quest + static guidance on
  the Rewards screen, not a dynamic flow.
- **Energy/mood check-in** (§7) — energy check-in exists and shapes the plan but
  is **transient (not persisted)**; no mood check-in.
- **User preferences** — `maxDailyQuests`, `availableMinutes`, `focusCategories`
  have store/repository setters but **no UI**; only the monthly budget cap is
  user-settable.

## ✗ Not implemented (spec future / §10 deferred)

- Calendar / health-app / todo-app integrations.
- Location-based or passive completion; real timers / checklists / calendar
  confirmation (the `TIMER`/`CHECKLIST` verification values are just labels).
- Social / party / household / accountability modes.
- Seasonal events, narrative storylines, boss battles, template marketplace,
  deeper analytics.
- Titles, collections, unlockable themes/cosmetics (only XP/levels/streaks/
  achievements exist).
- Cloud sync; in-app data export / delete-all.

## Biggest gaps vs. spec
Live AI (scaffolded but deterministic), habit/goal management UI, true
weekly/monthly scheduling, and all external integrations.
