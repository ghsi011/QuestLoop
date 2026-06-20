# QuestLoop — Comprehensive Code Review (June 2026)

A full-stack, multi-layer review of QuestLoop, from low-level bug hunting through
architecture to user flow & accessibility. Findings were produced by independent
domain reviewers and then **adversarially re-verified by skeptics** whose explicit
job was to *disprove* each claim against the actual code, tests, and Android API
semantics. Severities below are the **post-verification** ratings.

- **Date:** 2026-06-20
- **Commit reviewed:** `claude/comprehensive-code-review-3a5zze` (tip)
- **Scope:** `:core` (pure Kotlin) + `:app` (Android) main sources, tests, manifest, build config, docs.
- **Method:** 8 parallel domain reviews → 4 adversarial skeptic verifications → consolidation.

> This is a *new, independent* review. It deliberately did **not** trust the prior
> `docs/CODE_REVIEW.md` "all fixed" claims; every finding here was checked in the
> live code. (Result: the older H/M items genuinely are fixed; these are different,
> deeper issues.)

---

## Executive summary

QuestLoop is an unusually disciplined MVP. The headline architectural bet — a pure
Kotlin/JVM `:core` holding the entire reward economy, generation, safety and AI
guardrails, with `:app` owning all Android + I/O — is **real, enforced, and paying
off** (zero `android.*`/`androidx.*` in `:core`; the economy is fully unit-testable
on a plain JDK). Security and privacy are genuinely well-built: the API key lives
only in `EncryptedSharedPreferences`, never logged/exported/diagnosed; all
`PendingIntent`s are immutable; exported receivers validate their action; the money
feature has no dark patterns.

The review found **no Critical issues and no exploit of the XP/money path** — the
ledger math, caps, anti-farm and completion idempotency are sound and well-tested.

The validated problems cluster in four places:

1. **AI safety** — the single guardrail chokepoint omits the most dangerous category
   (self-harm / eating-disorder / violence) and the blocklist it does have is
   trivially bypassable *and* fires false positives on benign quests. **(Top fix.)**
2. **Accessibility** — the core reward feedback loop (complete → XP → level-up →
   ring) is silent to TalkBack; no `liveRegion`/`stateDescription` anywhere.
3. **A stale widget** — nothing ever refreshes the home-screen widget after a
   completion.
4. **App-layer tech debt** — a god-class repository, a half-reactive state model,
   and some domain orchestration stranded in the Android layer. Real, but not "on
   fire."

### Validated counts

| Severity | Count | Items |
|----------|-------|-------|
| **Critical** | 0 | — |
| **High** | 3 | AI-1 (no self-harm guardrail), REM-1 (widget never refreshes), A11Y-1 (no TalkBack state announcements) |
| **Medium** | 18 | see table |
| **Low** | 22 | see per-area reports |
| **Info** | 7 | indices, alpha dep, latent footguns, etc. |

### What the adversarial pass changed (validity gate)

The skeptics **refuted nothing outright** — every finding points at real code — but
they corrected severity inflation and caught factual errors. That's the signal the
remaining severities are trustworthy.

| Adjustment | Finding | Why |
|-----------|---------|-----|
| **Upgrade** L→M | DATA-6 mid-month allowance | Caller confirmed to pass month-to-date → ~6× inflated per-day budget (live, user-visible). |
| Downgrade H→M | ARCH-1 god class, ARCH-2 hybrid state | Real debt, but not causing bugs / blocking change today. |
| Downgrade H→M | REM-2 Doze alarm | `setAndAllowWhileIdle` is *batched ~9 min*, not suppressed; fine-ish for a twice-daily nudge. |
| Downgrade H→M | A11Y-2 glyph buttons | Airtight facts, but scoped to the measured-logging controls. |
| Downgrade H→L | UX-tone | Two slightly salesy strings = copy nit, not user harm. |
| Downgrade H→M | DATA-1 list race | Mechanism real, but the original "concrete trigger" was **factually wrong** (AddQuestVM writes Room, not profile lists); impact is a recoverable dropped manual entry. |
| Downgrade M→L | DATA-3 achievement toast | `newlyUnlocked` is a monotone set-diff — can only *suppress* a toast, never corrupt state. |
| Downgrade M→Info | DATA-4 IO dispatcher | Non-defect: Room dispatches its own I/O off-main. |
| Downgrade M→L | REM-3 day fallback | Producer always stamps `EXTRA_DAY`; buggy default unreachable on the happy path. |
| **Correction** | ECON-1 NaN cap | NaN crash is real; the "+Infinity also crashes" sub-claim is **wrong** (only NaN throws `roundToInt`). |
| **Correction** | ECON-2 level loop | Not a true infinite loop — a ~1.7B-iteration stall then an overflow exception. Unreachable in normal play. |

---

## High severity (validated)

### AI-1 — No self-harm / eating-disorder / violence guardrail
**`core/.../ai/AiQuestValidator.kt:35-44`** · Confidence: High · *Reproduced.*

`AiQuestValidator` is the single chokepoint every AI-generated quest passes through
(SPEC §11), but `bannedPatterns` only covers shame, thin financial, and thin medical
terms. Harmful titles pass `validate` untouched and return with `fromAi = true`:

- "Starve yourself until you hit your goal weight" → **passes**
- "Punish yourself with 200 burpees for relapsing" → **passes**
- "Skip every meal today" → **passes**

No test covers self-harm/ED/violence. This is the most dangerous gap in the product:
the entire AI-safety story rests on a blocklist that omits the highest-harm category.

**Fix:** Add an explicit, high-recall self-harm/ED/violence category. Treat AI-1/AI-2/AI-3
as one remediation — a literal-phrase blocklist is the wrong *shape* for a
product-critical safety control. Prefer a layered approach (a categorized, maintained
term set with normalization, plus a model-side system-prompt refusal instruction, plus
a conservative "when in doubt, route to the deterministic fallback" default). Add a
red-team test corpus.

### REM-1 — The home-screen widget never refreshes on data change
**`app/.../widget/QuestWidget.kt`** (+ absence of any caller) · Confidence: High · *Repo-wide grep confirmed.*

A grep of the whole app module for `QuestWidget|updateAll|GlanceAppWidgetManager|\.update\(`
returns only the widget's own definition, its receiver, and the manifest registration.
`quest_widget_info.xml` sets `android:updatePeriodMillis="0"`. There is **no**
WorkManager job, repository observer, or `update(...)`/`updateAll(...)` call site. The
widget only redraws on Glance lifecycle events (placement, reboot, resize), so
completing a quest in-app leaves the widget showing stale/empty "today" indefinitely —
defeating the widget's purpose.

**Fix:** Drive `QuestWidget().updateAll(context)` from the repository write paths
(completion, add, dismiss), e.g. after a successful ledger write.

### A11Y-1 — Core reward feedback is silent to TalkBack
**all of `app/.../ui/**`** · Confidence: High · *Full grep confirmed: zero `liveRegion`/`stateDescription`.*

Completion, XP gain, and level-up are announced only via a transient `Snackbar`; the
`LevelRing` fill change is silent. There is no `Modifier.semantics { liveRegion = ... }`
or `stateDescription` anywhere in the UI. For a gamified product explicitly positioned
around gentle, inclusive feedback, the central reward loop (complete → XP → level-up →
ring) is inaccessible to screen-reader users.

**Fix:** Add a `liveRegion` (polite) announcement for XP/level-up outcomes; give the
`LevelRing` merged semantics with a `stateDescription` ("Level 4, 60% to next"). See
also A11Y-2 / UX-LevelRing below.

---

## Medium severity (validated)

| ID | Area | Location | Issue |
|----|------|----------|-------|
| AI-2 | AI safety | `AiQuestValidator.kt:35-44` | Blocklist trivially bypassed — plurals (`\bloan\b` ≠ "loans"), leetspeak (`l4zy`), fullwidth unicode (`ｌａｚｙ`), paraphrase ("you should feel ashamed"). *Reproduced.* |
| AI-3 | AI safety | `AiQuestValidator.kt:35-44` | False positives block benign quests: "invest in your education", "diagnose the bug", "leverage my network", "research student loan repayment". *Reproduced.* |
| AI-4 | AI parse | `AiQuestService.kt:130-138` | JSON slice = first `[` … last `]`; trailing `[1]` aside or inner `[see below]` corrupts the slice → valid AI output silently dropped + misleading "unexpected response" fallback. *Reproduced.* |
| AI-5 | Generation | `QuestGenerator.kt:89` | Time-budget check exempts the first quest (`&& selected.isNotEmpty()`); one 240-min quest seats under a user-set 15-min budget — contradicts low-energy support. |
| ECON-1 | Reward | `RewardAllowanceCalculator.kt:46-95` | A NaN `monthlyBudgetCap` slips past the `cap <= 0` guard (NaN comparisons are false) and crashes `roundToInt(NaN)`. Fix: `isFinite() && it > 0.0`. (Note: +Inf does *not* crash — clamps.) |
| DATA-1 | Concurrency | `QuestRepository.kt:407-432` / `ProfileStore.kt:138-150` | Habit/goal/bad-habit list mutations do `profile.first()` (read) then `setHabits` → `dataStore.edit{}` (write) as two suspend ops; not atomic and not under `completionMutex`. Concurrent mutations can lose an entry (+ its derived quest). Use `dataStore.updateData{}` (merge inside the lambda) or a dedicated mutex. Recoverable; ledger/XP unaffected. |
| DATA-2 | Idempotency | `QuestRepository.kt:332` | SUBJECTIVE `completeMeasured` skips the `maxOf` monotonicity guard QUANTITATIVE/DURATION use; a lower re-rating lowers the fraction → nets XP *down*. Decide intent + test. |
| DATA-6 | Allowance | `QuestRepository.kt:379-393` + `RewardsViewModel.kt:41` | **(Upgraded L→M.)** `daysInMonth` = query-span length, but caller passes `startOfMonth(today)..today`; on the 5th, per-day budget = cap/5 ≈ 6× too high. User-visible financial pacing bug. |
| UI-1 | UI | `AddQuestViewModel.kt:38-68` + `AddQuestScreen.kt:120-139` | Manual "Add quest" has no in-flight guard and the button is gated only on `title.isNotBlank()`; double-tap mints fresh UUIDs → real duplicate rows. (Contrast: `acceptSuggestion`/`acceptAll` *are* guarded.) |
| REM-2 | Reminders | `ReminderScheduler.kt:39` | **(Downgraded H→M.)** `setAndAllowWhileIdle` is Doze-batched (~9-min windows, OEM-worse) → nudges fire late. Fine-ish for twice-daily, but `setAlarmClock()` (Doze-exempt, no permission) is the right fix; KDoc overstates reliability. |
| REM-4 | Reminders | `ReminderActionReceiver.kt:39` | EVENING "Mark done" uses `.firstOrNull()` and silently drops `eveningIntake()` (2 evening routines, only 1 completed) while the notification copy implies both. |
| ARCH-1 | Architecture | `QuestRepository.kt` (521 lines) | **(Downgraded H→M.)** Low-cohesion god class: ledger + plan assembly + profile CRUD + AI + export + reminders config + onboarding + diagnostics. Clean now, will hurt as features grow. Extract `CompletionLedger` first. |
| ARCH-2 | State mgmt | `TodayViewModel.kt:67,139` | **(Downgraded H→M.)** `TodayViewModel` polls via imperative `refresh()` + snapshot reads, ignoring the repo's reactive Flows → cross-surface staleness (widget/reminder completion doesn't propagate to an open Today). (Note: Quests/QuestBank VMs *do* collect Flows — it's a hybrid, not codebase-wide.) |
| ARCH-3 | DI / seams | `QuestRepository.kt:484,509` | Live `OpenRouterClient`/`AiQuestService` is `new`'d inside the repo, not injected → the app-side AI path is untestable. Inject a factory. |
| ARCH-4 | Layering | `QuestRepository.kt:107-230,296-307` | Domain orchestration (plan assembly, dismissal/progress math) lives in `:app`; `completeQuestLocked` re-derives `previousLevel/newLevel/leveledUp` *after* `engine.recordCompletion`, overwriting the engine's output. Move to `:core`. |
| ARCH-6 | Error handling | `ProfileStore.kt:120` (`decodeList … getOrDefault(emptyList())`) | A JSON decode failure silently drops **all** habits/goals with no breadcrumb. Inconsistent with the typed `Result` style in the AI path. For a local-data app this is a real trust risk — log/telemetry + don't silently zero the list. |
| A11Y-2 | Accessibility | `QuestControls.kt:62,64,77,79,91` | **(Downgraded H→M.)** Glyph buttons `−`, `+`, `−5`, `+5`, rating `1–5` are bare `Text` with no `contentDescription`. |
| UX-LevelRing | Accessibility | `Components.kt:40-81` | `LevelRing` is a `Canvas` + separate `Text` nodes with no merged semantics / `stateDescription`; the progress fraction is invisible to TalkBack. (Pairs with A11Y-1.) |
| UX-MarkDone | Flow | `ReminderActionReceiver.kt:41-43` | "Mark done" notification action completes + cancels the notification with no success/error confirmation — a silent remote mutation. |

---

## Low / Info (validated, condensed)

**Core economy:** level-curve `Double` drift at astronomically high levels + a
~1.7B-iteration `levelForXp(Long.MAX)` stall→overflow (unreachable, `LevelSystem.kt:21-43`);
`isStreakAlive` returns true for a future most-recent day, contradicting `currentStreak`
(`StreakTracker.kt:64-67`); spurious `capReason` on a zero-XP meta record
(`RewardEngine.kt:117`); `activeDays`/`priorSame` definitional nits. The uncapped
MEDIUM+ "real work" lane is **intended** (Info).

**Generation/AI:** misleading "N deferred" note after the empty-plan fallback
(`QuestGenerator.kt:149`); reduction-intent override (`AiQuestValidator.kt:81`); future
`lastCompleted` makes weekly/monthly never due (`QuestScheduler.kt:45`); OVERDRIVE fires
on one busy catch-up day (`SafetyGuard.kt:64`); QUANTITATIVE w/o count → "1 of 1"
(`AiQuestService.kt:159`); "strongest category" lacks a volume floor so 1/1 outranks 9/10
(`ReviewGenerator.kt:67`, Info).

**Data/security:** scoped `fallbackToDestructiveMigrationFrom(1)` is a silent data-loss
path for v1 installs with zero migration test (`QuestLoopDatabase.kt:41`); plaintext-key
migration writes inside the `getAiConfig` getter, racy (`ProfileStore.kt:176`); backup
backstop omits Room WAL/SHM sidecars (Low — only matters if `allowBackup` is flipped;
currently `false`); up to 300 chars of raw OpenRouter error body land in the shareable
diagnostics log (`OpenRouterClient.kt:70`); widget receiver `exported=true` without
`BIND_APPWIDGET`; no cert pinning; **Info:** `completions` table has empty `indices`
(full scans), `DataStoreKeyStore` (plaintext) ships in `src/main` unused (footgun),
`security-crypto` pinned to `1.1.0-alpha06`.

**Reminders/UI:** `addAction(0, …)` passes resource id 0 as the action icon
(`ReminderNotifications.kt:62`); DST spring-forward shift (once/year, self-corrects);
re-arm `minute` silently defaults to 0 if extra absent; failed AI save resets the
just-typed key (`SettingsScreen.kt:275`); one-frame empty flash on Habits (ignores
`loading`); redundant `collectLatest` + explicit `recompute()` race (benign).

**Architecture/UX (Low):** single-object `AppContainer` (intentional manual DI);
mutex RMW spans many DAO calls with no `withTransaction` (hardening, no live bug);
new completion style touches ~5 parallel `when` blocks (compiler-checked); `:core`
exposes `suspend` without declaring coroutines; enum-tolerant parsing duplicated 3×;
`ProfileStore` mixes concerns; `dynamicColor` + hardcoded category hex (visual contrast
only — the dot has a `contentDescription`); BYO-key model field lacks cost/free-tier
framing; manual form precedes quick-capture; measured logging needs a non-obvious
extra "Log" tap; slightly salesy copy on two strings; bare `%.2f` currency; no rest-day
copy; emoji read literally; large-font clipping risk.

---

## What's done well (verified, not assumed)

- **The module split is genuine and enforced** — `:core` has zero Android imports;
  `./gradlew :core:test` validates the entire economy without an SDK. The `LlmClient`
  interface is a textbook dependency-inversion seam.
- **Security/privacy baked into the architecture** — API key only in
  `EncryptedSharedPreferences` (every op `runCatching`-guarded), never logged, never
  exported (`ExportSnapshot` omits `AiConfig`), never in diagnostics; legacy plaintext
  key migrated + scrubbed; `allowBackup=false`; all `PendingIntent`s `FLAG_IMMUTABLE`;
  exported receivers validate `intent.action` and parse extras via `runCatching{valueOf}`;
  no injection sinks; release `isMinifyEnabled=true`.
- **The reward economy is correct** — integer-based caps, monotonic anti-farm,
  idempotent completions netted via a ledger source of truth; the hard concurrency path
  (the XP ledger) *is* mutex-serialized and verified race-free.
- **No dark patterns** — no guilt, fake urgency, or financial pressure; the money
  feature is consistently user-controlled with non-removable disclaimers.
- **Good Compose hygiene** — one-shot events keyed on monotonic counters (not message
  strings), double-tap guards on accept/"Add all", `enumSaver` for `rememberSaveable`,
  `collectAsStateWithLifecycle` everywhere, contextual/minimal permissions, differentiated
  empty states, delete-all confirmation.

---

## Recommended fix order

1. **AI-1 + AI-2 + AI-3** — re-shape the AI safety guardrail (categorized term set +
   normalization + model-side refusal + conservative fallback + red-team tests). Highest harm.
2. **A11Y-1 (+ UX-LevelRing, A11Y-2)** — add `liveRegion`/`stateDescription` and glyph
   `contentDescription`s. Cheap, high-value, aligned with the product's stated values.
3. **REM-1** — refresh the widget from repository write paths.
4. **DATA-6, DATA-2, ECON-1** — fix the mid-month allowance divisor, decide subjective
   re-log monotonicity, and reject non-finite budget caps. Small, real correctness wins.
5. **AI-4, AI-5, UI-1, REM-2, REM-4** — robust JSON extraction, honour the time budget
   for the first quest, guard the manual Add path, switch to `setAlarmClock()`, complete
   *all* evening routines.
6. **ARCH-4 → ARCH-1 → ARCH-3 → ARCH-6 → ARCH-2** — move orchestration/level math into
   `:core`; extract `CompletionLedger` from the repository; inject the `LlmClient`
   factory; stop silently zeroing habit/goal lists; make `TodayViewModel` reactive.
7. Address Low/Info as they're touched; add the missing tests (subjective re-log,
   cross-midnight undo, allowance math, migration, AI red-team, concurrent list mutation).

---

*Reviewers: 8 domain agents (core economy, generation/AI, data, security, UI, reminders,
architecture, UX) + 4 adversarial skeptic verifiers. Every High/Medium finding was
re-checked against live code before inclusion; severities above are post-verification.*
