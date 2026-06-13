# Code Review — QuestLoop

A correctness- and design-focused review of the current code. Findings are
grouped by severity with `file:line` references, impact, and a suggested fix.
This is a living document; check items off as they're addressed.

## Summary

The pure-Kotlin `:core` engine is solid, deterministic, and well-tested. The
highest-value issues are in the **app data layer** (`QuestRepository`) around
completion idempotency, XP/ledger consistency, and how partial completion
interacts with incremental (quantitative) logging. None block the MVP, but a few
are real correctness/abuse bugs worth fixing before the economy is trusted.

---

## High — correctness / data integrity

### H1. Partial completion removes the quest for the rest of the day
`app/.../data/QuestRepository.kt:79-83` — `completedQuestIdsToday` treats both
`COMPLETED` **and `PARTIAL`** as "done", and `todayPlan` filters those out
(`:65`). So logging 3/8 glasses (a `PARTIAL`) makes the quantitative quest
disappear; you can't log the remaining 5 later.
**Impact:** breaks the core use case for QUANTITATIVE/DURATION quests.
**Fix:** for non-binary styles, don't treat partial as terminal — keep the quest
visible and accumulate progress, or only exclude when `fraction >= 1.0`.

### H2. Re-completing the same quest the same day double-awards XP
`app/.../data/QuestRepository.kt:104-119` — `instanceId = "${quest.id}@$epochDay"`
with `@Insert(OnConflictStrategy.REPLACE)` (`data/local/Daos.kt`). A second
completion of the same quest on the same day **overwrites the record** but XP was
already added to the total on the first call, and the second call adds more — net
double-count, with the ledger showing only one record. The generator usually
hides completed quests, but a double-tap or a re-log of a measured quest bypasses
that.
**Impact:** XP inflation / farming vector; ledger and total XP disagree.
**Fix:** make completion idempotent per `instanceId` — if a record exists,
reconcile by subtracting its stored `xpAwarded` before applying the new grant
(or reject re-completion). Combine with H1 for incremental logging.

### H3. XP and the completion ledger are written non-atomically
`app/.../data/QuestRepository.kt:118-119` — `completionDao.insert(...)` (Room)
then `profileStore.setTotalXp(...)` (DataStore) are two separate writes with no
transaction. A crash between them desyncs total XP from history, and there's no
reconciliation path.
**Impact:** durable XP drift.
**Fix:** treat the ledger as the source of truth and derive total XP as
`sum(xpAwarded)` (cache it), or wrap both writes so they can be recomputed.

---

## Medium — design / performance / fairness

### M1. Reward context approximates "already earned today" by re-scoring
`core/.../QuestLoopEngine.kt:70-80` — `metaXpEarnedToday` / `penaltyXpAppliedToday`
are recomputed by re-scoring same-day records with a **neutral** `RewardContext`,
ignoring the streak/anti-farm multipliers that actually applied. The real granted
XP is already stored as `CompletionEntity.xpAwarded`.
**Impact:** the meta-XP daily cap and penalty cap are enforced against
approximate, not actual, totals — they can drift.
**Fix:** pass the real per-day totals from stored `xpAwarded` into the context
instead of re-deriving them in the engine.

### M2. Every call loads the entire completion table
`app/.../data/QuestRepository.kt:66,103` (and `safetySignals`, `statsFrom`) call
`completionDao.getAll()` and map all rows on each plan build / completion.
**Impact:** O(history) work that grows unbounded with use.
**Fix:** add windowed queries (e.g. last 14–30 days) for context/safety/streak;
reserve `getAll()` for places that truly need the full history.

### M3. Incremental quantitative logging is penalised as farming
`core/.../QuestLoopEngine.kt:65-69` — `priorSame` counts same-day `PARTIAL`
records, so a second progress log of the same quest is hit with anti-farm decay.
Combined with H1, incremental logging is both blocked and (if unblocked)
decayed.
**Fix:** for non-binary quests, model repeated logs as a single accumulating
completion (update the existing record) rather than independent completions.

### M4. AI guardrail uses naive substring matching
`core/.../ai/AiQuestValidator.kt` (`bannedSubstrings`) — substring contains can
false-positive (e.g. `crypto` ⊂ `cryptography`, `diagnos` ⊂ `diagnostic`,
`loan`-style overlaps).
**Fix:** match on word boundaries (regex `\bword\b`) and keep the list
configurable; add tests for the false-positive cases.

### M5. Energy check-in is transient and chips don't reflect selection
`app/.../ui/today/TodayScreen.kt` (`EnergyOption` always `selected = false`) and
`TodayViewModel` keeps energy only in in-memory UI state.
**Impact:** selection isn't shown; the choice is lost on process death.
**Fix:** hoist the selected energy into state and reflect it; optionally persist
today's check-in.

### M6. `levelForXp` relies on floating-point sqrt
`core/.../reward/LevelSystem.kt` — the inverse uses `sqrt`; tested to level 50,
but double precision could cause an off-by-one at very large XP.
**Fix:** clamp/verify the result against integer `xpForLevel` bounds (±1).

---

## Low — nits / maintainability

- **L1.** `Divider` is deprecated (CI warning) in `AddQuestScreen.kt:~122` →
  use `HorizontalDivider`.
- **L2.** `QuestRepository.headerState()` is unused public API — remove or use.
- **L3.** `Mappers.kt` safe-parses only `completionStyle` (`runCatching`) but not
  the other enums; a corrupt stored value for category/difficulty/etc. would
  throw. Make enum parsing consistent (all safe, or rely on DB integrity and
  document it).
- **L4.** Manual DI is fine, but consider a tiny interface over `QuestRepository`
  to enable ViewModel unit tests with a fake.

---

## Test coverage gaps

- **`QuestRepository` is untested** — yet it holds real logic (the day filter in
  H1, idempotency in H2, the approximation in M1). Add JVM tests with in-memory
  fakes for the DAOs/store, or Room in-memory + Robolectric.
- **No ViewModel tests** (`TodayViewModel` orchestration, toast/level-up text).
- `:core` coverage is good (economy, generation, safety, scaling, achievements,
  scenarios). Keep new economy/safety behaviour test-gated.

## What's good (keep doing)

- Clean module split; `:core` is pure, deterministic, and CI-verifiable without
  an emulator.
- Economy rules are centralised in `RewardConfig` and covered by property-style
  scenario tests.
- Every XP change and generated quest carries a human-readable explanation.
- Safety/anti-abuse and privacy defaults are first-class, not afterthoughts.

## Suggested order of work

1. H1 + H2 + M3 together (rework completion to be idempotent and to accumulate
   non-binary progress) — this is one coherent change in `QuestRepository` +
   `completedQuestIdsToday`.
2. H3 / M1 (make the ledger the source of truth for XP and per-day totals).
3. M2 (windowed queries).
4. M4–M6, then the Low items.
5. Add `QuestRepository` tests alongside (1)–(2).
