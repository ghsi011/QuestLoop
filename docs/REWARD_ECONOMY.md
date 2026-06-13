# Reward Economy

This is the most safety-sensitive system in QuestLoop. Every rule below maps to a
fairness principle from the product spec (§6, §8) and is enforced by unit tests
in `core/src/test/.../reward`.

All tunables live in `RewardConfig` so the economy can be rebalanced in one place.

## XP for a completed quest

```
xp = difficultyBaseXp × fraction × priorityMultiplier × consistencyBonus × antiFarmMultiplier
```

| Factor | Source | Range |
|--------|--------|-------|
| `difficultyBaseXp` | `Difficulty` (TRIVIAL 5 → EPIC 60) | fixed per tier |
| `fraction` | 1.0 for completed, 0..1 for partial | 0–1 |
| `priorityMultiplier` | `Priority` (LOW 0.8 → CRITICAL 1.6) | 0.8–1.6 |
| `consistencyBonus` | streak length, capped | 1.0–1.25 |
| `antiFarmMultiplier` | same-day repeats of the same quest | 0.1–1.0 |

**Harder and more important tasks are worth more** — but neither factor can run
away (priority caps at 1.6×, difficulty is a fixed small ladder).

## Anti-farming

The exploit we defend against is repeating the *same* quest many times in *one
day* to mint XP. Each prior same-day completion of that quest multiplies reward
by `antiFarmDecay` (0.55) raised to the repeat count, with a floor of 0.1.

Crucially, anti-farm is **same-day only**. Completing a daily habit across
consecutive days is *consistency*, not farming, and is rewarded (and earns the
streak bonus). This distinction is enforced in `QuestLoopEngine.deriveContext`
and tested in `QuestLoopEngineTest`.

## Consistency bonus

`consistencyBonus = 1 + maxConsistencyBonus × streak / (streak + halfLife)`

A smooth curve that reaches +12.5% at a 7-day streak and asymptotes at +25%. It
is **capped** specifically so there is no escalating pressure to never miss a day
(which would create burnout / shame dynamics).

## Meta-maintenance cap

Adding todos, reviewing, updating the schedule etc. are `META_MAINTENANCE`
quests. They are rewarded *lightly* and capped at **30 XP/day** total. This
guarantees that maintaining the system can never out-earn real-world progress
(SPEC §6, §7). Enforced in `RewardEngine.scorePositive` and via derived context.

## Gentle, capped penalties

- A genuinely skipped/failed non-reduction quest costs a small **−3 XP**.
- Total penalties are capped at **−10 XP/day** — after that, misses cost nothing,
  so a bad day can never spiral into a shame hole (SPEC §9).
- XP can never go negative (clamped at 0 in `QuestLoopEngine`).

## Bad-habit honesty

A bad-habit-reduction quest marked failed = an honest relapse log. This is
**never punished**. Instead it grants a small **+3 honesty XP** with supportive
framing ("recovery and consistency matter more than perfection"). This removes
the incentive to lie and aligns rewards with the behaviour we actually want:
honest tracking.

## Rescheduling

Rescheduling is neutral (0 XP, no penalty). Adjusting plans is part of the
system, not a failure.

## Levels

`LevelSystem` uses a quadratic total-XP curve: the gap from level *L* to *L+1*
is `100 × L`. Early levels come fast (encouraging), later levels are meaningful.
`levelForXp` is the exact inverse of `xpForLevel`, verified at every boundary.

## Real-world reward allowance

`RewardAllowanceCalculator` suggests how much of the user's **own, self-set,
affordable** monthly budget they've "earned":

```
earnedFraction = clamp( completionRate×0.7 + consistency×0.3 − criticalMissPenalty , 0, 1 )
suggestedAllowance = budgetCap × earnedFraction
```

- `completionRate` is **difficulty-weighted** (epics count more than trivials).
- Each missed CRITICAL task reduces the fraction by 5% (capped at 30%).
- The result **never exceeds the user's budget cap**.

Four disclaimers are returned with **every** result and are non-removable:
not financial advice; the app holds/moves/invests nothing; the user controls all
money externally; only set aside what's affordable. The app provides *admin
quests* to help users set up an external fund — it never handles money itself.
