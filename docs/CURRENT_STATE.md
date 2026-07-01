# Current State

An honest map of the codebase against the product spec, as of this snapshot.
Legend: ✅ implemented (logic + UI + tests) · ◑ partial · ✗ not yet.

> Build note: `:app` builds and is unit-tested locally once
> [`scripts/setup-android.sh`](../scripts/setup-android.sh) has installed the
> Android SDK + JDK 17; only the emulator suite is CI-only (no `/dev/kvm` in the
> sandbox). The pure-Kotlin `:core` logic compiles and is unit-tested everywhere.

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
  subjective); partial progress credited, never penalised. Measured recurring
  quests **accumulate across their calendar interval** (e.g. "swim 2×/week"
  counts both swims toward one 2/2 and resets weekly), with an opt-in
  **over-completion** mode that keeps a quest loggable past its target for a
  small capped bonus.
- **Completed-quest history** — a filterable (Today / week / month / all-time)
  screen off the Reviews tab with **undo**, **edit** (a full quest editor that
  re-scores the completion's XP), and **re-add** (clone as a new quest).
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
- **Daily reminders** (§3; UX H1) — opt-in morning & evening local notifications
  at user-set hours (AlarmManager, inexact), with a **"Mark done"** action that
  completes the routine from the notification, and **boot-persistence** (re-armed
  on reboot + on app open). *Delivery is device-only — not CI-verifiable — so it's
  covered by a pure schedule-math unit test and needs on-device testing.*
- **Home-screen widget** (Glance) — shows today's top quests at a glance and
  opens the app on tap. *Device-only; not CI-verifiable beyond compile.*
- **First-run onboarding** — a one-time intro covering the quest/XP model, the
  money/rewards disclaimer, and the local-first privacy stance before anything
  sensitive is touched.
- **Delete all data** (§9) — Settings → "Delete all my data" wipes quests,
  history, XP, and settings on-device (with confirmation).
- **Export data** (§9) — Settings → "Export my data" serialises everything to
  JSON and shares it via the system share sheet.
- **Weekly & monthly reviews** (§3) — aggregation + Review screen.
- **Real-world reward planning** (§6) — suggested allowance (% of a self-set
  affordable budget, difficulty-weighted, capped) with mandatory disclaimers.
- **Safety guard** (§9) — rest / overdrive / meta-heavy / recovery signals.
- **Privacy posture** (§9) — local-first (Room + DataStore), backups off by
  default, opt-in sensitive-notification flag.
- **Engineering process** (§11) — multi-module split, ~90 core + Compose UI
  tests, green CI (tests + APK assemble + lint), docs, downloadable APK release.

## ◑ Partial

- **AI quest generation** (§5) — **live LLM via OpenRouter** (user provides a
  key + model in Settings) behind the `AiQuestService`: versioned prompts,
  JSON parsing tolerant of chatty/markdown output, the `AiQuestValidator`
  guardrails, and a deterministic `FallbackSuggester` whenever AI is off,
  unavailable, or returns unusable output. The "Suggest quests ✨" action on the
  Add screen uses it. *(The live network call can't be exercised in the CI
  sandbox — OpenRouter is firewalled there — so it's covered by fake-client unit
  tests and verified on-device.)*
- **Weekly/monthly quest *lists*** (§4) — recurrence cadence is enforced
  (`QuestScheduler`) and the Reviews tab now has a **Plan** view (toggle next to
  the retrospective Review) that lays the candidate pool out across the current
  week/month: `PeriodPlanner` computes each quest's expected occurrences, flags
  overdue / due-this-period deadlines, and groups by category with time
  subtotals. *Forward-looking calendar weeks/months only; per-day scheduling
  within the period and drag-to-reschedule aren't built.*
- **Admin quests for reward funds** (§6) — `AdminFundFactory` derives admin
  quests from the fund's actual state (open a pot → fund monthly → claim),
  surfaced as a live status card on the Rewards screen and in Today/Plan; no
  longer a static one-off seed.
- **Energy/mood check-in** (§7) — energy check-in shapes the plan and is now
  **persisted per day** (survives restarts); a dedicated mood check-in isn't
  added yet.
- **Calendar integration** (§10) — opt-in, local-first: reads calendars already
  synced on the device via `CalendarContract` (no Google API, no OAuth, no
  network) to auto-budget today's plan (`FreeBusyCalculator`, a Settings
  switch) and to let "Add quest" pick a deadline straight from an upcoming
  event. *Read-only; no write-back, no health/todo-app integrations, and
  `VerificationMethod.CALENDAR`/`TIMER`/`CHECKLIST` stay labels — deliberately
  not auto-completing a quest just because a linked event ended, to keep the
  XP ledger honest.*

## ✗ Not implemented (spec future / §10 deferred)

- Health-app / todo-app integrations; calendar write-back (blocking focus time).
- Location-based or passive completion; real timers/checklists/calendar-based
  auto-completion (the `TIMER`/`CHECKLIST`/`CALENDAR` verification values are
  just labels by design — see above).
- Social / party / household / accountability modes.
- Seasonal events, narrative storylines, boss battles, template marketplace,
  deeper analytics.
- Titles, collections, unlockable themes/cosmetics (only XP/levels/streaks/
  achievements exist).
- Cloud sync (data export and delete-all are implemented).

## Biggest gaps vs. spec
Per-day scheduling/rescheduling within a week/month (the Plan view is a
forward overview, not a calendar you can drag quests around in), a mood
check-in, and the remaining external integrations (health/todo apps, calendar
write-back).
