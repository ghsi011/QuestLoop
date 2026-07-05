# QuestLoop — comprehensive multi-agent code review

**Date:** 2026-07-01 · **Branch:** `claude/codebase-review-multi-agent-0hnmfy` (= `main` @ `1470ba9`)

## How this review was run

A 91-agent workflow, structured so the review itself was reviewed:

1. **Review** — 8 specialized reviewers (architecture, code quality, testing/CI, security & privacy, performance, UX & content, existing-feature correctness, new-feature ideation), each grounded in AGENTS.md, the docs/ set, and the actual source.
2. **Verify** — every finding went to its own adversarial skeptic, instructed to *refute* it against the code (default stance: wrong until the code proves it). Verdicts: confirmed / partially correct / refuted / documented tradeoff; severities were corrected in both directions.
3. **Audit** — two agents audited the review as a whole: a *skeptic-in-chief* re-checked ~30 findings against source and re-ranked priorities; a *coverage auditor* hunted for what the review missed.
4. **Follow-up** — the coverage audit flagged 5 missed areas (R8/ProGuard validation, `data_extraction_rules.xml`, the stuck-busy defect class in unexamined ViewModels, timezone-change alarm handling, `SecureKeyStore` itself); targeted reviewers + skeptics covered them, adding 16 findings.

Totals: ~5.0M subagent tokens, 1,685 tool calls, 2h44m wall-clock (throttled to 2 concurrent agents by the 4-core container).
**Scorecard: 75 findings — 63 confirmed, 11 partially correct, 1 documented tradeoff, 0 refuted.** Post-correction severity: 4 high, 33 medium, 37 low, 1 tradeoff-context.

**Ground truth:** both local suites pass at the review baseline — `:core:test` + `:app:testDebugUnitTest`, **496 tests, 0 failures** (system Gradle 8.14.3; the wrapper cannot download in this sandbox because github.com release assets are egress-blocked — worth knowing for future cloud sessions).

## Executive summary

The codebase is in genuinely good shape structurally: the `:core`/`:app` boundary is real (zero Android imports in `:core`), the AI provider seam is clean, concurrency is disciplined (no `GlobalScope`, purpose-named mutexes), the OAuth loopback is carefully engineered, the content voice is consistently warm and non-shaming, and the test culture is strong (496 green tests, a 0.90 coverage gate on `:core`, past review items genuinely closed and pinned). None of the 75 findings is a security breach or data-loss-in-the-wild.

The problems cluster in four themes:

1. **The newest features shipped with correctness gaps.** The quantitative/interval work introduced a regression (completed daily routines are never dismissed and reappear all day — [49]), an unusable over-completion path for quantitative quests ([50]), one-off measured quests that over-mint XP across days ([52]), and habit-derived weekly quests that cannot meet their own advertised target ([51]).
2. **Error paths are the weakest layer.** Busy flags without `try/finally` and success-only state resets strand screens disabled or blank across at least eight ViewModels ([9], [45], [64]–[69]) — a defect *class*, confirmed screen by screen in the follow-up round.
3. **Release-pipeline trust gaps.** Releases publish an APK gated only by `:core` tests ([18]); R8-minified bytecode is never executed by any test while comments claim it is ([59]); the mapping file is discarded ([60]); the coverage badge can publish from failed runs ([25]).
4. **Honesty drift between copy/docs and behavior.** The privacy screen says "Nothing is uploaded" while opt-in AI sends quest text off-device ([39]) — the single most user-trust-sensitive finding — plus stale architecture/state docs ([7], [47], [63]).

The god-object growth of `QuestRepository` (1,000 LOC, ~61 public members, 11 responsibility clusters — [0]) is the main structural risk; the fix plan deliberately stages surgical fixes first and proposes the decomposition as dedicated follow-up work rather than folding it into a 40-patch wave.

## Top 10 priorities (skeptic-in-chief's ranking)

1. Completed daily routines are never dismissed - they reappear in Today's plan all day (regression) — [49], W01
2. Privacy copy claims "Nothing is uploaded" while opt-in AI sends quest text off-device — [39], W02
3. Over-completion is unusable for QUANTITATIVE quests - stepper hard-caps at target, quest then lingers stuck — [50], W03
4. One-off measured quests reset progress daily, can never complete by accumulation, and over-mint XP across days — [52], W04
5. Habit/bad-habit/goal entity lists stored as JSON blobs in DataStore; a decode failure silently and then permanently wipes them — [2], W20
6. Busy flags set without try/finally in three ViewModels — an error strands the UI disabled — [9], W05
7. Calendar ContentResolver queries run on the main thread — [33], W07
8. Disconnect / Delete-all races with in-flight token refresh: credentials can silently resurrect — [26], W08
9. Releases publish an APK gated only by :core tests; the full suite runs after publication — [18], W09
10. Habit-derived quests cannot meet their own weekly target (only 1 completion creditable per week) — [51], W10

## Priority tiers

Tiering is mine, informed by the audit's ranking, corrected severities, and duplicate grouping. **P0** = user-visible correctness/trust, fix immediately; **P1** = high-value fixes next; **P2** = real but bounded; **P3** = polish/context.

### P0

| # | Finding | Sev | Fix |
|---|---------|-----|-----|
| [49] | Completed daily routines are never dismissed - they reappear in Today's plan all day (regression) | HIGH | W01 |
| [39] | Privacy copy claims "Nothing is uploaded" while opt-in AI sends quest text off-device | HIGH | W02 |
| [50] | Over-completion is unusable for QUANTITATIVE quests - stepper hard-caps at target, quest then linger | HIGH | W03 |
| [52] | One-off measured quests reset progress daily, can never complete by accumulation, and over-mint XP a | MEDIUM | W04 |
| [59] | R8-minified bytecode is never executed by any test or CI job, while comments and docs claim it is va | HIGH | proposal |

### P1

| # | Finding | Sev | Fix |
|---|---------|-----|-----|
| [2] | Habit/bad-habit/goal entity lists stored as JSON blobs in DataStore; a decode failure silently and t | MEDIUM | W20 |
| [3] | Widget shows a different plan than the app: todayPlan makes callers thread the persisted energy chec | MEDIUM | W13 |
| [8] | OAuth loopback accept() is not cancellation-aware; cancelled sign-in holds port 1455 and discards a  | MEDIUM | W15 |
| [9] | Busy flags set without try/finally in three ViewModels — an error strands the UI disabled | MEDIUM | W05 |
| [10] | Blocking OkHttp execute() in withContext(IO) ignores coroutine cancellation; wake-lock timeout under | MEDIUM | W16 |
| [12] | saveOpenAi swallows persistence failure and always reports "AI settings saved" | MEDIUM | W18 |
| [18] | Releases publish an APK gated only by :core tests; the full suite runs after publication | MEDIUM | W09 |
| [20] | SchemaExportTest claims per-push protection it does not have; migration discipline is unguarded at r | MEDIUM | W09 |
| [26] | Disconnect / Delete-all races with in-flight token refresh: credentials can silently resurrect | MEDIUM | W08 |
| [27] | Backup-rule backstop is incomplete: Room WAL/SHM sidecars and ai_diagnostics.log not excluded | LOW | W22 |
| [32] | BootReceiver hand-wires ProfileStore with the plaintext test-default keystore | LOW | W12 |
| [33] | Calendar ContentResolver queries run on the main thread | MEDIUM | W07 |
| [43] | Reminder "Mark done" fallback silently dead-ends on Android 12+ (notification trampoline) | MEDIUM | W11 |
| [45] | Failures leave screens stuck: busy/loading flags never reset and no screen has an error state | MEDIUM | W05 |
| [51] | Habit-derived quests cannot meet their own weekly target (only 1 completion creditable per week) | LOW | W10 |
| [54] | Widget shows yesterday's plan after midnight (and the wrong day-part list) until the app is opened | MEDIUM | W14 |
| [57] | Reminder 'Mark done' fallback uses a notification trampoline blocked on Android 12+ (targetSdk 36) | LOW | W11 |
| [62] | data_extraction_rules.xml (the file governing Android 12+) repeats the WAL/SHM and ai_diagnostics.lo | MEDIUM | W22 |
| [64] | HabitsViewModel: failed load renders "No habits yet" over real data; failed mutate silently discards | MEDIUM | W05 |
| [65] | QuestsViewModel/QuestBankViewModel: one emission failure permanently kills the quests collector — Qu | LOW | W06 |
| [66] | QuestBankViewModel.add: a failed addFromBank strands the id in `adding`, silently dead-ending that r | LOW | W06 |
| [67] | ReviewViewModel: load failure blanks the Reviews tab; summarize failure permanently disables the AI  | LOW | W06 |
| [68] | AchievementsViewModel: one-shot init load with no retry API; a failure pins the whole visit on a ful | MEDIUM | W05 |
| [69] | RewardsViewModel.load: same success-only loading reset; flag never consumed, so failure silently ren | LOW | W06 |
| [70] | Armed reminder alarms are never corrected on timezone or clock changes — pending alarm fires at the  | MEDIUM | W17 |
| [71] | Plaintext key store is the constructor DEFAULT in the credential path (root cause behind the BootRec | MEDIUM | W12 |

### P2

| # | Finding | Sev | Fix |
|---|---------|-----|-----|
| [0] | QuestRepository is a god object: ~30 public operations across ~11 unrelated responsibility clusters | MEDIUM | proposal |
| [1] | Economy-critical interval-slot logic lives in :app, outside the per-push :core test gate, and is dup | MEDIUM | W19 |
| [4] | questOverview rebuilds the candidate pool 4-5 times with N+1 find() loops on every quests-flow emiss | MEDIUM | W21 |
| [11] | N+1 DB queries and triple recomputation in QuestRepository's per-quest status paths | MEDIUM | W21 |
| [13] | runCatching around suspend AI calls swallows CancellationException in :core | LOW | W30 |
| [14] | Settings LaunchedEffect(state.reminders) fires with the placeholder default config, transiently canc | LOW | W23 |
| [16] | Week/month interval math duplicated between QuestRepository and AppClock | LOW | W19 |
| [21] | Notification 'Mark done' XP-crediting path has zero tests and is coverage-excluded on a contradicted | MEDIUM | W29 |
| [22] | Documented double-tap / one-shot-event regressions have no pinning tests | MEDIUM | W29 |
| [23] | AppClock is an uninjectable global; day-boundary ViewModel behavior is untestable and two tests depe | LOW | W37 |
| [24] | Reminder scheduling is tested only in UTC; DST transitions unexercised | LOW | W35 |
| [25] | Coverage badge publishes from failed nightly runs, silently misreporting the public number | LOW | W09 |
| [28] | Diagnostics redaction misses tokens rotated mid-call and can log provider-echoed user text | LOW | W28 |
| [30] | OpenRouter API key field lets the IME learn/suggest the key | LOW | W24 |
| [31] | OAuth loopback: local apps can abort sign-in via error param (checked before state), and every reque | LOW | W31 |
| [34] | N+1 per-quest find() loops re-executed 2-3x per screen refresh | MEDIUM | W21 |
| [35] | Widget freshness trigger re-reads and re-maps the entire completion ledger on every write | LOW | W32 |
| [36] | Completed screen all-time filter loads, maps, and sorts the whole ledger in memory on Main | LOW | W33 |
| [37] | completions table has no secondary indexes; all windowed/aggregate queries are full scans | LOW | W25 |
| [40] | "Skip" is penalized as "Missed" with only a ~4-second recovery window | MEDIUM | W38 |
| [41] | Raw transport exception text shown on the Add screen for AI failures | MEDIUM | W27 |
| [42] | Add/edit forms leak internal vocabulary: "Binary", "Meta maintenance", "Recurring" vs "Daily" | MEDIUM | W39 |
| [44] | Completion controls are glyph-only for TalkBack (known UX_REVIEW L6, still unfixed) | MEDIUM | W26 |
| [53] | Terminal PARTIAL completions (subjective ratings 1-4, end-of-interval partial logs) are invisible in | MEDIUM | proposal |
| [55] | Habit/goal-derived quests deferred from today's plan cannot be completed anywhere that day | LOW | proposal |
| [56] | Reviews 'Plan' view and Today disagree on when a measured weekly/monthly quest is next due | LOW | W19 |
| [58] | 'Delete all my data' leaves the AI diagnostics log on disk | LOW | W22 |
| [60] | Release workflow discards the R8 mapping file and keeps no line numbers — signed-release crashes wil | MEDIUM | W09 |
| [72] | No scrub or migration path for the v2 plaintext credential slots; v1 scrub is one-shot fragile | LOW | W08 |
| [73] | EncryptedKeyStore has zero test coverage anywhere; the coverage-exclusion rationale is false for the | MEDIUM | W36 |
| [74] | Key-safety unit test asserts a weaker invariant than its name claims: the key IS in plaintext DataSt | LOW | W36 |

### P3

| # | Finding | Sev | Fix |
|---|---------|-----|-----|
| [5] | Sub-screen navigation routes are bare strings duplicated across four decision points | LOW | W34 |
| [6] | Cross-ViewModel coupling through a process-global static draft cache | LOW | proposal |
| [7] | Architecture docs materially stale: module trees omit half the code, and two 'known limitations' are | LOW | W40 |
| [15] | Dead field: UserProfile.totalXp survives from the pre-ledger design | LOW | proposal |
| [17] | Near-duplicate completion/undo plumbing and chip-row helpers across screens | LOW | proposal |
| [19] | Per-push CI never compiles :app — smoke-only gate leaves a day-long blind spot (arguing the document | LOW | proposal |
| [29] | Credential store built on deprecated androidx.security-crypto (EncryptedSharedPreferences) | LOW | proposal |
| [38] | Release build enables R8 minify but not resource shrinking | LOW | proposal |
| [46] | Add screen still leads with the long manual form (known UX_REVIEW M4, still unfixed) | LOW | proposal |
| [47] | Stale docs: CURRENT_STATE and SAFETY_AND_PRIVACY no longer match the shipped UI | LOW | W40 |
| [48] | All UI copy is hardcoded in Kotlin; strings.xml holds only app_name | LOW | proposal |
| [61] | Hand-written serializer keep rules are redundant with the library's bundled rules and misleadingly s | LOW | proposal |
| [63] | Manifest/XML comments and SAFETY_AND_PRIVACY.md wrongly frame the rules as a dormant backstop; on An | LOW | W02 |

## The fix wave (implemented next, one agent + one reviewer per fix)

40 fixes, each implemented by a dedicated agent in an isolated worktree and reviewed by a dedicated reviewer agent before its patch lands; duplicates from different reviewers are folded into single fixes. Patches apply small-and-surgical first, structural last (W19/W21 absorb rebase cost). A combined-diff review + skeptic pass runs over everything together before merge.

- **W01** ([49]): Exclude completed daily routines from the rest of today's plan (regression fix + pinning test).
- **W02** ([39], [63]): Make privacy copy honest about opt-in AI upload; align SAFETY_AND_PRIVACY.md and manifest/XML framing.
- **W03** ([50]): Let QUANTITATIVE steppers log past target so over-completion works and the quest completes.
- **W04** ([52]): Reconcile one-off measured semantics with the in-code doc note: accumulate or stop re-minting across days.
- **W05** ([9], [45], [64], [68]): Busy/error-state class fix, group 1: try/finally on busy flags + error surfacing (Habits, Achievements, plus the three flagged originals).
- **W06** ([65], [66], [67], [69]): Busy/error-state class fix, group 2: collector resilience and stranded flags (Quests/QuestBank, Review, Rewards).
- **W07** ([33]): Move calendar ContentResolver reads off the main thread.
- **W08** ([26], [72]): Serialize disconnect/delete-all with token refresh via aiAuthMutex; scrub v2 plaintext slots.
- **W09** ([18], [20], [25], [60]): Release/CI trust: gate release on app unit suite, fix SchemaExportTest claim, badge only from green runs, keep the R8 mapping artifact.
- **W10** ([51]): Let habit-derived weekly quests credit N completions per week (slots per occurrence, :core tests).
- **W11** ([43], [57]): Replace the Android-12-blocked notification trampoline with a direct receiver completion path.
- **W12** ([71], [32]): Make the encrypted store the explicit default in the credential path; fix BootReceiver wiring.
- **W13** ([3]): todayPlan resolves the persisted energy check-in itself; widget and reminder receiver then agree with the app.
- **W14** ([54]): Refresh the widget at the day boundary / recompute epochDay at render.
- **W15** ([8]): Make the OAuth loopback accept() cancellation-aware; release port 1455 and honor a completed handshake.
- **W16** ([10]): Propagate coroutine cancellation into OkHttp calls; wake-lock timeout must cover the read timeout.
- **W17** ([70]): Re-arm reminder alarms on TIMEZONE_CHANGED / TIME_SET.
- **W18** ([12]): Surface saveOpenAi persistence failures instead of always reporting success.
- **W19** ([1], [16], [56]): Move interval-slot math to :core (CompletionSlots), AppClock delegates, align Reviews next-due with Today; port tests to :core.
- **W20** ([2]): Harden ProfileStore decode failure: never overwrite the raw blob with emptyList on the next edit; surface the error.
- **W21** ([4], [11], [34]): Assemble a per-call DayContext once (profile, candidates, IN-clause interval records) to kill the N+1 / recompute pattern.
- **W22** ([27], [62], [58]): Exclude Room WAL/SHM + ai_diagnostics.log in both backup XMLs; 'Delete all my data' removes the diagnostics log.
- **W23** ([14]): Guard the Settings reminders LaunchedEffect against the placeholder default emission.
- **W24** ([30]): API-key field: password input type / no personalized IME learning.
- **W25** ([37]): Add secondary indexes to completions (Room migration + schema export).
- **W26** ([44]): Content descriptions + semantics for glyph-only completion controls (UX_REVIEW L6).
- **W27** ([41]): Human framing for AI failure copy while preserving the provider error body per AGENTS.md.
- **W28** ([28]): Close diagnostics redaction gaps: tokens rotated mid-call, provider-echoed user text.
- **W29** ([21], [22]): Add the missing tests: notification Mark-done XP path; pinning tests for documented double-tap/one-shot regressions.
- **W30** ([13]): Rethrow CancellationException from :core runCatching wrappers.
- **W31** ([31]): Loopback: validate state before honoring error param; neutral response page.
- **W32** ([35]): Cheap change-detection for widget freshness instead of re-mapping the whole ledger per write.
- **W33** ([36]): Move Completed all-time mapping off the hot path (windowed/off-main slice).
- **W34** ([5]): Extract navigation route constants.
- **W35** ([24]): Reminder scheduling tests across timezones/DST.
- **W36** ([73], [74]): EncryptedKeyStore: real instrumented coverage + fix the misnamed weak-invariant unit test.
- **W37** ([23]): Make AppClock injectable; migrate the two wall-clock-dependent tests.
- **W38** ([40]): Distinct, gentler Skip handling per REWARD_ECONOMY.md; extend the undo window.
- **W39** ([42]): Replace internal vocabulary in add/edit forms per CONTENT_STYLE.md.
- **W40** ([7], [47]): Refresh stale docs: ARCHITECTURE, CURRENT_STATE, SAFETY_AND_PRIVACY vs shipped code.

### Proposed, not auto-implemented

- **[0]** Full QuestRepository decomposition (AiOrchestrator, backup service, CompletionLedger) — high-churn refactor; do it as a dedicated change, not in a 40-patch wave.
- **[2]** Long-term: migrate habits/bad-habits/goals from DataStore JSON blobs to Room tables (W20 only hardens the failure mode).
- **[6]** Replace the process-global draft cache with nav-scoped state.
- **[15]** Remove the dead UserProfile.totalXp field (needs an export/import compat decision).
- **[17]** Deduplicate completion/undo plumbing and chip-row helpers.
- **[19]** Per-push :app compile gate — documented tradeoff; revisit only if the team wants it.
- **[29]** androidx.security-crypto deprecation — documented tradeoff; no drop-in replacement today.
- **[38]** Resource shrinking — unsafe to flip while nothing executes minified bytecode (see 59).
- **[46]** Add-screen redesign to lead with quick capture (UX_REVIEW M4) — product design work.
- **[48]** Externalize UI strings to strings.xml — large mechanical change, prerequisite for localization.
- **[53]** Surface terminal PARTIAL completions in Completed history — needs a small product/UI decision.
- **[55]** Make deferred habit/goal-derived quests completable elsewhere the same day — product decision.
- **[59]** Execute minified bytecode in CI (minified-variant smoke on the emulator). W09 fixes the false claims and keeps the mapping; this closes the actual gap.
- **[61]** Prune redundant serializer keep-rules — only together with 59's runtime validation.

## Findings in full

### architecture

#### [0] QuestRepository is a god object: ~30 public operations across ~11 unrelated responsibility clusters

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:55` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · proposal

The 1000-LOC hub mixes quest CRUD, plan generation (181-343), the completion ledger (344-561), achievements/streaks (563-588), reviews/allowance/reward-fund (590-652), settings pass-throughs (664-670), habit/goal CRUD (672-697), backup (699-800), and the entire AI stack including OAuth token lifecycle and client factory (811-967). Every ViewModel receives the whole surface (ui/ViewModelFactory.kt:19-30). Each new feature lands here, growing review risk around the economy-critical ledger code it shares a file with.

**Recommendation:** Incremental, not rewrite: first extract an AiOrchestrator (lines 811-967 plus aiAuthMutex; deps: ProfilePreferences, OpenAiAuth, AiDiagnostics, AiCallGuard, a questDao existing-quests supplier) - it shares no mutex with the ledger. Then a backup service, moving completionMutex into a small CompletionLedger so locks travel with their data.

<sub>Skeptic: File is exactly 1000 lines; every cited line range and ViewModelFactory.kt:19-30 (10 ViewModels, full surface) verified. Public surface is actually ~61 members, so "~30" undercounts — finding is conservative. aiAuthMutex (849) shares no lock with the ledger; extraction deps accurate; importJson's completionMutex use matches the CompletionLedger step. Docs describe the hub but never defend it as a tradeoff.</sub>

#### [1] Economy-critical interval-slot logic lives in :app, outside the per-push :core test gate, and is duplicated in AppClock

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:282` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W19

accumulates/hasCalendarInterval/completionSlot/intervalStartFor (257-303) are pure functions that decide the instanceId idempotency key - the invariant protecting XP from double-counting. They sit in :app, covered only by Robolectric tests run nightly/manual (full-tests.yml), not per-push smoke. This contradicts ARCHITECTURE.md goal 1 and .claude/rules/testing.md ('prefer a :core test whenever the logic can live in :core'). AppClock.startOfWeek/startOfMonth (ui/Clock.kt:13-21) reimplements the same calendar math; divergence would desync Completed filters from completion slots.

**Recommendation:** Move the four functions to core/completion (e.g. CompletionSlots beside CompletionPolicy) - they need only epochDay/frequency/style; java.time is fine in :core. Port the slot cases from QuestRepositoryCompletionStylesTest to :core, and make AppClock delegate to it.

<sub>Skeptic: Verified: completionSlot builds instanceId (QuestRepository.kt:484-485); intervalStartFor math is identical to AppClock.startOfWeek/startOfMonth (Clock.kt:13-21) which drive Completed/review windows; slot tests are Robolectric-only, run in full-tests.yml; smoke.yml runs :core only; no :core test covers this math; cited docs say what's quoted. Nit: slot cases live in QuestRepositoryTest.kt, not QuestRepositoryCompletionStylesTest.</sub>

#### [2] Habit/bad-habit/goal entity lists stored as JSON blobs in DataStore; a decode failure silently and then permanently wipes them

`app/src/main/java/com/questloop/app/data/ProfileStore.kt:129` · **MEDIUM** · effort large · skeptic verdict: **confirmed** · P1 · fix W20

ARCHITECTURE.md:70-73 justifies DataStore for 'scalar profile/preferences', but id-keyed entity lists now live there as JSON (Keys.HABITS/BAD_HABITS/GOALS), requiring whole-list rewrites serialized by a hand-rolled profileMutex (QuestRepository.kt:79, 672-697). decodeList falls back to emptyList() on any decode failure (corrupt blob, incompatible core-model schema change), and the next addHabit read-modify-write persists that empty list (QuestRepository.kt:672-675) - silent, permanent loss of the user's habits/goals.

**Recommendation:** Move habits/bad-habits/goals to Room tables (per-row upsert/delete kills profileMutex and blob rewrites). Short-term hardening: make decodeList failure surface (keep raw blob, don't overwrite on next edit) instead of defaulting to empty.

<sub>Skeptic: Every anchor checks out: JSON blobs (ProfileStore.kt:89-91), decodeList getOrDefault(emptyList()) with no logging (129-131), profileMutex RMW (QuestRepository.kt:79, 672-697) overwrites the raw blob on next edit, ARCHITECTURE.md:70-73 says scalars. ProfileStoreTest pins only the read-side empty fallback (crash avoidance); the permanent overwrite consequence is untested and undocumented. Enum/required-field changes defeat ignoreUnknownKeys.</sub>

#### [3] Widget shows a different plan than the app: todayPlan makes callers thread the persisted energy check-in, and QuestWidget doesn't

`app/src/main/java/com/questloop/app/widget/QuestWidget.kt:36` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W13

todayPlan(epochDay, dayPart, checkIn = null) (QuestRepository.kt:181-185) requires each caller to fetch and pass todayCheckIn - state the repository itself persists. TodayViewModel (69-70) and questOverview (129-130) do; QuestWidget and ReminderActionReceiver (ReminderActionReceiver.kt:45) don't. Check-in changes plan size, difficulty ceiling, and the time budget (QuestGenerator.kt:56-66), so on a low-energy day the widget shows the full plan, including hard quests the Today screen deliberately hides.

**Recommendation:** Resolve the persisted check-in inside todayPlan (repository already exposes todayCheckIn); keep the parameter only as an explicit override. The widget and reminder receiver then agree with the app for free.

<sub>Skeptic: Verified all citations: todayPlan defaults checkIn=null (QuestRepository.kt:181-185); widget (QuestWidget.kt:36) and receiver (ReminderActionReceiver.kt:45) omit it; ViewModel/questOverview pass it. Check-in gates plan count, MEDIUM ceiling, time budget (QuestGenerator.kt:64-82). keepWidgetFresh even refreshes the widget on check-in writes, yet it rebuilds without it. Receiver impact nil (routines unconditional), but widget divergence is real; not documented as intentional.</sub>

#### [4] questOverview rebuilds the candidate pool 4-5 times with N+1 find() loops on every quests-flow emission

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:128` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · fix W21

One questOverview call runs todayPlan (profile read, questDao.getActive, lastCompletedDays, dismissedQuestIdsToday), then re-runs lastCompletedDays (132), dismissedQuestIdsToday (135), todayProgress (136), and getActive (137). candidateQuestsById (251-255) re-reads profile + getActive + HabitQuestFactory.deriveAll each time, and dismissedQuestIdsToday (310-323) / todayProgress (330-342) each issue one completionDao.find() per candidate quest. QuestsViewModel triggers this on every quests emission (QuestsViewModel.kt:48-51). Bounded by local SQLite today, but it is the structural cost of composing suspend helpers with no shared per-call context, and grows with quest count.

**Recommendation:** Assemble a per-call DayContext (profile, candidates, lastCompleted, current-interval records fetched with one IN-clause query) once, and pass it to dismissedQuestIdsToday/todayProgress/todayPlan as parameters instead of each re-fetching.

<sub>Skeptic: Traced the call graph: questOverview re-runs lastCompletedDays (132), dismissedQuestIdsToday (135), todayProgress (136), getActive (137) after todayPlan already fetched them; candidateQuestsById executes 3x plus todayPlan's inline build (4 pool rebuilds, 5 getActive calls), and completionDao.find() runs per-candidate in two loops. QuestsViewModel:48-51 triggers per quests emission. No caching, batching, or documented tradeoff exists; severity fits an architecture review.</sub>

#### [5] Sub-screen navigation routes are bare strings duplicated across four decision points

`app/src/main/java/com/questloop/app/ui/QuestLoopApp.kt:136` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · fix W34

The Dest enum covers only the five tabs; sub-screens ('add', 'habits', 'achievements', 'quest-bank', 'completed') are string literals repeated in tabForRoute (72-79), isSubScreen (136-137), the title switch (138-145), and the NavHost composables (214-249). Adding one sub-screen means editing four independent switch sites; a missed one silently yields a wrong tab highlight, title, or back arrow.

**Recommendation:** Fold sub-screens into one route registry (sealed class or enum with route, title, owningTab, isSubScreen) and derive tabForRoute/isSubScreen/title from it; keep the NavHost as the single place listing destinations.

<sub>Skeptic: Read QuestLoopApp.kt: Dest covers only 5 tabs; the five sub-screen literals recur exactly at tabForRoute (72-79), isSubScreen (136-137), title when (138-145), NavHost (214-249), plus openOnce call sites. Misses fail silently (private funs, no unit tests; AppSmokeTest checks content not titles/highlight, CI-only). AGENTS.md documents the sync convention, not the duplication as deliberate.</sub>

#### [6] Cross-ViewModel coupling through a process-global static draft cache

`app/src/main/java/com/questloop/app/ui/settings/SettingsViewModel.kt:231` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · proposal

SettingsViewModel.deleteAllData reaches into another ViewModel's companion (AddQuestViewModel.resetDraftCache(), AddQuestViewModel.kt:250-259) to clear the Add-screen draft, which is itself a mutable process-global static (savedDraft) rather than SavedStateHandle or a container-owned store. The 'delete everything' invariant is thus split across two ViewModels via hidden global state; any future draft-like cache must remember to register itself here.

**Recommendation:** Own the draft in a small DraftStore held by AppContainer (or SavedStateHandle), injected into AddQuestViewModel and cleared by QuestRepository.deleteAllData alongside the other stores - removing the static and the cross-VM reach.

<sub>Skeptic: SettingsViewModel.kt:231 does call AddQuestViewModel.resetDraftCache(); AddQuestViewModel.kt:250-259 holds savedDraft as a mutable companion static (no SavedStateHandle in app/src, not in AppContainer). QuestRepository.deleteAllData clears only DAOs+profileStore, so the wipe invariant is split across two ViewModels. Nothing in AGENTS.md/docs documents this tradeoff. Low severity is apt: correct behavior today, coupling risk only.</sub>

#### [7] Architecture docs materially stale: module trees omit half the code, and two 'known limitations' are long since implemented

`docs/DESIGN_DECISIONS.md:81` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · fix W40

DESIGN_DECISIONS.md:79-83 claims AI generation 'currently runs through the deterministic FallbackSuggester' (live OpenRouter + OpenAI OAuth backends are fully wired: QuestRepository.kt:838-841) and achievements are 'not yet surfaced as a dedicated screen' (ui/achievements/AchievementsScreen.kt exists, routed at QuestLoopApp.kt:218). ARCHITECTURE.md:16-54 module trees omit core ai/openai, completion/, calendar/, most of generation/, and app widget/, reminders/, OAuth files, and six screen packages. CURRENT_STATE.md:67-74 mentions only OpenRouter, not the ChatGPT provider.

**Recommendation:** Refresh DESIGN_DECISIONS.md's known-limitations list and ARCHITECTURE.md's module trees (or replace trees with package one-liners that age better); add the OpenAI provider to CURRENT_STATE.md's AI section.

<sub>Skeptic: Verified every sub-claim: DESIGN_DECISIONS.md:79-83 contains both stale limitations; QuestRepository.kt:838-841 wires live OpenRouter+OpenAI clients; AchievementsScreen routed at QuestLoopApp.kt:218; ARCHITECTURE.md trees omit core calendar/, completion/, ai/openai, 5/6 generation files, app widget/reminders/ and exactly six ui packages; CURRENT_STATE.md AI section (67-74) mentions only OpenRouter. No doc marks this staleness deliberate. Low severity fits docs-only drift.</sub>

### code-quality

#### [8] OAuth loopback accept() is not cancellation-aware; cancelled sign-in holds port 1455 and discards a completed handshake

`app/src/main/java/com/questloop/app/data/OpenAiAuthService.kt:117` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P1 · fix W15

signIn runs blocking ServerSocket.accept() under withContext(Dispatchers.IO) with soTimeout up to the full 5-minute deadline (lines 66-79, 117-127). Coroutine cancellation (SettingsViewModel destroyed by a tab switch, QuestLoopApp.switchTab pops with saveState=false) cannot interrupt it: the IO thread stays blocked and port 1455 stays bound. A retried sign-in then fails at server.bind (SO_REUSEADDR doesn't allow a second live listener) with the generic error; a browser handshake completing after cancellation shows "You're signed in" but the tokens are discarded when withContext rethrows CancellationException.

**Recommendation:** Close the ServerSocket on cancellation (job.invokeOnCompletion / suspendCancellableCoroutine's invokeOnCancellation), or cap soTimeout at ~1s and check coroutineContext.isActive each accept loop iteration.

<sub>Skeptic: Verified every element: soTimeout=full remaining deadline (line 122), no suspension points so cancellation can't unblock accept; switchTab pops with saveState=false (QuestLoopApp.kt:88-93) clearing the nav-entry-scoped SettingsViewModel; fresh aiBusy lets retry hit BindException (reuseAddress only covers TIME_WAIT); success page written and tokens discarded by withContext cancellation. No mitigation, test, or documented tradeoff exists. But the port self-heals at the 5-min deadline and nothing is lost — medium, not high.</sub>

#### [9] Busy flags set without try/finally in three ViewModels — an error strands the UI disabled

`app/src/main/java/com/questloop/app/ui/add/AddQuestViewModel.kt:222` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W05

AddQuestViewModel sets generating (141, 167), refiningId (201), saving (222, 241) and clears them only on the success path; launchSafely routes any exception (e.g. a Room write failing in acceptSuggestion/acceptAll) to a log, so the flag never resets — permanent spinner, all Add buttons disabled. Same pattern in ReviewViewModel.summarizeWithAi (76-83, summarizing) and QuestBankViewModel.add (51-62, adding is only cleared by the collector on success). Inconsistent with TodayViewModel:157-163, QuestsViewModel:76-90, CompletedViewModel:67-73, RewardsViewModel:79-87, SettingsViewModel:139-157, which all use try/finally.

**Recommendation:** Wrap each flagged block in try/finally (as the other five ViewModels already do), or add an onError that clears the busy state and surfaces a message.

<sub>Skeptic: Verified all cited lines: three ViewModels clear busy flags only on success; launchSafely swallows non-cancellation exceptions (log-only), so an uncaught Room write (addQuest/addFromBank/completeOnboardingQuest have no catch) strands the flag and guards silently block retries. Five sibling ViewModels use try/finally; Settings even comments why. UI disables buttons on these flags. Not documented as intentional. Medium severity fits: error-path only, silent stuck UI.</sub>

#### [10] Blocking OkHttp execute() in withContext(IO) ignores coroutine cancellation; wake-lock timeout undershoots the read timeout

`app/src/main/java/com/questloop/app/data/OpenAiClient.kt:64` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P1 · fix W16

OpenAiClient.call:64, OpenRouterClient.complete:49, and OpenAiAuthService.postForm:100 use call.execute() inside withContext(Dispatchers.IO). Cancelling the caller (leaving the Add screen mid-generation kills its viewModelScope) leaves the request running up to the read timeout (120s OpenAI / 60s OpenRouter), blocking an IO thread and burning network/battery for a discarded result. Also WakeLockAiCallGuard's 90s timeout (AiCallGuard.kt:25) is shorter than OpenAiClient's 120s read timeout (OpenAiClient.kt:98), so the lock lapses before slow reasoning responses finish — undercutting its stated purpose.

**Recommendation:** Use OkHttp's coroutine executeAsync/await pattern (or suspendCancellableCoroutine with invokeOnCancellation { call.cancel() }) so cancellation aborts the call. Align the wake-lock timeout with the longest client read timeout.

<sub>Skeptic: Verified all three execute() sites sit inside withContext(IO) with no runInterruptible/call.cancel/executeAsync anywhere. AddQuestViewModel launches via viewModelScope, scoped to the nav entry of composable("add"), so leaving cancels while execute() blocks to timeout uncancelled. AiCallGuard.kt:25 is 90s vs OpenAiClient's 120s readTimeout; AGENTS.md documents the wake-lock purpose, not this mismatch. All cited line numbers accurate.</sub>

#### [11] N+1 DB queries and triple recomputation in QuestRepository's per-quest status paths

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:310` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · fix W21

dismissedQuestIdsToday (310-323) and todayProgress (330-342) each issue one completionDao.find() per candidate quest, and each call candidateQuestsById() (251-255: a profile read + questDao.getActive + deriveAll). questOverview (128-150) triggers this three times per refresh — via todayPlan at 130 (which computes dismissed at 192), then again at 135 and 136 — and QuestsViewModel.recompute reruns the whole thing on every quests-flow emission (QuestsViewModel.kt:48-51). Bounded by quest count, but the hot path scales as ~3 full recomputes + 2N point queries.

**Recommendation:** Compute candidateQuestsById once per refresh and pass it down; batch the point lookups with one `WHERE instanceId IN (:ids)` DAO query. Let questOverview reuse the dismissal set todayPlan already computed.

<sub>Skeptic: Read QuestRepository.kt 100-360, QuestsViewModel.kt, Daos.kt. Every cited line checks out: per-quest completionDao.find() loops at 310-323/330-342, candidateQuestsById at 251-255, questOverview triggering dismissal twice (192 via todayPlan:130, plus 135) and todayProgress (136); recompute per quests emission at ViewModel 48-50. No batch IN-query, no caching; AGENTS.md documents no tradeoff. Finding slightly undercounts (getActive ~5x, lastCompletedDays 2x).</sub>

#### [12] saveOpenAi swallows persistence failure and always reports "AI settings saved"

`app/src/main/java/com/questloop/app/ui/settings/SettingsViewModel.kt:111` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W18

saveOpenAi (108-124) wraps repository.setAiConfig in runCatching but discards the Result and unconditionally emits "AI settings saved" (122). setAiConfig writes through SecureKeyStore (ProfileStore.setAiConfig:236-249), which can throw on a corrupt/unavailable Keystore — the sibling saveAi (72-95) explicitly handles exactly this, checks the write, re-verifies persistence, and reports "Couldn't save your key" on failure. The asymmetry means an OpenAI-provider user gets a false success confirmation while nothing was saved. setProvider (98-105) has no handling at all (failure is silently logged).

**Recommendation:** Check the runCatching result in saveOpenAi (and setProvider) and emit a failure message when the write throws, mirroring saveAi's verification.

<sub>Skeptic: Read SettingsViewModel.kt, ProfileStore.setAiConfig, SecureKeyStore, launchSafely, tests, git history. saveOpenAi discards the runCatching Result and unconditionally emits "AI settings saved"; EncryptedKeyStore writes deliberately throw (check(commit())) and its comment says failures MUST reach SettingsViewModel for user reporting — saveAi honors this, saveOpenAi and setProvider don't. No mitigations or documented tradeoff found.</sub>

#### [13] runCatching around suspend AI calls swallows CancellationException in :core

`core/src/main/kotlin/com/questloop/core/ai/AiQuestService.kt:68` · **LOW** · effort small · skeptic verdict: **partially correct** · P2 · fix W30

AiQuestService (lines 68, 94, 134) and AiNarrator (80, 95) wrap `client.complete(...)` — a suspend call — in runCatching, so a cancelled coroutine is converted into an ordinary failure ("AI request failed: ...") and control continues in a cancelled scope (fallback suggestions built, error paths taken) instead of propagating cancellation. QuestRepository.importJson:742-791 similarly runCatches a large suspending block, turning a cancelled import into a scary "Couldn't finish importing" result. The project knows the correct idiom — launchSafely (util/LaunchSafely.kt:27-28) explicitly rethrows CancellationException.

**Recommendation:** Rethrow CancellationException from these runCatching blocks (a small `runCatchingCancellable` helper in :core), matching launchSafely's pattern.

**Skeptic's correction:** The runCatching blocks (AiQuestService.kt:68/94/134, AiNarrator.kt:80/95) do swallow CancellationException and convert it into a fallback-with-error result, and launchSafely shows the repo knows the rethrow idiom — but today this is latent fragility, not observable misbehavior. Every swallowed-CE path in QuestRepository immediately calls recordAiError (withContext(Dispatchers.IO)), which rethrows cancellation on entry, and launchSafely propagates it; the only cancellation source is ViewModel destruction (no withTimeout or cancel-and-retry patterns exist), so the bogus "AI request failed" state is never user-visible. The importJson claim is incorrect: its body runs inside withContext(Dispatchers.IO) (QuestRepository.kt:729), which on cancellation rethrows CE at the boundary and discards the "Couldn't finish importing" ImportResult (its KDoc explicitly notes a cancelled import is harmless). A runCatchingCancellable helper in :core is still worthwhile hygiene against future callers (timeouts, retry-cancel patterns), but as low severity.

<sub>Skeptic: Verified all cited runCatching sites wrap suspend client.complete and launchSafely rethrows CE. But traced callers: every swallowed-CE path hits recordAiError's withContext(IO), which rethrows cancellation; only cancellation source is ViewModel destruction. importJson's withContext(Dispatchers.IO) wrapper rethrows CE on exit, discarding the error result — the scary message never reaches users. Latent fragility only.</sub>

#### [14] Settings LaunchedEffect(state.reminders) fires with the placeholder default config, transiently cancelling armed alarms

`app/src/main/java/com/questloop/app/ui/settings/SettingsScreen.kt:75` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W23

SettingsUiState initialises reminders = ReminderConfig() (enabled=false, ReminderConfig.kt:8) and the effect runs before reload() completes, so every visit to Settings first executes ReminderScheduler.apply(disabled) — which cancelAll()s the user's alarms (ReminderScheduler.kt:24-26) — then re-arms once the real config loads. If reload() fails, alarms stay cancelled until the next app launch. The runCatching also hides scheduling failures while the ViewModel separately confirms "Reminders on". Duplicates the re-arm in QuestLoopApp.kt:129-131.

**Recommendation:** Guard the effect on !state.loading (or move (re)scheduling into the ViewModel after a confirmed load/save), so placeholder state never drives the AlarmManager side effect.

<sub>Skeptic: Read SettingsScreen.kt:75-77, SettingsViewModel.kt:18-62, ReminderConfig.kt:8, ReminderScheduler.kt:24-33, ui/QuestLoopApp.kt:88-131, LaunchSafely.kt. All elements hold: placeholder enabled=false drives apply()->cancelAll before reload() finishes; tab nav (saveState=false) recreates the VM so it recurs every visit; launchSafely swallows reload failures leaving alarms cancelled until relaunch/boot; "Reminders on" confirms without arming. Low severity fits: normal case self-heals in milliseconds.</sub>

#### [15] Dead field: UserProfile.totalXp survives from the pre-ledger design

`core/src/main/kotlin/com/questloop/core/model/Domain.kt:276` · **LOW** · effort small · skeptic verdict: **partially correct** · P3 · proposal

UserProfile.totalXp is never written or read anywhere in main source (only LevelSystem/DAO totalXp identifiers match). It is a vestige of the pre-ledger model that docs/CODE_REVIEW.md H3 removed ("XP no longer in DataStore"; QuestRepository.kt:101 derives XP from the ledger). It still gets serialized as 0 into every export (QuestRepository.exportJson:703-708 embeds the full UserProfile), which can mislead someone reading a backup or a future maintainer into treating profile as an XP authority.

**Recommendation:** Remove the field (exports tolerate it via ignoreUnknownKeys on import), or comment it as deprecated/ledger-superseded if wire compatibility of old backups matters.

**Skeptic's correction:** UserProfile.totalXp (Domain.kt:276) is a dead pre-ledger vestige — never written (ProfileStore.kt:110-126 omits it) or read anywhere in main source. However, it is NOT "serialized as 0 into every export": QuestRepository's exportJson (line 70) uses kotlinx's default encodeDefaults=false, so the always-default 0 is elided (verified: UserProfile() encodes to {}). Backups are unaffected; only readers of the model code can be misled. Removing the field remains safe (import uses ignoreUnknownKeys=true, so even hypothetical pre-ledger backups containing the key still parse) and is worthwhile hygiene.

<sub>Skeptic: Grepped every totalXp use: the field is never read or written; ProfileStore builds UserProfile without it; import ignores it — dead, matching docs/CODE_REVIEW.md H3. But the export claim is false: exportJson leaves encodeDefaults=false, and an empirical test showed UserProfile() serializes to {} — totalXp never appears in backups. Removal is safe (ignoreUnknownKeys).</sub>

#### [16] Week/month interval math duplicated between QuestRepository and AppClock

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:285` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W19

intervalStartFor (285-292) reimplements the ISO week-start/month-start arithmetic that AppClock.startOfWeek/startOfMonth already provide (ui/Clock.kt:13-21), plus a third inline month-start at QuestRepository.kt:240. These two copies must agree forever: intervalStartFor produces persisted instanceId keys (questId@slot) while AppClock drives the Completed-history and Review windows that read those records back. A locale-aware week-start change applied to one copy but not the other would silently mis-bucket weekly quest progress.

**Recommendation:** Extract one epoch-day calendar helper into :core (beside QuestScheduler) and use it from both the repository and AppClock.

<sub>Skeptic: Verified all three copies: QuestRepository.kt:285-292 duplicates AppClock.startOfWeek/startOfMonth (Clock.kt:13-21) character-for-character; line 240 is a third month-start feeding allowance() alongside RewardsViewModel's AppClock.startOfMonth. Slot keys are persisted (lines 484-485); Review/Completed windows use AppClock. No shared :core helper, no docs, tests pin copies independently. Low severity is right.</sub>

#### [17] Near-duplicate completion/undo plumbing and chip-row helpers across screens

`app/src/main/java/com/questloop/app/ui/today/TodayViewModel.kt:154` · **LOW** · effort medium · skeptic verdict: **confirmed** · P3 · proposal

TodayViewModel.runCompletion (154-164) and QuestsViewModel.completeWithUndo (73-91) are the same guard+launch+finally+toast+PendingUndo pattern, with matching undoLast (184-191 vs 93-100) and consumeToast copies; toast/toastId/pendingUndo/completing fields repeat in both UiStates. In Compose, ChipGroup (AddQuestScreen.kt:451-461) and ChipRow (CompletedScreen.kt:233-243) are the same generic FlowRow+FilterChip helper, and prettyOf (AddQuestScreen.kt:463-467) re-derives the enum pretty-printing that components.pretty and AiNarrator.kt:171 also implement. Each copy is small, but the completion/undo pair carries the double-tap and one-shot-toast gotchas that must stay in sync.

**Recommendation:** Extract a shared completion-with-undo helper (delegate or extension taking the state flow) used by both ViewModels, and move one generic enum ChipGroup into ui/components.

<sub>Skeptic: Read both ViewModels: guard/launch/finally/toast/PendingUndo, undoLast, consumeToast, and the four UiState fields are duplicated exactly as cited (line ranges match). ChipGroup/ChipRow differ only in spacing and default label; prettyOf triplicates pretty-printing (plus a fourth copy in ReviewGenerator.kt:133). No doc marks it intentional; only nuance is :core copies can't reuse :app helpers.</sub>

### testing

#### [18] Releases publish an APK gated only by :core tests; the full suite runs after publication

`.github/workflows/release.yml:55` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W09

A `[release]` commit builds and publishes the rolling prerelease APK after running only `./gradlew :core:test` (release.yml:55-56) — no :app unit tests, no lint, no migration guard. full-tests.yml triggers on `release: [published]` (full-tests.yml:15-16), i.e. AFTER the APK is public. A failing app suite (nightly red, Robolectric break, crash-on-upgrade migration) can ship; AGENTS.md's 'run [uitest] before releasing' is manual discipline, not enforced.

**Recommendation:** Add `:app:testDebugUnitTest` (and ideally `lintDebug`) to release.yml before the publish step, or make the release job depend on a green full-tests run for the same SHA.

<sub>Skeptic: release.yml:55-56 runs only :core:test before publishing; no app unit/lint/migration steps (QuestLoopMigrationTest is androidTest-only, debug-signed path skips all lint). full-tests.yml:15-16 fires post-publish — and likely never, since GITHUB_TOKEN-created releases don't trigger workflows. AGENTS.md:133's pre-release [uitest] is unenforced. Downgraded: explicitly experimental debug-signed prerelease with nightly backstop.</sub>

#### [19] Per-push CI never compiles :app — smoke-only gate leaves a day-long blind spot (arguing the documented choice)

`.github/workflows/smoke.yml:16` · **LOW** · effort small · skeptic verdict: **documented tradeoff** · P3 · proposal

Documented as deliberate (AGENTS.md), but the trade-off is worse than it looks: smoke.yml runs only the :core suite, so an :app compile error, Robolectric failure, or lint break lands silently and surfaces up to ~24h later in nightly full-tests, after more commits pile on top (harder bisect). Recent history shows the dev compensating with manual `[uitest]` markers. A compile-only gate costs ~4-5 cached minutes and catches the most common breakage class.

**Recommendation:** Add a per-push job running `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` (no tests, no emulator) alongside the core job in smoke.yml; keep the full suite nightly/on-demand as designed.

<sub>Skeptic: smoke.yml runs only core-tests.yml; full-tests.yml gates all :app jobs off on plain pushes — facts accurate. But AGENTS.md explicitly documents this ("smoke ... does NOT build :app ... breakage only caught when full-tests runs"), with mitigations: local :app builds via setup-android.sh and the [uitest] trigger, used in 31 of the last 60 commits.</sub>

#### [20] SchemaExportTest claims per-push protection it does not have; migration discipline is unguarded at release time

`app/src/test/java/com/questloop/app/data/local/SchemaExportTest.kt:8` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W09

The comment says this guard 'runs in the normal CI job ... on every push, instead of only when someone remembers the [uitest] marker'. False: it lives in app/src/test, which runs only in full-tests (nightly/manual/release/[uitest]) — .claude/rules/testing.md confirms. Combined with the core-only release gate, a SCHEMA_VERSION bump without a Migration/exported schema can ship via `[release]` and crash existing installs on upgrade (destructive fallback covers only v1).

**Recommendation:** Fix the stale comment, and run this guard where it matters: add the single test class (or `:app:testDebugUnitTest`) to release.yml, or move the schema-file existence check into a :core test / gradle verification task.

<sub>Skeptic: Comment (lines 8-14) claims per-push coverage; test is in app/src/test, run only by full-tests.yml (manual/nightly/release-published/[uitest]) — smoke.yml runs :core only. release.yml gates on :core:test alone, and full-tests fires after release publish. SCHEMA_VERSION=3 with fallbackToDestructiveMigrationFrom(1): a bump without Migration crashes v2/v3 upgrades. No other guard found; comment predates the CI split.</sub>

#### [21] Notification 'Mark done' XP-crediting path has zero tests and is coverage-excluded on a contradicted rationale

`app/src/main/java/com/questloop/app/reminders/ReminderActionReceiver.kt:30` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · fix W29

ReminderActionReceiver holds real branching that grants XP: stamped-epoch-day fallback (line 30), planned-routine check, complete-vs-open-app (lines 44-54). It has no test and is excluded from coverage as a 'framework entry point that can't realistically be driven' (app/build.gradle.kts:131-134) — yet ReminderRearmTest.kt:48 already drives sibling ReminderReceiver.onReceive under Robolectric. The EXTRA_DAY stamp at ReminderNotifications.kt:49 (the 'late tap credits the right day' invariant from AGENTS.md) is also unasserted, so wrong-day-credit regressions are invisible to CI.

**Recommendation:** Add Robolectric tests firing ReminderActionReceiver with a stamped prior-day EXTRA_DAY: assert the completion lands on that day, the not-planned branch opens the app instead of crediting, and remove the coverage exclusion.

<sub>Skeptic: All cited lines exact: receiver's epoch-day fallback (30), planned-vs-open branch (44-54), exclusion (build.gradle.kts:134), sibling driven at ReminderRearmTest.kt:48, EXTRA_DAY stamp (ReminderNotifications.kt:49). Grep: zero unit/instrumented tests reference the receiver or EXTRA_DAY. Manifest app is QuestLoopApplication, so Robolectric already provides the container cast; only goAsync/IO-await add friction — rationale genuinely undercut.</sub>

#### [22] Documented double-tap / one-shot-event regressions have no pinning tests

`app/src/main/java/com/questloop/app/ui/completed/CompletedViewModel.kt:46` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W29

AGENTS.md records these as bugs learned the hard way: re-entrant completion duplicating Undo state/inserts ('Add all mints fresh UUIDs → real duplicates') and identical consecutive toasts swallowed unless keyed on a monotonic toastId. The guards exist (CompletedViewModel.kt:46-71 inFlight; TodayViewModel.kt:45,177 toastId) but no test in app/src/test references either — AddQuestViewModelTest:111 calls 'add all' exactly once. A refactor could silently reintroduce both bug classes.

**Recommendation:** Add re-entrancy tests (invoke complete/undo/add-all twice without yielding; assert one ledger record, one insert) and a Robolectric Compose test that two identical consecutive toasts each trigger the snackbar effect.

<sub>Skeptic: Verified guards exist as cited (CompletedViewModel:46 inFlight, TodayViewModel:45/177 toastId, plus completing/saving flags). Repo-wide grep of all test files: zero references to toastId/inFlight, no test invokes any guarded action twice, no snackbar Compose tests. AddQuestViewModelTest:111 calls acceptAll() once, as claimed. RewardsViewModelTest:105 and QuestRepositoryTest idempotency don't cover these; no documented tradeoff.</sub>

#### [23] AppClock is an uninjectable global; day-boundary ViewModel behavior is untestable and two tests depend on the wall clock

`app/src/main/java/com/questloop/app/ui/Clock.kt:8` · **LOW** · effort medium · skeptic verdict: **partially correct** · P2 · fix W37

The repository layer cleanly injects epochDay (well tested), but ViewModels read the singleton `AppClock.todayEpochDay()` (LocalDate.now, implicit zone). ReviewViewModelTest.kt:97 and CompletedViewModelTest.kt:87 therefore run against the real date (midnight-crossing flake risk), and midnight rollover in an open session, plus week/month filter edges (CompletedViewModel.kt:120-124, e.g. on the 1st), cannot be simulated in any test.

**Recommendation:** Make the clock injectable (constructor parameter defaulting to AppClock, or a settable `now` provider) and add fixed-date tests for filter boundaries and day rollover.

**Skeptic's correction:** AppClock (app/src/main/java/com/questloop/app/ui/Clock.kt:8) is an uninjectable singleton using LocalDate.now(); with no mocking library in the project, midnight rollover and the CompletedViewModel.kt:120-124 week/month filter edges cannot be simulated in tests. Both cited tests read the real clock, but only CompletedViewModelTest.kt:87 carries actual flake risk — its `today` is captured at test init while the ViewModel re-reads the clock under the default WEEK filter, so it fails only if midnight into Monday crosses mid-test. ReviewViewModelTest.kt:97 cannot flake: its assertions (assertNotNull on weekly/monthly/summaries, aiAvailable=false) hold regardless of whether the completion lands in the review window. Recommendation (injectable clock + fixed-date boundary tests) stands.

<sub>Skeptic: Verified Clock.kt:8 is an object; seven ViewModels call it; no mockk/mockito exists, so day-boundary behavior is truly untestable; repository injects epochDay with fixed-day tests. But of the two cited tests, only CompletedViewModelTest can flake (captured today vs fresh WEEK rangeFor, midnight-into-Monday only); ReviewViewModelTest's assertions are date-independent. Not documented as intentional.</sub>

#### [24] Reminder scheduling is tested only in UTC; DST transitions unexercised

`app/src/test/java/com/questloop/app/reminders/ReminderScheduleTest.kt:12` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W35

ReminderSchedule.nextTriggerMillis takes a ZoneId (ReminderSchedule.kt:16, defaults systemDefault) but all four tests pin ZoneOffset.UTC. Behavior across DST gaps/overlaps (e.g. an 02:30 reminder on spring-forward day, or the fall-back repeated hour) relies on unverified java.time resolution — a firing skipped or shifted by an hour twice a year would never fail CI.

**Recommendation:** Add cases with a DST zone (e.g. Europe/Berlin) around both transitions asserting the trigger is strictly future and lands on the expected local wall time.

<sub>Skeptic: Verified: ReminderSchedule.kt:16 defaults to ZoneId.systemDefault(); all four tests in ReminderScheduleTest.kt pass ZoneOffset.UTC; production caller ReminderScheduler.kt:38 omits the zone arg so device DST zones are the real path; repo-wide grep finds no DST-zone tests. Current impl is DST-correct but unasserted, so a naive-arithmetic regression would pass CI. Not documented as intentional; low severity fits the inexact-alarm design.</sub>

#### [25] Coverage badge publishes from failed nightly runs, silently misreporting the public number

`.github/workflows/full-tests.yml:185` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W09

The badge job's condition starts with `always()`, so a nightly where the emulator job failed early still publishes: with few or no .ec files the 'merged' report is unit-only and the badge drops to a wrong number (or reflects a red run as if valid). The 'best-effort, never fails the pipeline' comment covers not failing — not publishing from failure.

**Recommendation:** Replace `always()` with `success()` (keeping the event filter), or have the badge step skip when the instrumented test step's outcome was failure.

<sub>Skeptic: Line 185 uses `always()` with `needs: instrumented`, so the badge job runs after emulator failure. In-job `if: always()` steps then produce a unit-only jacoco XML (build.gradle.kts lines 146-148 include "whichever data is present"), which the badge script publishes as the public number. No doc (AGENTS.md, README, workflow comments) accepts this wrong-number hazard; comments only cover not-failing and dev-run clobbering.</sub>

### security

#### [26] Disconnect / Delete-all races with in-flight token refresh: credentials can silently resurrect

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:849` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W08

freshOpenAiTokens (849-860) reads the whole AiConfig, does a network refresh (OkHttp 20s/30s timeouts), then persists the FULL stale config via setAiConfig — apiKey, tokens, enabled, provider. disconnectOpenAi (832-835) and deleteAllData (970-974, profileStore.clear) never take aiAuthMutex. Attacker model: shared-device user taps Disconnect or 'Delete all data' while an AI call is refreshing; when the refresh lands, OAuth tokens (and the OpenRouter key) are re-persisted, defeating sign-out and the SPEC §9 deletion promise. Practically exploitable: window is the whole refresh call.

**Recommendation:** Acquire aiAuthMutex in disconnectOpenAi and deleteAllData; inside freshOpenAiTokens re-read the config just before persisting (abort if tokens were cleared), or persist only the token slot instead of the whole config.

<sub>Skeptic: Verified all claims: freshOpenAiTokens (849-860) reads full config, refreshes over network (20s/30s OkHttp timeouts in OpenAiAuthService), then setAiConfig re-persists apiKey+tokens+enabled+provider (ProfileStore.kt:236-249). aiAuthMutex used only at line 849; disconnectOpenAi and deleteAllData/profileStore.clear() are unsynchronized. No re-read before persist, no other mitigation, not documented as a tradeoff. Medium severity is fair.</sub>

#### [27] Backup-rule backstop is incomplete: Room WAL/SHM sidecars and ai_diagnostics.log not excluded

`app/src/main/res/xml/backup_rules.xml:7` · **LOW** · effort small · skeptic verdict: **confirmed** · P1 · fix W22

The rules exist explicitly as a backstop "if backup is ever re-enabled" (allowBackup=false today). But they exclude only the exact path questloop.db; Room's default WAL mode also writes questloop.db-wal/-shm, which would carry recent quests/habits/completions into cloud backup or d2d transfer. files/ai_diagnostics.log (AI error log) is also unexcluded. Same gap in data_extraction_rules.xml:6-15. Not exploitable today (backup disabled) — it only fails in exactly the scenario the files were written for.

**Recommendation:** Add excludes for questloop.db-wal and questloop.db-shm (all three rule sections) and for file path ai_diagnostics.log; add a comment tying the sidecars to the DB name.

<sub>Skeptic: Verified: both XML files exclude only exact path questloop.db; QuestLoopDatabase uses default journal mode (WAL), so -wal/-shm sidecars exist and Android's literal path excludes miss them, defeating the backstop's stated intent. ai_diagnostics.log (filesDir root) is unexcluded, though it records only model+redacted error, never quest text. allowBackup=false today, so low severity is accurate.</sub>

#### [28] Diagnostics redaction misses tokens rotated mid-call and can log provider-echoed user text

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:966` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W28

recordAiError redacts using the AiConfig captured BEFORE the AI call (suggestQuests:881→896, narrateReview:953→961). freshOpenAiTokens can rotate tokens during the call, so a fresh access token — the one actually sent as the Bearer header — is not in the redaction set (redactSecrets:993-999). Separately, error messages embed up to 300 chars of raw provider body (OpenRouterClient.kt:75, OpenAiResponsesCodec.parseError), which for moderation/validation errors can echo user todo/goal text, contradicting AiDiagnostics.kt:9-11 ("never the user's quest text — safe to send"). Theoretical today; defeats the layer's stated defense-in-depth purpose.

**Recommendation:** Re-read the current AiConfig inside recordAiError before redacting (covers rotated tokens); additionally redact any 'Bearer <...>'-shaped strings, and soften or enforce the 'never quest text' claim for provider bodies.

<sub>Skeptic: Verified every cited line: config snapshots (QuestRepository.kt:881/953) feed recordAiError:966 while freshOpenAiTokens:849-860 persists rotated tokens mid-call and OpenAiClient.kt:54 sends the fresh Bearer token; redactSecrets:993 scrubs only stale ones. Both parseError paths take 300 chars of raw body; prompts carry user todos/goals, contradicting AiDiagnostics.kt:9-11. Tests cover only static tokens; no doc marks this intentional. Theoretical, as stated — low fits.</sub>

#### [29] Credential store built on deprecated androidx.security-crypto (EncryptedSharedPreferences)

`gradle/libs.versions.toml:12` · **LOW** · effort medium · skeptic verdict: **confirmed** · P3 · proposal

AGENTS.md documents EncryptedSharedPreferences via Keystore as the deliberate credential store (SecureKeyStore.kt:66-79), and the failure semantics are good. I argue the dependency choice itself now carries risk: Google deprecated androidx.security:security-crypto (1.1.0 is the final release, maintenance-only), so crypto bugs in its Tink/Keystore glue will not receive upstream fixes for the app's most sensitive data (OpenRouter key, OAuth refresh token). No current exploit; supply-chain/maintenance risk only.

**Recommendation:** Plan a migration behind the existing SecureKeyStore interface — e.g. Keystore-backed AES/GCM cipher directly, or Tink with your own keyset management — keeping the read-fail-safe / write-fail-loud semantics and the questloop_secure backup exclusion.

<sub>Skeptic: libs.versions.toml:12 pins security-crypto 1.1.0; EncryptedKeyStore (SecureKeyStore.kt:66-79) uses EncryptedSharedPreferences/MasterKey for the OpenRouter key and OAuth tokens. Official androidx release notes confirm 1.1.0 (Jul 2025) is the final release, all APIs deprecated. AGENTS.md documents the store choice but never the deprecation tradeoff. Interface abstraction and questloop_secure backup exclusion exist, so the migration recommendation is actionable. Low severity is apt.</sub>

#### [30] OpenRouter API key field lets the IME learn/suggest the key

`app/src/main/java/com/questloop/app/ui/settings/SettingsScreen.kt:427` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W24

The API-key OutlinedTextField (427-436) uses PasswordVisualTransformation (masked display, and the saved key round-trips back into it) but sets no KeyboardOptions, so the IME treats it as normal text: autocorrect, personalized suggestions, and third-party keyboard learning/cloud sync can retain the secret. Attacker model: a data-hungry or compromised third-party keyboard, or the key resurfacing in suggestions on a shared device. Real but bounded; users typically paste rather than type keys.

**Recommendation:** Add keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) to the key field so IMEs apply no-learning password treatment.

<sub>Skeptic: SettingsScreen.kt:427-436 confirmed: PasswordVisualTransformation, no keyboardOptions; saved key round-trips from SecureKeyStore via ProfileStore into the field. Verified via compiled Compose classes that ImeOptions/EditorInfo derive only from KeyboardOptions, never visualTransformation, so the IME sees learnable plain text. No mitigation or documented tradeoff found; recommendation's autoCorrectEnabled param exists. Low severity is apt.</sub>

#### [31] OAuth loopback: local apps can abort sign-in via error param (checked before state), and every request gets the 'You're signed in' page

`app/src/main/java/com/questloop/app/data/OpenAiAuthService.kt:73` · **LOW** · effort small · skeptic verdict: **partially correct** · P2 · fix W31

The when-branch order checks callback.error before validating state (73-76), so any co-installed app hitting 127.0.0.1:1455 with ?error=x aborts a genuine in-flight sign-in (local DoS); the callback path is never compared to /auth/callback (154); and acceptOnce unconditionally serves the SUCCESS_PAGE (156-159), so a declined or forged callback still shows 'You're signed in' in the browser. Token theft is NOT possible — PKCE verifier never leaves the app, state is random and checked, bind precedes openUrl. Nuisance/confusion only.

**Recommendation:** Validate state before honoring error, ignore requests whose path isn't /auth/callback, and serve a neutral 'return to QuestLoop' page (or an error page) for non-success callbacks.

**Skeptic's correction:** Facts confirmed: error checked before state (OpenAiAuthService.kt:73-76), request path never compared to /auth/callback (:154, core's CALLBACK_PATH unused there), every request gets the "You're signed in" page (:156-159), and token theft is blocked (verifier stays local, 128-bit SecureRandom state checked, bind precedes openUrl). However, the local DoS does not stem from branch order: awaitCallback (:124) returns any request carrying code or error, and every non-success branch throws, so a forged ?code=x&state=wrong aborts just like ?error=x; merely validating state first only changes the error message. The effective fix is to treat state-mismatched or wrong-path callbacks as strays and keep listening (as :124-125 already does for param-less requests), plus a neutral/non-success page — while noting a hostile local app could still pre-bind port 1455, so loopback OAuth cannot fully eliminate local DoS. Severity low stands.

<sub>Skeptic: Read OpenAiAuthService.kt, core OpenAiOAuth, tests, AGENTS.md. All cited facts hold: error-before-state (73-76), no path check (154), unconditional SUCCESS_PAGE (156-159), token theft impossible. But the DoS is misattributed to branch order: state mismatch also throws, so any forged code/error callback aborts regardless; reordering alone (the stated fix) wouldn't prevent it.</sub>

#### [32] BootReceiver hand-wires ProfileStore with the plaintext test-default keystore

`app/src/main/java/com/questloop/app/reminders/BootReceiver.kt:18` · **LOW** · effort small · skeptic verdict: **confirmed** · P1 · fix W12

BootReceiver builds ProfileStore(context) without a keyStore, so it gets the plaintext DataStoreKeyStore default (ProfileStore.kt:70), diverging from AppContainer's EncryptedKeyStore wiring. Harmless today (only getReminderConfig is called), but if boot-path code ever calls getAiConfig, the legacy-key migration (ProfileStore.kt:204-216) would move the API key into plaintext DataStore under ai_api_key_v2 and scrub the legacy slot — silently downgrading the credential and hiding it from the encrypted store. ReminderActionReceiver.kt:36-38 already documents reusing the container as the correct pattern.

**Recommendation:** Reuse (context.applicationContext as QuestLoopApplication).container's ProfileStore/repository in BootReceiver, or make the production constructor require an explicit SecureKeyStore so the plaintext default is test-only.

<sub>Skeptic: Verified BootReceiver.kt:18 hand-wires ProfileStore without keyStore; ProfileStore.kt:70 defaults to plaintext DataStoreKeyStore (ai_api_key_v2), while AppContainer uses EncryptedKeyStore. getReminderConfig never touches keyStore (harmless today, as stated); getAiConfig's legacy migration (202-216) would indeed plaintext-downgrade and orphan the key. ReminderActionReceiver:36-38 documents container reuse. Not documented as deliberate; low severity fits.</sub>

### performance

#### [33] Calendar ContentResolver queries run on the main thread

`app/src/main/java/com/questloop/app/data/AndroidCalendarReader.kt:67` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W07

queryBusy (line 67) and queryEvents (line 101) call CalendarContract.Instances.query — blocking cross-process IPC — directly in suspend functions with no dispatcher hop. Callers run on viewModelScope (Main.immediate via launchSafely): TodayViewModel.refresh -> QuestRepository.todayPlan:211 on every Today entry/completion/check-in (when calendar budgeting is opted in), and AddQuestViewModel.loadCalendarEvents for the deadline picker. Provider binding plus a busy calendar can block Main for tens to hundreds of ms — visible dropped frames on a mid-range phone.

**Recommendation:** Wrap freeMinutesToday and upcomingEvents bodies in withContext(Dispatchers.IO) inside AndroidCalendarReader, mirroring what exportJson/importJson already do.

<sub>Skeptic: Verified lines 67/101 call CalendarContract.Instances.query with no withContext in AndroidCalendarReader; launchSafely is bare viewModelScope.launch (Main.immediate); both cited chains (TodayViewModel.refresh→todayPlan:211, AddQuestViewModel.loadCalendarEvents→upcomingCalendarEvents:656) have no dispatcher hop; AppContainer wires the reader undecorated; exportJson/importJson do use Dispatchers.IO; no docs mark this intentional.</sub>

#### [34] N+1 per-quest find() loops re-executed 2-3x per screen refresh

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:316` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · fix W21

dismissedQuestIdsToday (line 316) and todayProgress (line 339) each issue one completionDao.find() per candidate quest, and each first rebuilds candidateQuestsById (profile decode + getActive, lines 251-255). questOverview (128-150) runs the dismissed loop twice (once inside todayPlan, once itself) plus todayProgress, plus lastCompletedDays and getActive again — roughly 3N point queries and 4 DataStore profile decodes per Quests-screen recompute; TodayViewModel.refresh does ~2N plus three activeDays() scans. Refresh latency grows linearly with quest count after every completion tap.

**Recommendation:** Fetch all current-interval records in one query (WHERE instanceId IN (...) or epochDay >= interval start) and derive dismissed/progress maps from it; compute candidateQuestsById once per operation and pass it through (questOverview should reuse the plan's dismissed set).

<sub>Skeptic: Traced every cited path. dismissedQuestIdsToday/todayProgress each call completionDao.find() per candidate and rebuild candidateQuestsById (profile JSON decode + getActive). questOverview: dismissed loop twice, 2N+M finds, 4 profile decodes, duplicate lastCompletedDays/getActive; TodayViewModel.refresh: ~2N finds plus exactly three activeDays() scans; re-run per completion tap. No caching, bulk query, or documented tradeoff. Off-main-thread PK lookups keep severity at medium.</sub>

#### [35] Widget freshness trigger re-reads and re-maps the entire completion ledger on every write

`app/src/main/java/com/questloop/app/QuestLoopApplication.kt:46` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W32

keepWidgetFresh collects repo.completions — completionDao.observeAll() mapped to models with distinctUntilChanged (QuestRepository.kt:91-92) — solely as a Unit change signal, for the whole process lifetime. Every completion/import write re-runs the full-table query, maps O(history) entities, and does an O(history) list comparison, even when no widget is placed. Each actual update then re-runs the entire todayPlan pipeline including the N+1 loop (QuestWidget.kt:36-40). Cost grows unbounded with usage history.

**Recommendation:** Trigger on cheap signals instead: observeTotalXp() plus a SELECT COUNT(*)/MAX(rowid) flow (or Room InvalidationTracker), and skip collection/update when GlanceAppWidgetManager reports no widget ids. Keep the existing 500ms debounce.

<sub>Skeptic: Verified: QuestLoopApplication.kt:46 collects repo.completions (full-table observeAll, O(history) map + distinctUntilChanged compare, QuestRepository.kt:91-92) process-lifetime as a Unit signal; no GlanceAppWidgetManager gating anywhere; todayPlan runs the N+1 find loop (dismissedQuestIdsToday, QuestRepository.kt:310-323). Not documented as a tradeoff. But cost is background-thread milliseconds at realistic ledger sizes, per user action, debounced — so low, not medium.</sub>

#### [36] Completed screen all-time filter loads, maps, and sorts the whole ledger in memory on Main

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:377` · **LOW** · effort small · skeptic verdict: **partially correct** · P2 · fix W33

completedHistory with a null range calls completionDao.all(), then maps every entity to a model, filters, and sortedByDescending in memory (lines 387-395) — on the caller's Main dispatcher (CompletedViewModel.load via launchSafely). exportJson explicitly moves comparable full-ledger work to Dispatchers.IO (line 700-711), but this path doesn't. With a couple of years of history (~5k+ rows) each load/reload (filter change, undo, edit) does this on the UI thread.

**Recommendation:** Add ORDER BY epochDay DESC and the result='COMPLETED' filter to the DAO query, and run the mapping in withContext(Dispatchers.Default). Consider capping all-time results or paging.

**Skeptic's correction:** completedHistory(null) does call the result-unfiltered, ASC-ordered completionDao.all() and runs map/filter/sortedByDescending over the full ledger on Main.immediate on every load/reload (exportJson shifts to IO; this path doesn't). However, Room suspend DAOs are main-safe: the query and entity materialization happen on Room's executor, so only the in-memory transform of light records (~few ms at 5k rows) runs on Main — CPU cost, not disk I/O. The DAO-side filter/ORDER BY DESC + withContext(Default) recommendation stands; note all()'s doc comment "used only for data export" is already stale.

<sub>Skeptic: Verified QuestRepository.kt:376-396, Daos.kt:37-49, CompletedViewModel + launchSafely (bare viewModelScope.launch = Main.immediate), exportJson:700 on IO, no ledger pruning. In-memory map/filter/sort on Main per reload: confirmed. But all() is a Room suspend query — main-safe, DB I/O runs on Room's executor — so only lightweight CPU transforms (~ms at 5k rows) hit Main, not the load itself.</sub>

#### [37] completions table has no secondary indexes; all windowed/aggregate queries are full scans

`app/src/main/java/com/questloop/app/data/local/Entities.kt:38` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W25

CompletionEntity declares only the instanceId primary key. between/since (epochDay), observeAll ORDER BY epochDay, lastCompletedDays (GROUP BY questId), activeDays and the four COUNT queries (result/category filters) all full-scan the table. Each Today refresh runs roughly ten such scans (Daos.kt:37-93), and the table grows without bound (several thousand rows/year). Individually cheap now, but it compounds with the refresh frequency and the widget trigger above on long-lived installs.

**Recommendation:** Add @Entity(indices = [Index("epochDay"), Index("questId")]) with a v4 migration (+ exported schema, per the [schema] workflow). Optionally an index on result for the count/activeDays queries.

<sub>Skeptic: CompletionEntity (Entities.kt:38) has only the instanceId PK, no indices. Daos.kt:37-93 queries filter/group on epochDay/questId/result/category, none PK-servable. Traced TodayViewModel.refresh(): 11-12 full scans (lastCompletedDays, since x2, activeDays x3, totalXp x2, 4 COUNTs) — "roughly ten" holds. SCHEMA_VERSION=3, so v4 migration is correct; no documented no-index tradeoff. Low severity is apt.</sub>

#### [38] Release build enables R8 minify but not resource shrinking

`app/build.gradle.kts:63` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · proposal

buildTypes.release sets isMinifyEnabled = true with the optimize proguard profile, but isShrinkResources is not set (default false). Unused drawables/layouts/strings pulled in by Compose/Material3/Glance dependencies ship in the APK. Pure APK-size waste — relevant since the rolling prerelease APK is the distribution channel — with no runtime cost.

**Recommendation:** Add isShrinkResources = true to the release block (requires minify, already on) and verify via the [release] build plus a quick smoke of the minified APK.

<sub>Skeptic: Read app/build.gradle.kts release block (lines 62-72): isMinifyEnabled=true with optimize profile, isShrinkResources absent; grep finds no shrinkResources anywhere. No AGENTS.md/docs mention it as a tradeoff. release.yml publishes assembleRelease as the rolling prerelease (RELEASE_SIGNING.md: current builds release-signed), so shrinking affects the shipped APK. Recommendation valid; low severity appropriate.</sub>

### ux-content

#### [39] Privacy copy claims "Nothing is uploaded" while opt-in AI sends quest text off-device

`app/src/main/java/com/questloop/app/ui/settings/SettingsScreen.kt:285` · **HIGH** · effort small · skeptic verdict: **confirmed** · P0 · fix W02

Settings' Privacy card states "Your data stays on this device / Nothing is uploaded." unconditionally, and onboarding says "Everything stays on-device." (OnboardingScreen.kt:52). But with AI enabled, suggestQuests/decomposeGoal send the user's todo text and goal titles to OpenRouter/OpenAI (QuestRepository.kt:879-928). No UI text discloses this; the AI section only says the key/login stays on-device. For a privacy-forward app, the claim becomes materially false once AI is on.

**Recommendation:** Qualify the claim ("Stays on this device — unless you turn on AI, then only the text you submit goes to your chosen provider") and add one plain disclosure line to the AI settings section. Update SAFETY_AND_PRIVACY.md:45-46 to match.

<sub>Skeptic: Verified verbatim: SettingsScreen.kt:284-285 unconditional "Nothing is uploaded."; OnboardingScreen.kt:52 "Everything stays on-device." QuestRepository.kt:879-928 sends todos, goal text, focus areas, plus existing quest titles to OpenRouter/OpenAI when enabled. Grepped all UI copy — no transmission disclosure anywhere (AI section and AddQuestScreen silent). SAFETY_AND_PRIVACY.md:45-46 repeats the false claim, so not a documented tradeoff.</sub>

#### [40] "Skip" is penalized as "Missed" with only a ~4-second recovery window

`app/src/main/java/com/questloop/app/ui/components/QuestControls.kt:53` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W38

The button says "Skip" but records SKIPPED, and the snackbar reads "Missed — a gentle -3 XP" (RewardEngine.kt:214) — a label/outcome mismatch. Undo lives only in a SnackbarDuration.Short (~4s) snackbar (TodayScreen.kt:88-93; QuestsScreen.kt:56-64); afterwards there is no recovery, since Completed history lists only COMPLETED records (QuestRepository.kt:388). The economy's free RESCHEDULED outcome has no UI affordance (known: CURRENT_STATE.md lists rescheduling UI as unbuilt), so the only "not today" action is penalized.

**Recommendation:** Use SnackbarDuration.Long for undoable snackbars, align copy with the button ("Skipped — ..."), and add a neutral "Not today" (RESCHEDULED) action or include skips in Completed history for late undo.

<sub>Skeptic: Verified: "Skip" (QuestControls.kt:53) records SKIPPED, scored via scoreMissOrRelapse as "Missed — a gentle -3 XP" (RewardEngine.kt:97,214) and shown verbatim in Today's snackbar; both screens use SnackbarDuration.Short and timeout clears pendingUndo; skipped quests leave Today+backlog (dismissedForToday) and history filters COMPLETED (QuestRepository.kt:388); RESCHEDULED unused in UI; docs document the penalty but not the mismatch/short undo.</sub>

#### [41] Raw transport exception text shown on the Add screen for AI failures

`core/src/main/kotlin/com/questloop/core/ai/AiQuestService.kt:72` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W27

On a failed AI call the UI message becomes "AI request failed: ${exception.message}" (also lines 96, 136), rendered verbatim by AddQuestScreen.kt:219-221 via AddQuestViewModel.kt:150. AGENTS.md deliberately surfaces provider error bodies — OpenRouterClient/OpenAiClient curate those — but offline/timeout failures bypass the curation: users see platform strings like `Unable to resolve host "openrouter.ai": No address associated with hostname` or bare `timeout`. That's developer-facing text (CONTENT_STYLE hard rule 1) on the most common failure path, offline.

**Recommendation:** Keep curated provider messages; map other IOExceptions (UnknownHost, timeout) to plain copy — "Couldn't reach the AI. Check your connection and try again." — and keep the raw detail in the shareable AI error log.

<sub>Skeptic: Verified the full chain: AiQuestService.kt:72/96/136 interpolate raw exception.message; QuestRepository passes error through untouched; AddQuestViewModel.kt:150 and AddQuestScreen.kt:219-221 render it verbatim. Both clients curate HTTP error bodies only — OpenAiClient rethrows caught IOExceptions unchanged — so UnknownHost/timeout strings reach users. AGENTS.md covers provider bodies, not transport; diagnostics log exists for raw detail.</sub>

#### [42] Add/edit forms leak internal vocabulary: "Binary", "Meta maintenance", "Recurring" vs "Daily"

`app/src/main/java/com/questloop/app/ui/add/AddQuestScreen.kt:96` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W39

"How is it completed?" chips render enum names via the generic prettyOf (lines 463-467): "Binary / Quantitative / Duration / Subjective" — jargon CONTENT_STYLE bans. Category chips use the full QuestCategory.entries (line 82), offering "Meta maintenance" (internal, XP-capped; Settings and Habits filter it via isMeta). Frequency chips offer both "Daily" and "Recurring" although QuestScheduler treats them identically (QuestScheduler.kt:65), plus "Seasonal". The same chips appear in SuggestionCard (lines 382-391) and the Completed edit dialog (CompletedScreen.kt:220-227).

**Recommendation:** Use plain labels ("Done or not", "Count", "Time", "Rate 1-5") and hide META_MAINTENANCE, RECURRING, and SEASONAL from user-facing pickers; the tolerant enum parsing can keep accepting them internally.

<sub>Skeptic: Every citation checks out: AddQuestScreen 96/463-467 renders CompletionStyle enum names ("Binary/Quantitative/Duration/Subjective"); line 82 offers META_MAINTENANCE while SettingsScreen:574 and HabitsScreen:146,173 filter isMeta; QuestScheduler:65 treats DAILY/RECURRING identically; chips recur in SuggestionCard 382-391 and CompletedScreen 225/227. CONTENT_STYLE.md bans developer language; no doc marks this intentional; no friendly-label mapping exists.</sub>

#### [43] Reminder "Mark done" fallback silently dead-ends on Android 12+ (notification trampoline)

`app/src/main/java/com/questloop/app/reminders/ReminderActionReceiver.kt:51` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P1 · fix W11

When the tapped slot's routine isn't in today's plan (commonly: already completed in-app, or dismissed), the receiver falls back to context.startActivity (lines 51-54). A broadcast receiver fired from a notification action is a notification trampoline; Android 12+ blocks the activity start for targetSdk 31+ (this app targets 36), so the tap does nothing visible — the notification was already cancelled at line 40. A completeQuest failure is likewise swallowed after the cancel, making failure look like success.

**Recommendation:** Have the fallback post/update a notification whose tap is an Activity PendingIntent (allowed), or decide completability before attaching the action; cancel the original notification only after the completion succeeds.

<sub>Skeptic: Verified: "Mark done" is a getBroadcast notification action (ReminderNotifications.kt:50-62); receiver fallback calls startActivity (lines 51-53) — a notification trampoline, blocked silently on Android 12+ with targetSdk 36 (build.gradle.kts:31). Notification cancelled at line 40 before completion/fallback; runCatching discards failures. Fallback branch reachable (todayPlan drops completed/dismissed routines). No tests, no documented tradeoff.</sub>

#### [44] Completion controls are glyph-only for TalkBack (known UX_REVIEW L6, still unfixed)

`app/src/main/java/com/questloop/app/ui/components/QuestControls.kt:62` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W26

Counter buttons announce bare "−"/"+" (lines 62-64), duration steppers "−5"/"+5" (77-79), and subjective ratings bare "1".."5" (89-92), with no contentDescription tying them to an action, unit, or quest; Settings' reminder HourRow repeats the pattern (SettingsScreen.kt:354-356). With several quests on screen a TalkBack user hears indistinguishable "plus, button" rows. UX_REVIEW L6 flagged exactly this and it remains unfixed, while nav/delete/dot/pip components are properly labeled.

**Recommendation:** Add semantics contentDescriptions such as "Add one glass", "Remove 5 minutes", "Rate 3 of 5", "Later morning hour"; where controls repeat per quest row, include the quest title.

<sub>Skeptic: Read QuestControls.kt: bare Text("−")/("+") at 62/64, ("−5")/("+5") at 77/79, ("$rating") at 90-92, zero semantics; SettingsScreen.kt HourRow 354-356 same pattern. Call sites (TodayScreen:401, QuestsScreen:174) add no semantics wrapper. docs/UX_REVIEW.md:100-102 (L6) documents this as a recommended fix, not a tradeoff. Nav/delete/dot/pip labeling contrast verified.</sub>

#### [45] Failures leave screens stuck: busy/loading flags never reset and no screen has an error state

`app/src/main/java/com/questloop/app/ui/add/AddQuestViewModel.kt:141` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W05

launchSafely's default onError only logs. generate/decomposeGoal set generating=true (lines 141, 168) and acceptSuggestion/acceptAll set saving=true (lines 222, 241) with no try/finally: if the repository throws, the spinner spins and Add buttons stay disabled until the screen is recreated, with no message. TodayViewModel.refresh (TodayViewModel.kt:63-115) similarly leaves a first-load failure on an endless spinner. Contrast runCompletion (TodayViewModel.kt:154-164) and connectOpenAi (SettingsViewModel.kt:152-156), which reset flags in finally.

**Recommendation:** Wrap flag-guarded work in try/finally like runCompletion, and on error clear loading and emit a plain user message ("Something went wrong — try again.") via the existing message/toast channels.

<sub>Skeptic: Verified: launchSafely's default onError only logs; generating/saving/loading set at cited lines with no finally, reset only on success. UI gates on these flags (AddQuestScreen 184-236, TodayScreen 117). Contrast cases (runCompletion, connectOpenAi finally) accurate; connectOpenAi's comment and docs/SAFETY_AND_PRIVACY.md treat stuck UI as a bug, not a tradeoff. refineSuggestion/loadCalendarEvents share the flaw.</sub>

#### [46] Add screen still leads with the long manual form (known UX_REVIEW M4, still unfixed)

`app/src/main/java/com/questloop/app/ui/add/AddQuestScreen.kt:73` · **LOW** · effort medium · skeptic verdict: **confirmed** · P3 · proposal

The screen opens with title + five chip groups + minutes + deadline (lines 73-162) before the fast capture paths — "Break down a goal" and "Quick add with AI" — at lines 169-217. UX_REVIEW M4 recommended leading with quick capture for a minimal-effort product. DESIGN_DIRECTION defers full "conversational capture" to Phase 4, but the cheap reorder it doesn't cover was never done. Known, still unfixed.

**Recommendation:** Move quick capture (and goal breakdown) above the manual form, or collapse the manual form behind an "Add details" expander.

<sub>Skeptic: Verified AddQuestScreen.kt: manual form (lines 73-162, five chip groups) precedes goal-breakdown (169) and AI quick-add (195). UX_REVIEW.md M4 (67-72) recommends leading with quick capture and remains listed as pending. The only "Add-screen reorder" commit (89891b0) just swapped the two AI sections. DESIGN_DIRECTION defers only the larger conversational-capture feature; no doc defends form-first ordering.</sub>

#### [47] Stale docs: CURRENT_STATE and SAFETY_AND_PRIVACY no longer match the shipped UI

`docs/CURRENT_STATE.md:67` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · fix W40

CURRENT_STATE.md:67 describes AI as OpenRouter-only ("user provides a key + model in Settings"), omitting the shipped OpenAI/ChatGPT OAuth provider. CURRENT_STATE.md:49-50 claims onboarding covers "the money/rewards disclaimer", but OnboardingScreen shows none (its own comment says it lives on the Rewards tab). Onboarding's "Add a key in Settings" (OnboardingScreen.kt:56) is likewise key-only and stale for the sign-in provider. SAFETY_AND_PRIVACY.md:45-46 asserts "nothing is uploaded" with no AI caveat.

**Recommendation:** Refresh both docs for the two-provider AI reality and actual onboarding scope, and reword the onboarding AI card ("Turn it on in Settings" rather than "Add a key").

<sub>Skeptic: Verified all four sub-claims. CURRENT_STATE.md:67 says OpenRouter-only while AiConfig/SettingsScreen ship an OpenAI ChatGPT OAuth provider (since commit 25cd8ec). CURRENT_STATE.md:49-51 claims onboarding covers the money/rewards disclaimer; OnboardingScreen shows none and its KDoc says it lives on the Rewards tab. OnboardingScreen.kt:56 is key-only copy. SAFETY_AND_PRIVACY.md:45-46 says "nothing is uploaded" though enabled AI sends goals/quests to the provider; the nearby AI-transparency bullet never caveats upload.</sub>

#### [48] All UI copy is hardcoded in Kotlin; strings.xml holds only app_name

`app/src/main/res/values/strings.xml:2` · **LOW** · effort medium · skeptic verdict: **confirmed** · P3 · proposal

Every user-facing string — screens, dialogs, snackbars, even notification copy (ReminderNotifications.kt:16-18) and widget text (QuestWidget.kt:58) — is a Kotlin literal; resources contain only app_name. There is no localization path, and CONTENT_STYLE compliance has no single place to audit copy. Bounded for an experimental single-locale app, but the debt grows with every screen.

**Recommendation:** Extract copy to string resources (or first a central copy object per screen) as surfaces stabilize; prioritize notification and widget text, which the OS renders outside the app's context.

<sub>Skeptic: strings.xml holds only app_name; no other locale/values string files exist. Grep found zero stringResource/R.string usages in app Kotlin, 137 hardcoded Text() literals across 13 UI files, and both citations are exact (ReminderNotifications.kt:16-18 enum copy, QuestWidget.kt:58 widget text). No central copy object; AGENTS.md/docs cover voice only, never document hardcoding as a tradeoff. Low severity is apt.</sub>

### features

#### [49] Completed daily routines are never dismissed - they reappear in Today's plan all day (regression)

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:312` · **HIGH** · effort small · skeptic verdict: **confirmed** · P0 · fix W01

Commit 3d2d31e rewrote dismissedQuestIdsToday to iterate candidateQuestsById() (stored + habit-derived only). Routine quests (RoutineQuestFactory) and admin-fund steps are not in that map, so their completion records are never checked. QuestGenerator gates routines solely on dismissedToday (QuestGenerator.kt:129), so completing the morning/evening routine puts it right back in the plan with live buttons; it can be re-completed repeatedly (anti-farm-decayed XP). RoutineQuestFactory.kt:21 documents the intended behavior. Skipped admin-fund steps are likewise not hidden for the day.

**Recommendation:** Include RoutineQuestFactory.all() and AdminFundFactory quests in the dismissal scan (or restore the old scan of today's completion records with a BINARY-style fallback). Add a repository test: completed routine absent from same-day plan.

<sub>Skeptic: Verified at HEAD: dismissedQuestIdsToday iterates candidateQuestsById (stored+habit-derived only); 3d2d31e removed the old all-records scan with BINARY fallback that caught routines. QuestGenerator.kt:129 gates routines solely on dismissedToday; TodayViewModel refresh() re-adds them post-completion; no test covers this. Skipped admin steps also reappear (lastCompletedDays only counts COMPLETED). Minor nuance: re-completion is XP-neutral (idempotent instanceId), so no farming — UX regression only.</sub>

#### [50] Over-completion is unusable for QUANTITATIVE quests - stepper hard-caps at target, quest then lingers stuck

`app/src/main/java/com/questloop/app/ui/components/QuestControls.kt:64` · **HIGH** · effort small · skeptic verdict: **confirmed** · P0 · fix W03

QuestCompletionControls never reads quest.allowOverCompletion: the quantitative counter clamps at target (`if (count < target) count++`, and resume `progress.coerceIn(0, target)` at line 59). Yet CompletionPolicy (CompletionScaling.kt:77-81) keeps such quests visible for the whole week/month so the user can log 3/2, 4/2. Result: after hitting 2/2 the quest sits on Today/Quests all interval, unable to log more or leave the list. Domain.kt:160-165's headline example (3rd swim on swim 2x/week) is exactly this case. DURATION over-logging works, so siblings are inconsistent.

**Recommendation:** When allowOverCompletion, let the counter exceed target (cap at MAX_OVER_FRACTION x target) and show 'n / target (+extra)'. Cover with a UI/state test.

<sub>Skeptic: Verified every link: QuestControls.kt:59/64 clamp count to target and never read allowOverCompletion; both call sites (Today/Quests) use it, no other measured-logging path; completeMeasured is monotonic so relogging can't help; CompletionPolicy keeps the COMPLETED quest visible all interval; DURATION over-logs fine. Commit 3d2d31e claims "the UI shows 3/2", and AddQuestScreen's toggle copy promises the 3rd-swim case — so a shipped-but-broken feature, not a deferral.</sub>

#### [51] Habit-derived quests cannot meet their own weekly target (only 1 completion creditable per week)

`core/src/main/kotlin/com/questloop/core/generation/HabitQuestFactory.kt:30` · **LOW** · effort medium · skeptic verdict: **partially correct** · P1 · fix W10

fromHabit maps targetPerWeek < 5 to a WEEKLY BINARY quest. QuestScheduler then hides it for 7 rolling days after one completion, so a 'swim 2-4x/week' habit can only ever log one occurrence per week; a second same-week completion is impossible. HabitsScreen advertises 'Nx/week' (HabitsScreen.kt:58) and the quest rationale says 'aim for Nx a week'. The recently shipped weekly quantitative accumulation exists precisely for this but is not used for habits - user-created weekly measured quests and habit-derived ones behave differently.

**Recommendation:** Derive habits with 2-4 targetPerWeek as WEEKLY QUANTITATIVE quests (targetCount = targetPerWeek, unit 'times'), so interval accumulation counts each occurrence toward the weekly target.

**Skeptic's correction:** Habit-derived quests with targetPerWeek 2-4 would be WEEKLY BINARY and creditable only once per rolling week, contradicting the advertised "Nx/week" — the code path, asymmetry with user-created weekly quantitative quests, and recommended fix are all accurate. However, AddHabitForm hardcodes targetPerWeek=7, so no in-app habit can currently hit this branch; it is a latent defect reachable only via imported/hand-edited backups (or once a target picker ships).

<sub>Skeptic: Verified the mechanism end-to-end: fromHabit yields WEEKLY BINARY, hasCalendarInterval excludes BINARY, so isDue hides it 7 rolling days after one completion; no other completion surface exists. But the only habit-creation UI (HabitsScreen.kt:151) hardcodes targetPerWeek=7 (always DAILY), so the 2-4x branch is reachable only via hand-edited imported backups — latent, not user-facing today.</sub>

#### [52] One-off measured quests reset progress daily, can never complete by accumulation, and over-mint XP across days

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:283` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P0 · fix W04

completionSlot day-keys ONE_OFF measured quests (only WEEKLY/MONTHLY accumulate). A one-off 'read 100 pages' logged 30/day creates a fresh record each day: UI progress resets to 0, the quest never reaches COMPLETED (stays due forever per isDue ONE_OFF), and each day's partial is scored independently with no netting - 4 days x 30 pages grants ~1.2x the quest's full XP. ONE_OFF is the Add screen's default frequency (AddQuestViewModel.kt:45). The code comment (QuestRepository.kt:264-269) calls per-day semantics deliberate; I argue that choice is wrong for ONE_OFF, where the target is inherently cumulative.

**Recommendation:** Key ONE_OFF measured quests to a stable slot (e.g. questId@oneoff) so progress accumulates until the target is reached once, then completes and leaves via lastCompleted.

<sub>Skeptic: Verified: completionSlot day-keys ONE_OFF measured quests; lastCompletedDays() counts only COMPLETED so partials never satisfy isDue(ONE_OFF); XP nets per-instanceId and anti-farm is same-day only, so 4x0.3-fraction days mint ~1.2x base. AddQuestViewModel:45 defaults ONE_OFF. Not documented in AGENTS.md/docs; commit 3d2d31e fixed this same bug for WEEKLY/MONTHLY only.</sub>

#### [53] Terminal PARTIAL completions (subjective ratings 1-4, end-of-interval partial logs) are invisible in Completed history and un-undoable

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:388` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · proposal

completedHistory filters result == COMPLETED, on the rationale 'partials are in-progress'. But a subjective check-in rated 1-4/5 is PARTIAL yet terminal for the day (CompletionPolicy dismisses it), earns XP, and goal quests default to SUBJECTIVE (HabitQuestFactory.kt:61). Once the Today snackbar times out (pendingUndo cleared), these records appear nowhere and can never be undone or edited; same for a week's partial measured progress after the interval ends. XP totals then include grants with no visible ledger entry - an inconsistency users hit weekly.

**Recommendation:** Include terminal partials in history (filter via CompletionPolicy.dismissedForToday or fraction > 0), showing 'partial - n%' labels; keep truly in-progress measured partials of the current interval excluded.

<sub>Skeptic: Verified: completedHistory filters result==COMPLETED (QuestRepository.kt:388); subjective 1-4 → PARTIAL (CompletionScaling.subjective/classify) yet dismissedForToday==true; RewardEngine scores PARTIAL positive XP; goals are SUBJECTIVE (HabitQuestFactory.kt:61); deleteCompletion/editQuestAndRescore only reachable via CompletedViewModel; pendingUndo cleared on toast consume. Not documented as tradeoff (docs/CODE_REVIEW.md, AGENTS.md silent); KDoc rationale contradicts CompletionPolicy.</sub>

#### [54] Widget shows yesterday's plan after midnight (and the wrong day-part list) until the app is opened

`app/src/main/java/com/questloop/app/QuestLoopApplication.kt:44` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W14

quest_widget_info.xml sets updatePeriodMillis=0 and keepWidgetFresh refreshes only when quests/completions/profile flows emit. Nothing triggers a refresh at the date rollover or when DayPart changes, so the morning glance - the widget's core use case - shows the previous evening's plan ('N today' counting yesterday's state, evening routines) until the user opens the app or data changes. The dimension's widget/db sync requirement is only met while data mutates.

**Recommendation:** Re-render at day boundaries: schedule a WorkManager daily refresh or an ACTION_DATE_CHANGED/TIME_SET receiver calling QuestWidget().updateAll(); optionally also on day-part transitions.

<sub>Skeptic: Verified updatePeriodMillis=0; the only updateAll call site is the data-flow merge in QuestLoopApplication (flows emit only on writes/process start). No WorkManager, no DATE_CHANGED/TIME_SET/TIME_TICK receivers anywhere; ReminderReceiver doesn't write Room. Widget snapshots LocalDate/DayPart at render, and routinesFor(EVENING) differs from MORNING. No documented tradeoff. Medium severity fits.</sub>

#### [55] Habit/goal-derived quests deferred from today's plan cannot be completed anywhere that day

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:137` · **LOW** · effort medium · skeptic verdict: **partially correct** · P2 · proposal

questOverview (Quests screen) deliberately lists only questDao.getActive(), excluding derived quests; HabitsScreen manages definitions with no completion controls; TodayScreen never renders plan.deferred. So when maxDailyQuests (default 6) squeezes a habit or goal check-in out of the generated plan, that quest is invisible and uncompletable for the day, silently costing streak/consistency. The Quests screen's own contract - 'nothing the user created... is ever hidden behind the curated daily plan' - does not hold for habits/goals. I argue the documented exclusion is wrong without any fallback surface.

**Recommendation:** Include derived quests in questOverview (flagged non-editable/non-deletable, linking to Habits for management) so 'Also due today' lets users complete them when deferred.

**Skeptic's correction:** When maxDailyQuests squeezes a habit/goal-derived quest out of the plan, it is temporarily invisible with no completion surface (Today shows only plan.quests, Quests excludes derived, Habits manages definitions only). However, it usually re-surfaces the same day: completing/skipping plan items removes them from candidates and deferred quests backfill. No streak break or penalty occurs; the cost is that day's rep/XP for daily-cadence habits, and only if 6+ higher-scored quests stay undone all day. A fallback surface (derived quests in questOverview) is still worthwhile.

<sub>Skeptic: Verified: questOverview excludes derived quests, HabitsScreen has no completion controls, plan.deferred is rendered nowhere, generator caps derived quests at maxDailyQuests. But the plan recomputes on every completion — deferred quests backfill the same day — and no streak/penalty fires for hidden quests (penalties need explicit skip; streak is any-completion app-level).</sub>

#### [56] Reviews 'Plan' view and Today disagree on when a measured weekly/monthly quest is next due

`core/src/main/kotlin/com/questloop/core/generation/PeriodPlanner.kt:73` · **LOW** · effort medium · skeptic verdict: **partially correct** · P2 · fix W19

PeriodPlanner uses QuestScheduler's rolling windows (lastCompleted + 7/30 days) for all quests, while Today resets measured weekly/monthly quests at calendar-interval boundaries (QuestRepository.kt:195-206). A weekly quantitative quest finished Wednesday reappears on Today next Monday, but the Plan view reports first-due/occurrences as if nothing is due until Wednesday+7. The two sibling surfaces present contradictory schedules for the interval-accumulation quests introduced in the recent merge.

**Recommendation:** Teach the planner interval semantics for measured weekly/monthly quests: treat lastCompleted as satisfied only within the current calendar interval, mirroring hasCalendarInterval/completionSlot.

**Skeptic's correction:** PeriodPlanner does use rolling 7/30-day windows while Today resets measured weekly/monthly quests at calendar boundaries, but the cited example shows no user-visible contradiction: firstDueEpochDay is never rendered (ReviewScreen shows only frequency/"·N×"/deadline), and a weekly quest finished Wednesday still lists in next week's plan (firstDue Wed+7 lands inside the Mon–Sun window, occurrences=1), agreeing with Today. The visible divergence is narrower: a monthly measured quest completed late in the prior month is missing from the "This week" plan while Today surfaces it from the 1st (and after a 31-day month can drop from the next monthly plan entirely, e.g. completed Jan 30-31 → firstDue Mar 1-2 > Feb 28); weekly quests' "·N×" counts and minute subtotals in the monthly plan can be off by one. The recommendation — teach the planner interval semantics for hasCalendarInterval quests — stands.

<sub>Skeptic: Verified todayPlan's calendar-interval gate (completionSlot/dismissedQuestIdsToday) vs periodPlan feeding raw lastCompletedDays (real log day) into rolling QuestScheduler math — the divergence is real and undocumented. But firstDueEpochDay is never rendered, and in the cited Wednesday scenario the quest still appears in next week's plan (occurrences=1), matching Today; visible contradictions only hit monthly quests and occurrence counts.</sub>

#### [57] Reminder 'Mark done' fallback uses a notification trampoline blocked on Android 12+ (targetSdk 36)

`app/src/main/java/com/questloop/app/reminders/ReminderActionReceiver.kt:53` · **LOW** · effort small · skeptic verdict: **confirmed** · P1 · fix W11

When the slot's routine isn't in today's plan, the receiver calls context.startActivity(MainActivity) from a broadcast receiver invoked by a notification action. Apps targeting Android 12+ cannot start activities from notification-trampoline receivers - the call is silently blocked, so the documented 'open the app instead' fallback does nothing; the tap just cancels the notification. Currently masked by the routine-dismissal regression (routine is always in the plan); once that is fixed, a second late tap hits this dead branch.

**Recommendation:** Drop the trampoline: make the fallback post/refresh a notification whose content PendingIntent opens MainActivity, or simply leave the original notification with its activity contentIntent.

<sub>Skeptic: ReminderNotifications.kt attaches the "Mark done" action via PendingIntent.getBroadcast; ReminderActionReceiver.kt:51-53 calls startActivity — a notification trampoline, blocked silently for targetSdk 36 on Android 12+. Notification is canceled at line 40 first. Masking claim verified: dismissedQuestIdsToday only scans candidateQuestsById(), which excludes synthetic routines, so the fallback branch is currently unreachable. Not documented as intentional.</sub>

#### [58] 'Delete all my data' leaves the AI diagnostics log on disk

`app/src/main/java/com/questloop/app/data/QuestRepository.kt:970` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W22

deleteAllData clears Room, DataStore and the encrypted key store, but never calls aiDiagnostics.clear(); the file-backed ai_diagnostics.log (FileAiDiagnostics, up to 50 lines of model names + provider error bodies) survives the wipe and remains shareable via Settings -> AI error log. The confirmation dialog promises to erase everything on-device (SPEC section 9 deletion). Contents are low-sensitivity by design (no key, no quest text), so impact is bounded polish. Alarm cancellation, by contrast, is handled indirectly via SettingsScreen's LaunchedEffect(state.reminders).

**Recommendation:** Call aiDiagnostics.clear() (and cancel any shown reminder notifications) inside deleteAllData; add a unit test asserting the dump is empty after a wipe.

<sub>Skeptic: deleteAllData (QuestRepository.kt:970-974) clears only Room and profileStore; aiDiagnostics.clear() is never called — the existing clearAiDiagnostics() wrapper (line 864) has no production caller. FileAiDiagnostics is production-wired and "Share AI error log" still dumps the file post-wipe. KDoc promises "Erases all on-device data (SPEC §9)"; no doc marks this deliberate. Redacted, low-sensitivity content justifies low severity.</sub>

### follow-up: R8/ProGuard keep-rule coverage and zero runtime validation of the minified release variant

#### [59] R8-minified bytecode is never executed by any test or CI job, while comments and docs claim it is validated

`.github/workflows/full-tests.yml:60` · **HIGH** · effort medium · skeptic verdict: **confirmed** · P0 · proposal

No job ever executes R8 output. full-tests runs only debug bytecode (testDebugUnitTest; connectedDebugAndroidTest at line 106; no testBuildType override in app/build.gradle.kts); its 'Assemble release APK (R8 check)' step (lines 60-63) proves compilation only, yet its comment — and NEXT_STEPS.md:9-10 — claim ProGuard breakage 'is caught before shipping'. Runtime-only R8 failures (reflection, stripped members) are invisible at compile time, and AGP 8.13 defaults to R8 full mode. Once RELEASE_KEYSTORE_BASE64 is configured per docs/RELEASE_SIGNING.md, release.yml:75-101 ships this never-executed bytecode.

**Recommendation:** Execute minified bytecode in CI: add a debuggable 'minified' build type mirroring release's proguardFiles and run AppSmokeTest against it via testBuildType in the emulator job — or at minimum adb-install the unsigned release APK there and assert MainActivity launches.

<sub>Skeptic: Verified all workflows: only assembleRelease touches R8 (full-tests.yml:63, release.yml:82); every test runs debug bytecode, no testBuildType override. Comment "caught before shipping" and docs/NEXT_STEPS.md:9-10 "now exercised" overclaim a compile-only step. AGP 8.13.2, full mode not disabled. release.yml gates only :core:test; adding the keystore secret silently ships never-executed bytecode. No documented tradeoff.</sub>

#### [60] Release workflow discards the R8 mapping file and keeps no line numbers — signed-release crashes will be un-retraceable

`.github/workflows/release.yml:88` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P2 · fix W09

The signed-release path discards R8's mapping file: staging copies only the APK into dist/ (lines 91-101) and the publish step uploads dist/*.apk (line 131), so app/build/outputs/mapping/release/mapping.txt dies with the runner. Nothing retains line numbers either — AGP's default proguard-android-optimize template keeps no LineNumberTable/SourceFile (verified in the AGP 8.13.2 jar) and proguard-rules.pro adds no keepattributes. For a sideloaded app whose crash feedback is user-pasted logcat, every signed-release crash arrives obfuscated and permanently un-retraceable.

**Recommendation:** When signed==true, attach app/build/outputs/mapping/release/mapping.txt to the GitHub release (or upload as a workflow artifact). Add '-keepattributes SourceFile,LineNumberTable' and '-renamesourcefileattribute SourceFile' to app/proguard-rules.pro.

<sub>Skeptic: Verified release.yml stages only the APK (lines 89-101), uploads dist/*.apk (131), no upload-artifact anywhere. proguard-rules.pro has no keepattributes; extracted AGP 8.13.2's default template from the cached jar — it keeps Signature/annotations, not SourceFile/LineNumberTable. Latest releases (v0.3.0, v0.1.0) are release-signed, so minified builds actually ship. No docs document this tradeoff.</sub>

#### [61] Hand-written serializer keep rules are redundant with the library's bundled rules and misleadingly scoped to core.model only

`app/proguard-rules.pro:7` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · proposal

Lines 7-14 keep serializer machinery only for com.questloop.core.model.**, implying other @Serializable classes — AiQuestDto (core/src/main/kotlin/com/questloop/core/ai/AiQuestService.kt:15), OpenAiTokens (core/.../openai/OpenAiOAuth.kt:79), OpenRouterClient's DTOs (lines 78-99), ExportSnapshot.kt:13 — need but lack protection. They don't: kotlinx-serialization-core 1.11.0 bundles generic consumer rules (verified in the jar's META-INF/com.android.tools/r8) covering every @Serializable class including R8 full mode, and all call sites pass explicit .serializer(), so serializers are statically reachable. The hand rules are dead weight, and line 14 pins whole $$serializer classes, blocking shrinking.

**Recommendation:** Delete lines 7-14 (or replace with a comment noting kotlinx-serialization's bundled consumer rules own serialization keeps); retain only the Tink -dontwarn. Pair with a minified smoke test so any future keep-rule regression surfaces in CI, not on devices.

<sub>Skeptic: Verified the resolved kotlinx-serialization-core-jvm-1.11.0 jar bundles META-INF/com.android.tools/r8 rules (common + full-mode) conditionally covering all @Serializable classes; grep shows zero reified serializer calls — every site passes explicit .serializer(). Cited classes/lines accurate; line 14 pins whole $$serializer classes unlike the bundled descriptor-field-only rule. No docs mark the scoped rules deliberate. Low severity fits.</sub>

### follow-up: data_extraction_rules.xml duplicates the exact exclusion gaps the review flagged only in backup_rules.xml

#### [62] data_extraction_rules.xml (the file governing Android 12+) repeats the WAL/SHM and ai_diagnostics.log holes, and its device-transfer section is live, not a backstop

`app/src/main/res/xml/data_extraction_rules.xml:7` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W22

Android 12+ reads only this file (targetSdk 36, AndroidManifest.xml:27). Both sections exclude questloop.db but not questloop.db-wal/-shm (Room uses default WAL mode, QuestLoopDatabase.kt:52-63) nor files/ai_diagnostics.log (AiDiagnostics.kt:34,54). allowBackup="false" does not disable device-to-device transfer for apps targeting 12+, so <device-transfer> is live policy: recent quest/habit writes sitting in the WAL, plus the AI error log, transfer during phone migration today. The prior finding anchored only backup_rules.xml; fixing that file alone leaves modern devices leaky.

**Recommendation:** In both <cloud-backup> and <device-transfer> (and backup_rules.xml) add excludes for questloop.db-wal, questloop.db-shm, questloop.db-journal (domain="database") and ai_diagnostics.log (domain="file"). Sturdier: exclude the domain roots (path=".") so future files are covered by default.

<sub>Skeptic: Both sections exclude only questloop.db/datastore/secure-prefs (data_extraction_rules.xml:6-15); no setJournalMode anywhere, so Room defaults to WAL; ai_diagnostics.log lives in filesDir; targetSdk 36. Android docs confirm allowBackup=false can't be relied on to block D2D on 12+, so device-transfer is live. SAFETY_AND_PRIVACY.md:47 promises exclusion — real bug. But data reaches only the user's own device and the log is secret-redacted.</sub>

#### [63] Manifest/XML comments and SAFETY_AND_PRIVACY.md wrongly frame the rules as a dormant backstop; on Android 12+ device-transfer ignores allowBackup="false"

`app/src/main/AndroidManifest.xml:20` · **LOW** · effort small · skeptic verdict: **confirmed** · P3 · fix W02

The manifest comment (lines 20-23) claims backup-off means nothing is copied to "cloud/adb backup or device-transfer" and calls the rule files a backstop "in case backup is ever re-enabled"; data_extraction_rules.xml:2-4 and backup_rules.xml:2-5 repeat it. For apps targeting Android 12+, allowBackup="false" only disables Google Drive backup; device-to-device migration still runs, governed solely by the <device-transfer> rules. docs/SAFETY_AND_PRIVACY.md:47-49 likewise presents the exclusions as complete. This wrong model is what let the gaps persist and invites future regressions.

**Recommendation:** Reword the manifest and both XML header comments: allowBackup="false" does not stop D2D transfer on Android 12+, so <device-transfer> is the active control and must stay exhaustive. Correct the "Backups off by default" bullet in docs/SAFETY_AND_PRIVACY.md accordingly.

<sub>Skeptic: All cited comments/doc lines say exactly what's claimed; targetSdk=36. Official Android docs (autobackup guide + Android 12 backup-restore page) confirm allowBackup="false" doesn't disable D2D transfers for apps targeting 12+ (on some OEMs' devices), and only <device-transfer> rules govern D2D. No repo doc acknowledges this; not intentional. Low severity fits: sensitive stores are already excluded, so it's a comments/docs fix.</sub>

### follow-up: The stuck-busy/stranded-in-flight defect class exists in ViewModels the review never touched (fix-phase enumeration gap)

#### [64] HabitsViewModel: failed load renders "No habits yet" over real data; failed mutate silently discards input; loading flag is dead

`app/src/main/java/com/questloop/app/ui/habits/HabitsViewModel.kt:33` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W05

load() sets loading=true (line 35) and clears it only on success (line 39); launchSafely's default onError just logs. HabitsScreen never reads loading, so a failed load shows all three "No habits yet" hints (HabitsScreen.kt:52-79) over the user's real habits/goals, inviting duplicate re-adds (fresh UUIDs, duplicate derived quests). More likely in practice: mutate() (lines 87-92) silently drops a failed DataStore write after the form already cleared its field (HabitsScreen.kt:151).

**Recommendation:** Reset loading in try/finally, pass a launchSafely onError that surfaces an error toast/state, and either render loading in HabitsScreen or delete the dead flag. Pin with a FakePrefs whose setHabits throws (harness already exists, HabitsViewModelTest.kt:43-81).

<sub>Skeptic: Verified all claims: loading set line 35, cleared only on success line 39; launchSafely default onError logs only; HabitsScreen never reads loading; forms clear fields eagerly (line 151); setHabits writes are unhardened unlike reads; fresh UUIDs make real duplicates. Sibling ViewModels got try/finally fixes; Habits didn't. Load-throw is rare (IOException swallowed upstream), but reviewer already hedged that.</sub>

#### [65] QuestsViewModel/QuestBankViewModel: one emission failure permanently kills the quests collector — Quests tab pinned on spinner or frozen stale

`app/src/main/java/com/questloop/app/ui/quests/QuestsViewModel.kt:48` · **LOW** · effort medium · skeptic verdict: **partially correct** · P1 · fix W06

launchSafely wraps the whole repository.quests.collectLatest (lines 48-51); a throw from recompute()/questOverview propagates out of collectLatest, is logged once, and the subscription is dead for the VM's lifetime. First-emission failure leaves loading=true (cleared only at line 56) and QuestsScreen shows only a full-screen spinner (QuestsScreen.kt:66-73); a later failure silently freezes the backlog. QuestBankViewModel's collector (QuestBankViewModel.kt:36-49) dies identically, freezing addedIds/adding tracking. Note: try/finally on flags alone cannot fix this variant.

**Recommendation:** Catch per emission (try/catch inside the collectLatest body, or .catch/retry on the flow) so one bad recompute doesn't cancel collection; clear loading in finally; surface an error state. Apply to both collectors.

**Skeptic's correction:** A throw from the flow or recompute()/questOverview does kill the collector for that VM instance, leaving a spinner (first emission) or no passive updates, silently — and QuestBankViewModel strands adding/addedIds identically. However, the nav setup (saveState=false/restoreState=false; sub-screen back-pop) destroys and recreates these VMs on every tab visit, so navigating away/back or re-tapping the tab restarts collection; local actions (complete/skip/delete) also still refresh via direct recompute(). Impact is one tab visit per transient failure, not a permanent freeze. Per-emission catch + error state is still the right fix.

<sub>Skeptic: Verified mechanics and all line refs: launchSafely catches once, collector dies, loading only cleared in recompute(), spinner-only branch, QuestBank identical, no error state. But switchTab/openOnce use saveState=false/restoreState=false, so any tab re-entry (even re-tapping Quests) recreates the VM and collector — transient failures self-heal; local actions still recompute. Trigger (store/engine throw) is rare.</sub>

#### [66] QuestBankViewModel.add: a failed addFromBank strands the id in `adding`, silently dead-ending that row's Add button

`app/src/main/java/com/questloop/app/ui/quests/QuestBankViewModel.kt:54` · **LOW** · effort small · skeptic verdict: **confirmed** · P1 · fix W06

add() puts the id into `adding` (line 54) and deliberately never clears it locally — the quests collector removes it only once the quest appears in addedIds (lines 42-46, 57-59). If repository.addFromBank throws, the quest never appears, the id stays in `adding` for the VM lifetime, and the guard (line 53) silently no-ops every retry tap. The screen never renders `adding` (BankRow gets only addedIds, QuestBankScreen.kt:61-66), so the button still looks like an enabled "Add". The deliberate design's comments reason only about success ordering; the failure path is unhandled.

**Recommendation:** On failure, remove the id from `adding` and toast the error (launchSafely onError or try/catch around addFromBank only, preserving the success-path handoff). Optionally pass `adding` to BankRow as a disabled in-flight state.

<sub>Skeptic: Verified: launchSafely's default onError only logs (LaunchSafely.kt:22); `adding` is cleared only at line 46 when the id reaches addedIds; guard line 53 silently no-ops retries; QuestBankScreen never renders `adding`. Sibling VMs clear in-flight flags in `finally`; no test/doc covers failure. Trigger is a rare Room write failure, and the nav-entry-scoped VM resets on leaving the screen.</sub>

#### [67] ReviewViewModel: load failure blanks the Reviews tab; summarize failure permanently disables the AI button mid-spinner

`app/src/main/java/com/questloop/app/ui/review/ReviewViewModel.kt:43` · **LOW** · effort small · skeptic verdict: **partially correct** · P1 · fix W06

loading (line 43) and summarizing (line 77) are cleared only on success (lines 52-54, 81). If repository.review/periodPlan throws, cards stay null and both empty-state hints are gated on !state.loading (ReviewScreen.kt:103-111), so the tab renders blank until a tab switch recreates the VM (switchTab uses saveState=false). If narrateReview throws — AiNarrator runCatches transport (AiNarrator.kt:80-83), but recordAiError's file rewrite can throw (QuestRepository.kt:961-967) — "Summarize with AI" stays disabled with an eternal spinner (ReviewScreen.kt:88-97).

**Recommendation:** Wrap both bodies in try/finally to reset loading/summarizing, and surface an error line or toast via launchSafely onError. Add failure-path cases to ReviewViewModelTest.

**Skeptic's correction:** Confirmed: if repository.review/periodPlan throws (unguarded Room calls — the exact case launchSafely exists to absorb), loading sticks true, cards stay null, and both empty-state hints (gated on !state.loading, ReviewScreen.kt:103/111) are suppressed, blanking the tab content until a tab switch recreates the VM. The summarize half is structurally true (summarizing cleared only on success; guard + disabled button then lock it, and load() never resets it) but its claimed trigger is wrong: FileAiDiagnostics.record (AiDiagnostics.kt:40-42) runCatching-guards both the file read and write ("Logging an AI error must never itself throw"), AiCallGuard.keepAwake guards the wake lock, AiNarrator runCatches transport, and getAiConfig's DataStore/keystore reads are IOException-guarded — so the eternal-spinner path is nearly unreachable. Fix recommendation stands and matches the try/finally convention already used in Quests/Today/Settings/Rewards/Completed ViewModels.

<sub>Skeptic: Verified ReviewViewModel, LaunchSafely, ReviewScreen gating, switchTab saveState=false, AiNarrator, AiDiagnostics. Load half confirmed: Room throws leave loading=true, hints suppressed, blank content until tab switch; five sibling VMs use try/finally, so not intentional. But the summarize trigger is refuted: FileAiDiagnostics.record runCatching-guards both read and write, keepAwake/transport/getAiConfig also guarded.</sub>

#### [68] AchievementsViewModel: one-shot init load with no retry API; a failure pins the whole visit on a full-screen spinner

`app/src/main/java/com/questloop/app/ui/achievements/AchievementsViewModel.kt:24` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W05

The only load runs in init (lines 24-30); loading defaults true and is cleared solely on success (line 27). AchievementsScreen early-returns a centered CircularProgressIndicator while loading (AchievementsScreen.kt:28-35), so if achievementStatuses() throws, the entire visit is an infinite spinner. Unlike every sibling, the VM exposes no reload method, so there is no retry hook even for the screen; recovery requires backing out so the openOnce entry pops and a fresh VM is created.

**Recommendation:** Extract the init body into a public load() with try/finally on loading plus an error state; keep init calling it. Follow the sibling pattern so a class-wide launchSafely/onError fix covers this file too.

<sub>Skeptic: Verified: init-only load (AchievementsViewModel.kt:23-30), loading=true default cleared only on success; launchSafely's default onError just logs, so a throw strands loading=true. Screen early-returns a full-screen spinner (AchievementsScreen.kt:28-35). VM exposes no reload; recovery only via popping the openOnce nav entry. No test/doc mitigation. Trivial nit: QuestsViewModel's reload is also private (flow-driven).</sub>

#### [69] RewardsViewModel.load: same success-only loading reset; flag never consumed, so failure silently renders a zeroed budget

`app/src/main/java/com/questloop/app/ui/rewards/RewardsViewModel.kt:47` · **LOW** · effort small · skeptic verdict: **confirmed** · P1 · fix W06

load() (lines 45-63) sets loading=true (line 47), cleared only on success (line 54). RewardsScreen never consumes state.loading, so on failure the tab quietly shows defaults — budgetCap 0.0, allowance null — as if no budget were configured, until re-entry reloads. Contrast markFundStepDone (lines 78-87), which resets its busy flag in finally — the correct idiom already lives in this same file. Listed so a per-file fix sweep of the stuck-busy class does not miss it.

**Recommendation:** Move the loading reset into try/finally with an onError surface, and either render loading in RewardsScreen or drop the flag. Include in the same class-wide fix as the other five files.

<sub>Skeptic: RewardsViewModel.kt:47 sets loading=true; only the success update (line 54) clears it. launchSafely's default onError just logs, so failure skips the update entirely. Grep confirms RewardsScreen never reads state.loading; failure renders allowance=null/budgetCap=0/fundBudgetSet=false as an unconfigured budget. markFundStepDone's try/finally idiom is at lines 79-87. Tests cover only happy path; nothing documents this as intentional.</sub>

### follow-up: Reminder alarms are armed as fixed wall-clock instants and never re-armed on timezone or clock changes

#### [70] Armed reminder alarms are never corrected on timezone or clock changes — pending alarm fires at the old zone's wall-clock time

`app/src/main/java/com/questloop/app/reminders/ReminderScheduler.kt:38` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W17

Alarms are armed as fixed RTC instants using the arm-time zone (ReminderScheduler.kt:38, ReminderSchedule.kt:16) and re-armed only on fire/boot/app-open; the manifest's only time trigger is BOOT_COMPLETED (AndroidManifest.xml:52-58; BootReceiver.kt:14 rejects other actions). After a timezone change, pending alarms fire at the old zone's wall clock — verified: a New-York-armed 20:00 evening reminder fires at 02:00 Paris local — one wrong fire per slot until re-arm self-corrects. Clock corrections (ACTION_TIME_CHANGED) are likewise unhandled. Plain DST transitions are handled at arm time.

**Recommendation:** Add android.intent.action.TIMEZONE_CHANGED and android.intent.action.TIME_SET to BootReceiver's intent-filter (both exempt from implicit-broadcast restrictions), accept them in onReceive, and re-apply the stored ReminderConfig. Add a Robolectric test asserting a zone change re-arms both slots at the new zone's trigger millis.

<sub>Skeptic: Verified: schedule() arms fixed RTC_WAKEUP epoch millis computed with ZoneId.systemDefault() at arm time; re-arm only on fire/boot/app-open/settings-save; manifest BootReceiver filters only BOOT_COMPLETED and onReceive rejects other actions; no TIMEZONE_CHANGED/TIME_SET anywhere. NY 20:00 = Paris 02:00 checked both seasons. No documented tradeoff in AGENTS.md/docs. Recommendation valid (both broadcasts exemption-listed).</sub>

### follow-up: ProfileStore's SecureKeyStore default parameter is the plaintext store - the un-fixed root cause behind the BootReceiver finding - and SecureKeyStore.kt itself was never audited

#### [71] Plaintext key store is the constructor DEFAULT in the credential path (root cause behind the BootReceiver mis-wire)

`app/src/main/java/com/questloop/app/data/ProfileStore.kt:70` · **MEDIUM** · effort small · skeptic verdict: **confirmed** · P1 · fix W12

`keyStore: SecureKeyStore = DataStoreKeyStore(dataStore)` makes the plaintext store the default; secure storage depends on every call site remembering an optional named argument (only AppContainer.kt:21 does). The KDoc (SecureKeyStore.kt:33-36) frames the default as deliberate for tests — that rationale is wrong: all 7 ProfileStoreTest constructions could pass it explicitly, and the one hand-wired production call site (BootReceiver.kt:18) already forgot it, proving the trap fires. Contradicts AGENTS.md:115 ('never plaintext DataStore').

**Recommendation:** Remove the default (or default to a factory returning EncryptedKeyStore); pass DataStoreKeyStore explicitly in tests and fix BootReceiver in the same change. Safe-by-default costs ~9 call-site edits.

<sub>Skeptic: Verified every claim: ProfileStore.kt:70 defaults keyStore to plaintext DataStoreKeyStore; KDoc SecureKeyStore.kt:33-36 matches; only AppContainer.kt:21 passes EncryptedKeyStore; BootReceiver.kt:18 omits it; 7 ProfileStoreTest constructions; AGENTS.md:115 mandates never-plaintext. Mitigating: BootReceiver only calls getReminderConfig(), which never touches keyStore, so no credentials transit plaintext today — latent trap, not active exposure.</sub>

#### [72] No scrub or migration path for the v2 plaintext credential slots; v1 scrub is one-shot fragile

`app/src/main/java/com/questloop/app/data/ProfileStore.kt:199` · **LOW** · effort small · skeptic verdict: **partially correct** · P2 · fix W08

getAiConfig's migration handles only the v1 'ai_api_key' slot; nothing ever scrubs or migrates plaintext 'ai_api_key_v2'/'openai_tokens_v1' (SecureKeyStore.kt:40-41) except full clear(). A defaulted call site calling getAiConfig on a pre-migration install would move the key into plaintext v2, scrub v1, and the key vanishes from the real app (EncryptedKeyStore never reads v2) while persisting in plaintext. Also, if the process dies between the verified secure write and the v1 remove (line 215), the plaintext v1 copy lingers until the next Settings save.

**Recommendation:** When the injected store is EncryptedKeyStore: migrate-then-remove v2 slots if populated, and unconditionally remove AI_KEY whenever the secure store already holds the key. Run once at startup or inside getAiConfig.

**Skeptic's correction:** The v2 plaintext slots (ai_api_key_v2/openai_tokens_v1) are written only by DataStoreKeyStore, which production has never used — EncryptedKeyStore was wired into AppContainer in the same commit that introduced the abstraction, and the only defaulted ProfileStore call site (BootReceiver) never calls getAiConfig/setAiConfig. So no scrub/migration is needed today and no key-vanishing path exists; the defaulted keyStore parameter is merely a latent footgun. The reachable kernel is narrow: if the process dies between the verified secure write and the v1 remove (ProfileStore.kt:215), the pre-existing plaintext v1 copy lingers (the migration branch won't re-run since the secure read is now non-blank) until the next setAiConfig scrubs it — no new exposure and no key loss, just delayed cleanup of an already-plaintext copy. A cheap hardening fix: in getAiConfig, unconditionally remove Keys.AI_KEY when the secure store already holds the key.

<sub>Skeptic: Read ProfileStore/SecureKeyStore, grepped all ProfileStore constructions, checked git history. v2 slots are written only by DataStoreKeyStore, never used in production: EncryptedKeyStore was wired into AppContainer in the same commit (c5401d2) that created the abstraction, and BootReceiver, the sole defaulted site, calls only getReminderConfig. Only the process-death v1-lingering window is reachable.</sub>

#### [73] EncryptedKeyStore has zero test coverage anywhere; the coverage-exclusion rationale is false for the emulator

`app/build.gradle.kts:138` · **MEDIUM** · effort medium · skeptic verdict: **confirmed** · P2 · fix W36

The production credential store is coverage-excluded claiming the Keystore "isn't available under Robolectric or the emulator" (build.gradle.kts:136-138, docs/NEXT_STEPS.md:56). Robolectric: true. Emulator: false — emulator images ship a software-backed Keystore, EncryptedSharedPreferences is routinely exercised in connectedAndroidTest, and the repo's own instrumented tests already execute EncryptedKeyStore reads on the emulator (AppSmokeTest walks Settings). Its subtle contract — reads swallow to "", writes/clear must throw via commit() (SecureKeyStore.kt:86-106) — has never run under assertion. Documented deliberate choice; the emulator half of the rationale is factually wrong.

**Recommendation:** Add an androidTest for EncryptedKeyStore: round-trip apiKey/tokens, clear(), and blank-value behavior on the emulator; then drop the exclusion (or narrow it to the unit-only report) and correct NEXT_STEPS.md.

<sub>Skeptic: Verified: no test anywhere references EncryptedKeyStore; build.gradle.kts:136-138 and NEXT_STEPS.md:56 claim the Keystore is unavailable on the emulator, which is false — AppContainer wires EncryptedKeyStore, and AppSmokeTest/CoverageWalkTest walking Settings triggers getAiConfig() → EncryptedKeyStore reads on the emulator today, unasserted. Write/clear throw-on-commit contract (lines 94-106) never executes in any test. Documented, but on a false premise.</sub>

#### [74] Key-safety unit test asserts a weaker invariant than its name claims: the key IS in plaintext DataStore during the test

`app/src/test/java/com/questloop/app/data/ProfileStoreTest.kt:64` · **LOW** · effort small · skeptic verdict: **confirmed** · P2 · fix W36

The test 'ai config round-trips and is not left in plaintext datastore' checks only the legacy 'ai_api_key' slot (line 73). Because the store is constructed with the defaulted DataStoreKeyStore, 'sk-xyz' actually sits in the same plaintext DataStore file under 'ai_api_key_v2' when the assertion passes. The test gives false assurance on exactly the property it is named for, and would keep passing if a regression routed production credentials through the plaintext default.

**Recommendation:** Inject a map-backed fake SecureKeyStore (not DataStore-backed), then assert the key string appears in no DataStore preference value. Keep a separate, honestly-named test for the legacy-slot scrub.

<sub>Skeptic: Verified: test (line 66) uses ProfileStore's defaulted DataStoreKeyStore (ProfileStore.kt:70), so setAiConfig writes "sk-xyz" into the same plaintext DataStore under "ai_api_key_v2" (SecureKeyStore.kt:40,49) while line 73 asserts only the legacy "ai_api_key" slot. Production EncryptedKeyStore wiring (AppContainer.kt:21) is coverage-excluded and untested, so the regression scenario is real. Docs only justify skipping Keystore, not the overclaiming name. Low severity is right.</sub>

## What this codebase does well

### architecture

- The :core/:app boundary is real, not aspirational: verified zero android/androidx/okhttp imports in core (kotlinx-serialization only), time injected as epochDay, and a 90% JaCoCo instruction-coverage gate wired into :core's check task - the deterministic economy genuinely runs and gates anywhere.
- The AI provider seam is exemplary: a one-method LlmClient interface in core; prompt building, parsing, and guardrails (AiQuestService/AiNarrator/AiQuestValidator) fully provider-agnostic; per-provider transport/auth in app; and the OpenAI OAuth split (pure PKCE/JWT/SSE codec in core, ServerSocket/OkHttp/Keystore in app) puts every brittle protocol bit under plain JVM tests.
- Manual DI is proportionate and disciplined: one AppContainer, one appViewModelFactory, and interfaces placed exactly at the awkward seams (ProfilePreferences, OpenAiAuth, CalendarReader, AiDiagnostics, AiCallGuard, injectable endpoints for MockWebServer); widget and broadcast receivers reuse the app-scoped container instead of hand-wiring divergent stacks.
- The ledger-as-single-source-of-truth XP design (SUM(xpAwarded), idempotent instanceId keys, undo-by-restoring-the-prior-record) makes undo, history edit/re-score, and idempotent import all fall out consistently with no reconciliation code - the past review's H2/H3 fixes became an architectural asset.
- The data layer stays thin and honest: total, tolerant entity-to-domain mapping (parseEnum defaults, enums stored by name), windowed/aggregate DAO queries on hot paths, and derived (habit/goal/routine/admin) quests consistently merged into candidate pools rather than special-cased per screen.

### code-quality

- Exceptional comment discipline: nearly every non-obvious decision carries a "why" comment citing the spec or a learned gotcha (e.g. the slot-vs-epochDay trade-off note in QuestRepository.completeQuestLocked:470-483, QuestBankViewModel's adding-flag lifecycle comment) — the 1000-LOC repository stays navigable because of it.
- Disciplined concurrency: no GlobalScope/runBlocking anywhere; three purpose-named Mutexes with documented scope and an explicit lock-ordering note (QuestRepository.importJson:744-748), a "Caller MUST hold" convention for completeQuestLocked, and a correct launchSafely utility (rethrows CancellationException) used by every ViewModel instead of bare launches.
- The AGENTS.md one-shot-event gotcha is institutionalized, not just remembered: every snackbar across Today/Quests/QuestBank/Settings/Rewards/Completed keys its LaunchedEffect on a monotonic toastId/messageId, with the rationale re-stated at each site.
- Defensive data-layer idioms applied uniformly: tolerant enum parsing via a single Mappers.parseEnum, DataStore reads guarded against corrupt files (ProfileStore.safeData:77-79), a carefully idempotent plaintext-to-Keystore key migration (ProfileStore.getAiConfig:204-216), and windowed DAO queries replacing the old getAll() hot paths.
- All fourteen items from the past review (docs/CODE_REVIEW.md) are verifiably fixed in code — ledger-derived XP with per-instance idempotent netting, style-aware dismissal, word-boundary AI guardrails — showing review findings actually land rather than accumulate.

### testing

- Layered, behavior-first test architecture: :core pure-JVM suite behind an enforced 0.90 instruction-coverage gate; the app data layer tested against real in-memory Room via Robolectric (actual DAO SQL exercised) with the real repository under ViewModels; emulator tests split deliberately into strict assertions (NavigationTransitionTest) vs an explicit coverage walk (CoverageWalkTest).
- The past review's gaps were genuinely closed and pinned, not just marked done: idempotent completion, ledger-derived XP, negative-XP floors, export/import round-trips including 'export never contains the ai api key', a real-concurrency test for the daily meta cap, and weekly-interval accumulation edge cases (QuestRepositoryTest/BranchesTest/CompletionStylesTest).
- The AI/network stack is tested unusually well for an app this size: MockWebServer for both providers covering error bodies, 401 refresh-and-retry, SSE mid-stream failure treated as a miss rather than partial success (OpenAiResponsesCodecTest:75), the PKCE RFC 7636 test vector, and a real loopback OAuth sign-in with CSRF-state and timeout tests (OpenAiAuthServiceTest).
- CI is deliberate and self-documenting: a ~1-minute per-push smoke gate, a reusable core-tests workflow, merged unit+instrumented coverage with a reasoned, annotated floor and per-package diagnostics printed in the log, plus a MigrationTestHelper suite that replays shipping migrations and asserts v2→v3 data preservation.
- A regression-driven testing culture: tests name the exact bug they pin (LevelSystemTest 'regression: skip crash', monotonic re-log tests, guardrail false-positive tests), and .claude/rules/testing.md codifies where each kind of test belongs.

### security

- The OAuth loopback flow is genuinely well engineered: ServerSocket bound to 127.0.0.1 (not wildcard) with backlog 1 BEFORE the browser opens, SecureRandom state validated against the callback, RFC 7636 S256 PKCE with the verifier never leaving the process, exact registered redirect_uri, token exchange over TLS only, read-timeout-bounded stray-connection handling, and unit tests that explicitly cover the CSRF state-mismatch and declined-consent paths (OpenAiAuthServiceTest).
- Credential storage has thought-through failure semantics, not just encryption: Keystore-backed EncryptedSharedPreferences where reads fail safe to signed-out while writes fail loudly (SecureKeyStore.kt:81-96), SettingsViewModel verifies the key actually persisted before claiming success, the legacy plaintext key migration scrubs only after a verified secure write (ProfileStore.kt:204-216), and ExportSnapshot structurally cannot contain AiConfig — locked in by tests.
- Privacy-by-design AI pipeline: narration payloads send only engine-computed aggregates (AiNarrator explicitly excludes quest titles; dedup against existing quests happens locally in the validator, not in the prompt), the Codex request sets store=false, model output is treated as untrusted (word-boundary banned-content regexes, difficulty/minutes clamps, NarrationSanitizer hard-gate) and is never auto-persisted.
- Manifest and IPC hygiene: allowBackup=false with explicit exclusion backstops, minimal permission set with in-manifest justifications, every PendingIntent FLAG_IMMUTABLE, reminder receivers non-exported and action-validated (exported BootReceiver checks ACTION_BOOT_COMPLETED), and no cleartext traffic (the localhost redirect is inbound-only).
- The user-shareable diagnostics log is deliberately minimal (model + reason, 50-line cap) with tested secret redaction (ApiKeyRedactionTest covers both the OpenRouter key and OAuth token scrubbing), and docs/NEXT_STEPS records the key-safety work as an explicit, tested invariant.

### performance

- Hot-path scoring/safety queries are properly windowed and aggregated in SQL (since/between, SUM(xpAwarded), COUNT variants, lastCompletedDays GROUP BY in Daos.kt) — the past review's M2 'loads entire table' item is genuinely fixed, and total XP derives from a SQL SUM rather than in-memory scans.
- Startup is lean: Room database open and EncryptedSharedPreferences creation are both lazy (QuestLoopDatabase.get builder, SecureKeyStore.kt:68 'by lazy'), Application.onCreate does no blocking IO, and every secure-store read/write hops to Dispatchers.IO.
- Network resources are handled well: both LLM clients share lazily-built singleton OkHttpClients with per-provider timeouts (OpenRouterClient.kt:105, OpenAiClient.kt:94), responses use .use{} closing, and WakeLockAiCallGuard holds a timeout-bounded partial wake lock released in finally — it cannot leak on exception paths.
- Compose lists consistently use stable keys (Today items by instanceId, Quests/Completed/QuestBank/calendar picker by id), and known-heavy JSON work (export/import) is explicitly moved off the main thread with a comment explaining why.
- The widget refresh pipeline is debounced (500ms) to coalesce write bursts, alarms use cheap one-shot re-arm instead of repeating alarms, and release builds ship with R8 minification + the optimize profile enabled.

### ux-content

- Non-shaming, warm voice is genuinely enforced end-to-end: reward explanations ("Rescheduled — no penalty. Plans change, that's fine.", honesty-logging copy in RewardEngine.kt:178-214), safety-signal framing, gentle empty states ("All clear ✓ ... rest — that counts too"), and an AI NarrationSanitizer plus a user-facing "Keep AI wording plain" toggle to strip slop.
- Most of the past UX review is actually fixed and done well: completion Undo with monotonic toastId keying, per-destination titles + back arrows, FAB scoped to list roots, numeric/decimal keyboards, locale-aware currency (Money.kt), save confirmations, first-run onboarding + guided starter quests, and an energy control with deselect and a distinct Rest option.
- Accessibility care in custom components beyond the defaults: CategoryDot and DifficultyPips carry contentDescriptions so color/pips aren't the only signal, EnergyBudgetBar collapses to one spoken summary via clearAndSetSemantics, FilterWordingRow uses row-level toggleable(Role.Switch), and the small "Why?" label reserves a 48dp target via minimumInteractiveComponentSize.
- Destructive actions are consistently confirmed in plain language that states consequences ("This permanently erases...", "Your past history stays.") for delete-quest, remove-habit/goal, and delete-all-data, while reversible actions get Undo instead — the right split.
- Double-tap and one-shot-event discipline is systematic: in-flight flags disable buttons (completing/saving/aiBusy/stepInFlight), snackbars key on monotonic ids so identical messages re-fire, and drafts persist across navigation — the documented gotchas in AGENTS.md are visibly applied in the UI code.

### features

- Ledger-as-source-of-truth completion design: records are idempotent per instanceId with prior grants netted from a baseline (QuestRepository.completeQuestLocked), so undo, history edit/re-score, re-import and double-taps all keep XP exactly consistent - the past review's H1-H3 are genuinely fixed and regression-tested.
- The reward economy implements every documented fairness rule (meta/penalty/honesty/low-effort daily caps, same-day-only anti-farm with same-instance exclusion, capped consistency and over-completion bonuses) in RewardEngine/QuestLoopEngine, matching docs/REWARD_ECONOMY.md line for line, with explanations attached to every grant.
- Cross-screen interaction discipline is systematic: in-flight flags plus disabled buttons guard double-taps, monotonic toastIds prevent swallowed snackbars, and PendingUndo restores the exact prior record - the AGENTS.md gotchas are applied uniformly in Today, Quests, Completed, and Rewards ViewModels.
- The Completed-history edit path handles the subtle re-key case (a frequency edit moving the interval slot) by deleting the stale record before re-scoring, avoiding orphans, spurious anti-farm decay, and dishonest totals - with a clear comment explaining why.
- Import/export is defensively engineered: version gating, merge-not-wipe semantics, mutex-serialized read-modify-writes, and phantom-XP prevention that validates completion questIds against stored, snapshot, derived, routine, and admin-fund quest ids.

## Feature roadmap (ideas, adversarially vetted)

A ninth agent proposed features grounded in the data model and screens; a skeptical staff-engineer/product critic then verified each against the code (does it already exist? are the integration claims real? does it fit the warm, local-first ethos?).

**Critic's top picks:** One-tap quest completion from the Glance widget, Visible rest days: accept the rest suggestion, streak stays safe, Morning digest: due and overdue quests in the reminder, with per-quest Done, Scheduled encrypted auto-backup with restore-on-first-run, Insights screen: consistency heatmap and category trends.

### One-tap quest completion from the Glance widget  · impact high / effort small · critic: **strong**

The core promise is a near-zero-effort loop, but the widget is read-only: it lists titles and opens the app (widget/QuestWidget.kt:33-64). The notification 'Mark done' already proved the pattern for routines. Completing binary quests straight from the home screen removes the last friction from the daily retention loop.

*Integration:* Add Glance actionRunCallback buttons per row in WidgetBody; route to QuestRepository.completeQuest (idempotent, mutex-guarded) like reminders/ReminderActionReceiver does for routines. Widget refresh on DB change is already wired (QuestLoopApplication.kt:50 updateAll). Binary quests first; measured ones deep-link in.

*Roadmap overlap:* docs/DESIGN_DIRECTION.md next-gen idea 3 promises 'complete without opening the app' and marks Phase 3 done, but only the read-only widget shipped — the completion half is unbuilt.

*Critic:* Verified read-only widget; ReminderActionReceiver proves receiver-to-completeQuest; refresh wiring at QuestLoopApplication.kt:50; Glance 1.1.1 supports callbacks. Small is realistic for binary quests. Ships the half of DESIGN_DIRECTION idea 3 that Phase 3 never built.

### Insights screen: consistency heatmap and category trends  · impact high / effort medium · critic: **strong**

The ledger already stores day, category, XP, and fraction for every record (data/local/Entities.kt), but users only ever see one period's aggregate (ReviewGenerator) — no history visualization, week-over-week deltas, or best-day patterns. Competitor LifeUp ships statistics; watching accumulated effort grow is a proven habit-app retention loop.

*Integration:* New pure :core InsightsEngine beside review/ReviewGenerator.kt, fed by CompletionDao.since(); new 'insights' route off the Reviews tab mirroring the 'completed' route (ui/QuestLoopApp.kt:226); heatmap drawn with Compose Canvas, no chart dependency.

*Roadmap overlap:* docs/CURRENT_STATE.md lists 'deeper analytics' as spec-future; NEXT_STEPS Horizon 3 'progression journey view' is adjacent — this is the data-first, low-art version of it.

*Critic:* Data and hooks all verified (CompletionDao.since, 'completed' route at line 226, Canvas precedent in Components.kt:59); medium fair. One ethos caution: render presence, not gaps — CONTENT_STYLE forbids shame framing.

### AI plan doctor: retrospective that proposes one-tap quest adjustments  · impact high / effort medium · critic: **plausible**

Review suggestions are inert strings ('want a smaller, easier quest there?' — core/review/ReviewGenerator.kt:114-131) and AiNarrator only rephrases facts. Users must translate insight into edits by hand. Proposing concrete reviewable changes — shrink targetCount, lower difficulty, add one starter quest in the neglected category — closes the insight-to-action loop no competitor has.

*Integration:* New PromptLibrary prompt fed review aggregates; reuse AiQuestValidator plus a deterministic rule-based fallback (FallbackSuggester pattern); expose beside ReviewViewModel.summarizeWithAi; apply through existing editQuestAndRescore/upsert. Never auto-applied, matching the review-before-save rule in AGENTS.md.

*Roadmap overlap:* none — review narration (narrateReview) exists but only rephrases; Horizon 2's conversational capture is about intake, not retrospection.

*Critic:* All refs check out, but medium underestimates: needs a new edit-op schema and validation (AiQuestValidator handles quests, not edits), apply UI, and the OpenAI path is sandbox-untestable. Differentiating and ethos-safe via review-before-apply.

### Reward wishlist and redemption ledger  · impact high / effort medium · critic: **plausible**

The allowance is a bare number plus generic admin quests (core/generation/AdminFundFactory.kt claim quest); nothing tracks what the user is saving toward or has claimed. User-defined reward shops are LifeUp's and Habitica's strongest retention mechanic. Concrete goals make earned allowance motivating; a claim log keeps it honest.

*Integration:* New Room RewardItem entity + DAO (schema bump with a real Migration per AGENTS.md discipline); list UI on ui/rewards/RewardsScreen.kt; remaining = RewardAllowanceCalculator result minus this month's claims; include in ExportSnapshot. App still never moves money; disclaimers unchanged.

*Roadmap overlap:* none — CURRENT_STATE marks reward *planning* done and Horizon 3 monetization is unrelated; redemption tracking is the missing half of the loop.

*Critic:* Verified missing — Rewards shows only allowance plus fund steps; migration discipline is real. Fits the self-funded-pot framing as a wishlist, not a Habitica shop. Impact overstated: only helps users who set a nonzero budget.

### Scheduled encrypted auto-backup with restore-on-first-run  · impact high / effort medium · critic: **strong**

Export exists but is manual (QuestRepository.exportJson via share sheet, QuestRepository.kt:700). A lost or reset phone loses months of ledger — the local-first design's biggest trust gap and a real Play-launch liability for an app whose value accumulates in data.

*Integration:* WorkManager periodic job (new dependency) writing a passphrase-encrypted ExportSnapshot to a user-picked SAF tree, retaining last N; the onboarding gate (ui/QuestLoopApp.kt:104-117) gains 'Restore from backup' using existing importJson. AiConfig is already excluded from exports.

*Roadmap overlap:* Direct: NEXT_STEPS Horizon 3 'account-less, user-owned sync (Drive app-folder / SAF / scheduled encrypted export)'. New angle: skip Drive entirely — SAF + WorkManager + a restore path ships now with zero new services.

*Critic:* Refs exact (exportJson:700, onboarding gate); WorkManager genuinely absent; manifest's allowBackup=false makes the trust gap real. Direct Horizon 3 item. Medium slightly light once passphrase UX, SAF permissions, and restore flow land.

### Visible rest days: accept the rest suggestion, streak stays safe  · impact medium / effort small · critic: **strong**

SafetyGuard raises REST_SUGGESTION as display-only text (core/safety/SafetyGuard.kt:52-61) and streak grace is invisible math (StreakTracker graceDays). Competitors headline purchasable streak freezes; QuestLoop can do it ethically: declare a rest day, see 'streak protected', get a routines-only plan. Turns the wellbeing moat into a feature users can touch.

*Integration:* Persist declared rest days in ProfileStore (long-set key); StreakTracker.currentStreak gains a protectedDays parameter (pure :core, unit-testable); 'Take a rest day' action on the Today safety banner; QuestGenerator gets a lighten flag for that day.

*Roadmap overlap:* NEXT_STEPS Horizon 2 'honesty / recovery framing made visible' — this is a concrete mechanic for that line item, beyond warmer copy.

*Critic:* REST_SUGGESTION is display-only as claimed; SafetyBanner exists for the action. Partial overlap: the energy 'Rest' option already lightens plans, shrinking work to StreakTracker protectedDays, persistence, copy. Best ethos fit on the list.

### Morning digest: due and overdue quests in the reminder, with per-quest Done  · impact medium / effort small · critic: **strong**

Reminders are two fixed generic nudges (reminders/ReminderNotifications.kt:16-19) that never mention content, while quests carry deadlines (Domain.kt:151) and the generator scores urgency. Deadline-aware notifications convert the reminder from ignorable noise into daily utility — the reason users keep notifications enabled.

*Integration:* In ReminderReceiver, query todayPlan for due/overdue items; show titles only when sensitiveNotificationsOptIn (ProfileStore.kt:86), counts otherwise; per-quest Done actions reuse the epoch-day-stamp pattern (ReminderNotifications.kt:48-49) into completeQuest.

*Roadmap overlap:* DESIGN_DIRECTION idea 3's notification 'Done' shipped for routines only; deadline digests and per-quest actions are new.

*Critic:* All line refs exact; sensitiveNotificationsOptIn is stored but never consulted by notification code, so this activates a dormant privacy flag. Receiver-with-repository and day-stamp patterns already proven. Small is realistic.

### Monthly challenge 'seasons' generated from your own ledger  · impact medium / effort medium · critic: **weak**

QuestFrequency.SEASONAL is fully scheduler-supported (core/generation/QuestScheduler.kt:42,72,107) yet nothing ever creates a seasonal quest — dormant capability. A deterministic monthly challenge derived from the user's weak spots ('8 active days across 4 categories in March') adds a fresh medium-horizon goal each month without servers, content drops, or FOMO pressure.

*Integration:* New :core SeasonFactory mirroring AdminFundFactory's derive-don't-persist pattern; month-scoped ProgressStats feeding new AchievementEngine badges; progress card on Today/Reviews. Completions flow through the existing ledger, caps, and safety rules unchanged.

*Roadmap overlap:* CURRENT_STATE lists 'seasonal events' as spec-future; new angle is local-first and personalized — generated deterministically from the user's own data, no live-ops.

*Critic:* SEASONAL refs exact but oversold: the scheduler treats it as always-eligible — no month windows — and an aggregate challenge is badge-shaped, not a completable quest. Month-end expiry also flirts with deadline pressure the product avoids.

### Optional one-line completion notes, surfaced in reviews  · impact medium / effort medium · critic: **plausible**

CompletionEntity records numbers only — no field for how it went. Subjective quests capture a 1-5 rating but no words; honesty logs lose their story. A skippable one-liner at completion gives the evening wrap-up substance and makes weekly reviews personal, deepening the reflective loop the product is built around.

*Integration:* Nullable note column on completions (Migration + [schema] regen per AGENTS.md); optional field in the completion dialogs and CompletedScreen's editor; recent notes shown in ReviewScreen. Keep notes out of AiNarrator payloads — user text must never reach the model (AiNarrator.kt:37-39).

*Roadmap overlap:* Adjacent to Horizon 3's planned mood check-in but distinct: per-completion journaling, not a per-day mood scale.

*Critic:* Verified: no note field; subjective 1-5 exists (CompletionScaling). Touches core CompletionRecord, entity, migration, dialogs, two screens — medium is right; the AiNarrator privacy rule is correctly pre-empted. Fits the reflective loop; modest impact.

### Week layout: place recurring quests onto concrete days using calendar free/busy  · impact medium / effort large · critic: **plausible**

CURRENT_STATE names per-day scheduling inside a week/month the biggest gap vs. spec: PeriodPlanner shows expected occurrence counts but never which day. Auto-placing 'swim 2x/week' onto the two freest days — FreeBusyCalculator already reads the device calendar — turns the Plan view from a forward overview into an actionable week.

*Integration:* Extend core/generation/PeriodPlanner.kt with a deterministic day-assignment pass over FreeBusyCalculator budgets; persist per-quest preferred days (small table or ProfileStore JSON); Plan view gains a day grid with tap-to-move; QuestGenerator respects assignments.

*Roadmap overlap:* CURRENT_STATE 'Biggest gaps vs. spec' names exactly this (not in any NEXT_STEPS horizon). New angle: deterministic free/busy placement first, AI-optional refinement later.

*Critic:* CURRENT_STATE indeed names this the biggest gap, but the claim misattributes: AndroidCalendarReader reads the calendar, today-only — multi-day free/busy is new surface. Large is honest. Right direction, heaviest lift proposed.

### Launcher shortcuts: quick capture and check-in from a long-press  · impact low / effort small · critic: **plausible**

No ShortcutManager usage exists anywhere in :app. Long-press-icon shortcuts ('Add quest', 'Morning check-in') cut capture to two taps, surface in launcher search, and cheaply reinforce the minimal-interaction promise for users who won't place a widget.

*Integration:* shortcuts.xml plus intent extras; MainActivity currently ignores its intent (MainActivity.kt:11-20) — parse an extra into a start route passed to QuestLoopApp to open the 'add' modal or Today's check-in.

*Critic:* Verified absent; MainActivity ignores its intent as claimed. Needs a start-route parameter threaded into QuestLoopApp — modest plumbing. Cheap and consistent with minimal interaction, honestly rated low impact.

### Shareable week card: render the review as an image  · impact low / effort small · critic: **weak**

Social modes are deliberately out of scope, but a locally rendered summary image (level ring, streak flame, completions, strongest category) shared via the system sheet gives proud users an organic-growth artifact with zero accounts, network, or data exposure — the only social feature that genuinely fits a local-first single-user app.

*Integration:* Draw ReviewGenerator.Review facts into a Bitmap (Compose graphicsLayer record or Canvas), write to cacheDir, share via FileProvider intent — same share-sheet plumbing as Settings' JSON export. One button on ui/review/ReviewScreen.kt.

*Roadmap overlap:* CURRENT_STATE lists social/party/accountability as spec-future; this shares a rendered artifact, not data, so it does not conflict with the local-first stance.

*Critic:* Integration slightly wrong: Settings shares text via EXTRA_TEXT and no FileProvider exists, so image sharing needs new provider config (still small). Product deliberately excludes social; the organic-growth hypothesis is the weakest value here.

## The review, reviewed (skeptic audit of the review itself)

### Accuracy & priorities audit

Strong review: I spot-checked ~30 findings against source (all three highs plus every medium I doubted) and each core claim reproduces — the 3d2d31e regression framing, the quantitative over-completion trap, the OAuth/token races, the release gate. Weaknesses are structural rather than factual: three dimensions filed the same N+1/candidate-pool defect, two filed the busy-flag and trampoline issues (with mismatched severities), inflating the count. A few findings relitigate documented tradeoffs (per-push :core-only CI; surfacing provider error bodies per AGENTS.md; coverage publishing from failed runs is commented as intentional in the workflow). Two recommendations conflict: architecture wants interval-slot logic moved to :core while code-quality wants it merged into :app's AppClock — resolve by moving it to :core and delegating. The trampoline finding's "commonly triggered" premise is contradicted by the routine-dismissal regression itself. Severity under-weights the weekly-habit gap and the JSON-blob wipe.

**Findings the audit flagged as over/under-weighted or duplicated:**

- *Habit-derived quests cannot meet their own weekly target (only 1 completion creditable per week)* — Rated low, but it under-credits the app's core habit mechanic: HabitQuestFactory.kt:30 maps every 2-4x/week habit to a WEEKLY quest whose own rationale text tells the user to 'aim for Nx a week' while QuestScheduler.isDue hides it for 7 rolling days after one completion. Should be medium and in the fix queue.
- *Reminder "Mark done" fallback silently dead-ends on Android 12+ (notification trampoline)* — Medium is overstated today: the claimed common trigger ('already completed in-app') cannot currently occur because the routine-dismissal regression keeps completed routines in the plan, so ReminderActionReceiver finds them and idempotently re-completes instead of falling back. It matters mainly as a companion fix to the regression. Its features-dimension twin is rated low — the two severities should agree.
- *Habit/bad-habit/goal entity lists stored as JSON blobs in DataStore; a decode failure silently and then permanently wipes them* — Medium matches the low likelihood, but the failure mode is permanent, unrecoverable user-data loss (decodeList returns emptyList on any decode error and the next read-modify-write persists the wipe) — impact-wise it belongs in the top five fixes, above several other mediums.
- *Per-push CI never compiles :app — smoke-only gate leaves a day-long blind spot (arguing the documented choice)* — Relitigates a tradeoff documented in AGENTS.md and smoke.yml's own header comment; the verifier already tagged it intentional-choice. Keep as context only — the actionable slice is the separate release-gate finding.
- *Coverage badge publishes from failed nightly runs, silently misreporting the public number* — Semi-intentional: full-tests.yml:109-110 explicitly comments that coverage steps run even when the instrumented run fails 'so we still publish coverage + report'. The genuine defect is only the badge inheriting a unit-only number; lower value than framed.

- Duplicate cluster: Busy flags set without try/finally in three ViewModels — an error strands the UI disabled / Failures leave screens stuck: busy/loading flags never reset and no screen has an error state (folded into one fix)
- Duplicate cluster: questOverview rebuilds the candidate pool 4-5 times with N+1 find() loops on every quests-flow emission / N+1 DB queries and triple recomputation in QuestRepository's per-quest status paths / N+1 per-quest find() loops re-executed 2-3x per screen refresh (folded into one fix)
- Duplicate cluster: Reminder "Mark done" fallback silently dead-ends on Android 12+ (notification trampoline) / Reminder 'Mark done' fallback uses a notification trampoline blocked on Android 12+ (targetSdk 36) (folded into one fix)
- Duplicate cluster: Economy-critical interval-slot logic lives in :app, outside the per-push :core test gate, and is duplicated in AppClock / Week/month interval math duplicated between QuestRepository and AppClock (folded into one fix)

**Suspect-finding spot checks** (claims the auditor re-verified or qualified):

- *Completed daily routines are never dismissed - they reappear in Today's plan all day (regression)* — The routine claim is fully confirmed (verified against the 3d2d31e diff and QuestGenerator.kt:129-133), but the admin-fund sub-claim is overstated: completed admin steps ARE removed from todayPlan candidates by QuestScheduler.isDue via lastCompletedDays (Daos.kt:92 filters result='COMPLETED'). Only a same-day SKIPPED admin step could resurface. Scope the fix accordingly.
- *Reminder "Mark done" fallback silently dead-ends on Android 12+ (notification trampoline)* — The trampoline blockage is real, but the stated common trigger is contradicted by another confirmed finding: because completed routines are never dismissed (the regression), repo.todayPlan still contains them and the receiver re-completes idempotently rather than reaching the startActivity fallback. The dead-end is currently a rare path; it becomes common only after the regression fix.
- *Raw transport exception text shown on the Add screen for AI failures* — Partially conflicts with AGENTS.md's documented convention to 'surface the provider's error body; never silently echo the deterministic fallback'. A fix that genericizes messages would regress that requirement; needs the both/and pattern SettingsViewModel.connectOpenAi already models (friendly copy in UI, detail in the AI error log).
- *Coverage badge publishes from failed nightly runs, silently misreporting the public number* — The always() publication is explicitly documented as intentional in the workflow itself (full-tests.yml:109-110); only the badge side effect is plausibly unintended. 'Silently misreporting' overstates a semi-deliberate design.
- *Credential store built on deprecated androidx.security-crypto (EncryptedSharedPreferences)* — Factually correct (securityCrypto 1.1.0 pinned in libs.versions.toml) but it argues against a tradeoff AGENTS.md documents as deliberate, and there is no drop-in replacement — the practical alternative is a hand-rolled Keystore wrapper with its own risk. Generic dependency-hygiene advice with low actionability.
- *Completed screen all-time filter loads, maps, and sorts the whole ledger in memory on Main* — Half right, matching its partially-correct verdict: completionDao.all() is a suspend Room call that hops off Main internally; only the map/filter/sort in completedHistory (QuestRepository.kt:387-395) runs in the caller's Main context, which is trivial until the ledger grows large. The 'loads the whole ledger' part is real; the main-thread-IO framing overstates it.
- *One-off measured quests reset progress daily, can never complete by accumulation, and over-mint XP across days* — Confirmed mechanically (completionSlot day-keys ONE_OFF measured quests), but note the in-code doc at QuestRepository.kt:262-267 declares per-day semantics for one-off/daily measured quests as a deliberate exclusion, and commit 6acdac5 documented the single-record trade-off — the fix is a design decision (interval = quest lifetime for ONE_OFF), not a plain bug patch, and the reviewer did not surface that documented intent.

### Coverage audit

Coverage is strong overall: 61 findings hit the genuine hot spots (QuestRepository, CI gating, reminders, widget, AI/OAuth stack, reward-economy correctness, docs staleness), and the thin :core finding count is defensible since :core runs per-push under a 90% coverage floor. I verified several plausible suspects are NOT gaps: Daos.kt SQL is clean and matches the ledger-idempotency design; the export/import path (ExportSnapshot.kt + QuestRepository.importJson) is merge-based, version-checked, and failure-wrapped; SafetyGuard is wired into Today; RewardsViewModel/the allowance calculator already follow the documented guard patterns; core-tests.yml and export-room-schema.yml are benign; settings.gradle.kts/gradle.properties/scripts/setup-android.sh and the manifest permission set (deliberately inexact alarms, guarded notifications) are fine. The real residual risk clusters in five places the findings never touched: the R8/minified release variant (keep rules cover only core.model serializers and no CI ever executes minified bytecode, yet release.yml ships assembleRelease the moment a keystore secret exists, gated only by :core tests), data_extraction_rules.xml (the Android-12+-authoritative twin of the backup_rules.xml finding, with identical WAL/SHM + ai_diagnostics.log gaps), sibling instances of the already-found stuck-busy defect class in ViewModels the review never enumerated (HabitsViewModel, QuestBankViewModel), a runtime reminder failure mode no dimension considered (alarms armed as wall-clock instants are never re-armed on timezone/clock change), and the plaintext-keystore default parameter that is the un-fixed root cause behind the BootReceiver finding (SecureKeyStore.kt itself was never touched).

Missed areas it named (each got a targeted follow-up review + skeptics, producing findings [59]–[74]):

- **R8/ProGuard keep-rule coverage and zero runtime validation of the minified release variant** — app/proguard-rules.pro keeps kotlinx.serialization serializers ONLY for com.questloop.core.model.**, but @Serializable classes also live in core.ai (AiQuestDto in AiQuestService.kt:15), core.ai.openai (OpenAiTokens in OpenAiOAuth.kt:79 - the persisted OAuth token bundle), and :app (OpenRouterClient request/error DTOs, ExportSnapshot). The narrowly-scoped hand-written rules plus the Tink -dontwarn suggest prior R8 breakage fixed per-symptom. No CI job ever EXECUTES minified bytecode (unit tests run unminified, emulator tests run debug; full-tests only checks assembleRelease compiles), yet release.yml lines 75-82 publish the R8-minified assembleRelease APK gated only by :core tests as soon as RELEASE_KEYSTORE_BASE64 is configured - and docs/RELEASE_SIGNING.md is the standing guide to configure exactly that. A serializer stripped by R8 means AI parsing, OAuth token persistence, or backup import crashes only in the shipped APK. The review flagged resource shrinking (performance) and gate ordering (testing) but never audited keep rules or minified runtime behavior.
- **data_extraction_rules.xml duplicates the exact exclusion gaps the review flagged only in backup_rules.xml** — The security finding about Room WAL/SHM sidecars and ai_diagnostics.log not being excluded anchored solely to app/src/main/res/xml/backup_rules.xml. But on Android 12+ the manifest's android:dataExtractionRules (data_extraction_rules.xml) is the authoritative file for BOTH cloud backup and device-to-device transfer, and it has the same holes: it excludes questloop.db but not questloop.db-wal/questloop.db-shm, and never mentions files/ai_diagnostics.log (path confirmed in FileAiDiagnostics, AiDiagnostics.kt:34,54). A fix scoped to the finding's file leaves the file that actually governs modern devices leaky - quest/habit data in the WAL and AI error logs would transfer despite the documented privacy posture.
- **The stuck-busy/stranded-in-flight defect class exists in ViewModels the review never touched (fix-phase enumeration gap)** — The review found 'Busy flags set without try/finally in three ViewModels' and 'Failures leave screens stuck', touching only Add/Completed/Today/Settings. The same shape exists in untouched files: HabitsViewModel.load() (lines 33-46) sets loading=true and resets it only on the success path - launchSafely routes any repository failure to a log, leaving the Habits screen permanently loading; QuestBankViewModel.add() (lines 51-62) adds the quest id to the `adding` set and deliberately relies on the quests-flow collector to clear it, so if repository.addFromBank throws, the id is never removed and that bank row's button is stranded in-flight for the ViewModel's lifetime. If fixes are applied per named file, these siblings survive. ReviewViewModel, AchievementsViewModel, and QuestsViewModel should be checked for the same pattern while fixing.
- **Reminder alarms are armed as fixed wall-clock instants and never re-armed on timezone or clock changes** — ReminderScheduler.schedule() computes a single RTC epoch-millis trigger via ReminderSchedule.nextTriggerMillis using ZoneId.systemDefault() at arm time, and re-arms only on fire, boot, and app-open. The manifest registers only BOOT_COMPLETED - there is no ACTION_TIMEZONE_CHANGED or ACTION_TIME_CHANGED receiver. So after DST shifts overnight or the user travels across zones, the already-armed morning/evening alarms fire at the wrong local time (potentially hours off, e.g. 3am) until the next fire/boot/app-open re-arm. The review's reminder findings covered UTC-only tests, the notification trampoline, the boot-receiver keystore, and the Settings LaunchedEffect - but no dimension considered this runtime failure mode, which is distinct from (and not fixed by) adding DST test coverage.
- **ProfileStore's SecureKeyStore default parameter is the plaintext store - the un-fixed root cause behind the BootReceiver finding - and SecureKeyStore.kt itself was never audited** — The security finding flagged the symptom (BootReceiver hand-wires ProfileStore with the plaintext test-default keystore) but not the root cause: ProfileStore.kt line 70 declares `keyStore: SecureKeyStore = DataStoreKeyStore(dataStore)`, so the insecure store is the DEFAULT and every future call site (widgets, receivers, tests promoted to prod) silently reads/writes AI credentials into plaintext DataStore keys ai_api_key_v2/openai_tokens_v1 (SecureKeyStore.kt lines 37-60). Fixing only the BootReceiver call site leaves the trap armed. Note the legacy one-time migration in ProfileStore.getAiConfig (lines 199-216) scrubs only the v1 'ai_api_key' slot - the v2/openai_tokens_v1 plaintext slots have no scrub if they were ever populated by a mis-wired call site. A fix-phase should flip the default (or remove it) and add a scrub for the v2 slots; SecureKeyStore.kt (the actual credential file) had zero findings anchored to it.

### On the zero-refuted rate

No finding was refuted outright. Two independent reasons make this credible rather than suspicious: reviewers were required to anchor every claim in file:line citations they had read (generic advice was banned), and the skeptic-in-chief independently re-checked ~30 findings — including every high — reproducing each core claim. Where skeptics found overstatement they said so: 11 findings carry corrections, one was reclassified as a documented tradeoff, and several severity ratings moved in both directions.


---

## Implementation status (applied on `claude/codebase-review-multi-agent-0hnmfy`)

The fix wave was implemented by 40 per-fix agents (one isolated worktree each) with a
dedicated reviewer per patch. **37 of 40 fixes are applied** to this branch; each was
generated against the reviewed tree and applied in order with conflicts resolved by
hand. Several conflicts were genuine semantic merges, not pick-a-side — notably:

- **W19 ↔ W04** — W19 moved the interval-slot math to a new `:core` `CompletionSlots`
  authority, but its moved copy used the base one-off semantics and would have silently
  reverted W04's fix ([52]). W04's `"oneoff"` lifetime-slot behaviour was ported into
  `CompletionSlots.completionSlot` (now returns the slot token as a `String`) and its
  `:core` tests updated, so both the refactor and the correctness fix survive.
- **W38 ↔ W33** — the "show skips in history" feature ([40]) and the "map the Completed
  slice off the main thread" perf fix ([36]) both rewrote `completedHistory`; merged so
  the SKIPPED-inclusion runs inside the `historyDispatcher` `withContext`.
- **W05/W06, W15/W08, W16, W26/W03, W28/W27, W36/W20, W37** — reconciled by hand
  (helper-extraction vs inline, added constructor params, union-of-imports, both-sides
  test additions, injected clock).

**W02** (privacy copy, [39]) was rejected by its reviewer for over-claiming that existing
quest titles are sent to the AI provider — they are not (dedup runs on-device in
`AiQuestValidator`). It was re-implemented by hand to disclose the opt-in upload
accurately: only the to-dos and goals the user types are sent.

### Deferred to a clean follow-up (3)

These were skipped rather than force-merged, because each collides with a higher-priority
fix in code where a botched merge is worse than a temporary gap. All remain tracked here:

- **W21 — N+1 `DayContext` refactor ([4]/[11]/[34], perf, medium).** Eight conflict hunks
  through the economy-critical status/dismissal code that the correctness fixes (W01/W19)
  already rewrote. Deferred so it can be rebuilt cleanly on top of them; no behaviour
  change is lost, only a performance optimization.
- **W29 — Mark-done/double-tap/snackbar pinning tests ([21]/[22], test-only).** Add/add
  conflict with W11's rewrite of the same receiver test; W29's "open-the-app fallback"
  test encodes receiver behaviour that contradicts W11's trampoline fix. W11's receiver
  tests remain in place.
- **W31 — OAuth forged-callback CSRF guard ([31], low, partially-correct).** Five
  interleaved conflict regions with W15's cancellation-aware rewrite of the same
  `signIn`/`awaitCallback`/`acceptOnce` methods in security-critical auth code. W15 (the
  confirmed, higher-severity fix) is applied; W31's state-validation is deferred to
  reimplement on top of it rather than risk a hand-merge of two auth rewrites.

### Validation

`:core:test` and `:app:testDebugUnitTest` pass (592 tests, 0 failures) and `:app:lintDebug`
is clean. The wave's tests were written but never executed by the implementer agents (they
were sandboxed off gradle), so the first full `:app` run surfaced test-only failures in the
new tests (a `JobCancellationException` from `db.close()` that `launchSafely` correctly
rethrows, a Robolectric `AndroidKeyStore` gap exposed by W32's encrypted-default change,
Robolectric broadcast delivery, a lint `RegisterReceiverFlag` error) — all fixed, no
production bug among them.

A **combined-diff re-review** (5 risk-focused reviewers over the full changeset + one
adversarial skeptic per finding) then hunted for regressions introduced by stacking and the
hand-merges. It surfaced 3 low-severity issues, all fixed: (1) dead `CompletionDao` queries
orphaned by the W33↔W38 merge (deleted); (2) `OpenAiAuthService.postForm` laundering a
cancelled token refresh into a spurious "sign-in expired" error — the W30 rethrow convention
hadn't reached the app layer (fixed with the `runCatchingCancellable` idiom); (3) the W02
privacy copy still saying "nothing else" when the AI request also sends the time budget and
focus areas (reworded). CI (`[uitest]`) runs the app build, lint, R8 release, and the
emulator suite as the authoritative gate.
