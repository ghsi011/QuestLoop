# Research-Informed Design Notes

SPEC §11 asks that several areas be informed by research before implementation.
This document summarises the established behavioural-design principles that shaped
QuestLoop's MVP and maps each to where it lives in the code. These are design
rationale, not a literature review; they are intentionally conservative so the
MVP stays simple while avoiding known harms.

## 1. Motivation: autonomy, competence, relatedness

Durable motivation comes from intrinsic drivers (self-determination theory), not
just points. Over-reliance on extrinsic rewards can *crowd out* intrinsic
motivation.

**Implications applied:**
- **Autonomy:** AI proposes, the user disposes. Everything is editable/rejectable
  (`AiQuestValidator` sanitises but never forces; generation is transparent).
- **Competence:** a smooth early level curve and an "always offer one approachable
  quest" rule (`QuestGenerator`) keep wins reachable.
- **Quests map to real life**, so points reinforce real progress rather than
  replacing it (meta caps in `RewardEngine`).

## 2. Habit formation: cue → routine → reward, small and consistent

Habits form through consistent, low-friction repetition with a satisfying close.
Streaks help, but rigid streaks create fragility and all-or-nothing thinking.

**Implications applied:**
- Low-friction completion; daily habits are first-class.
- **Streaks with grace days** (`StreakTracker`) so one miss doesn't reset weeks.
- Consistency is rewarded across days (anti-farm is same-day only), so a daily
  habit earns its bonus instead of being penalised as a "repeat".

## 3. Healthy gamification vs. addictive patterns

Variable-ratio reward schedules and loss-aversion mechanics (streak-loss dread,
FOMO) are exactly what makes some apps compulsive. We deliberately avoid them.

**Implications applied:**
- **Deterministic, legible rewards** — no randomised loot/variable payouts.
- **Capped** consistency bonus and **capped, gentle** penalties — no escalating
  pressure, no streak-loss dread spiral (`RewardConfig`).
- `SafetyGuard` actively flags overdrive and suggests rest — the opposite of
  engagement-maximising design.

## 4. Anti-farming / reward-economy integrity

Any points economy invites min-maxing. The fix is to make low-effort repetition
non-dominant without punishing legitimate use.

**Implications applied:**
- Same-day repeat **diminishing returns** with a floor.
- **Difficulty-weighted** progress and allowance so trivial spam can't dominate.
- **Meta-maintenance daily cap** so "managing the system" can't out-earn doing
  the work. Validated by `ScenarioTest`.

## 5. Behaviour change & relapse (good habits up, bad habits down)

Effective behaviour-change approaches (and harm-reduction practice) treat lapses
as normal and emphasise self-monitoring and self-compassion; shame is
counter-productive and increases avoidance.

**Implications applied:**
- Bad-habit relapse logged honestly is **never penalised**; honesty earns a small
  reward with supportive framing (`RewardEngine.scoreMissOrRelapse`).
- "Recovery mode" framing when miss-rate is high (`SafetyGuard`).
- No medical advice; reduction quests centre on *tracking*, not willpower-shaming.

## 6. Safety: overwork, over-optimization, restriction

Gamified self-improvement can tip into compulsion, overexercise, or restriction.
Guardrails should notice and gently intervene.

**Implications applied:**
- Rest suggestions after long active streaks; overdrive warnings on very heavy
  days; energy check-ins shrink the plan and cap difficulty.
- No mechanics that reward extremes (no "more is always better" scaling).

## 7. Privacy & sensitive data

Habit/health/financial-adjacent/behavioural data is sensitive; minimisation and
local-first storage reduce risk and build trust.

**Implications applied:**
- Local-first (Room + DataStore); backups off by default; opt-in sensitive
  notifications; small schema; archivable data. See `SAFETY_AND_PRIVACY.md`.

## 8. AI personalization: fairness, explainability, hallucination control

LLM features in a product need guardrails, explainability, and graceful
fallback — treat output as untrusted input.

**Implications applied:**
- Versioned prompts (`PromptLibrary`) with the rules baked in.
- Output guardrails (`AiQuestValidator`): block shame/financial/medical content,
  clamp unrealistic estimates, dedupe.
- **Deterministic fallback** (`FallbackSuggester`) when the model is unavailable
  or output is unusable. Every quest carries a rationale; every XP change has an
  explanation.

## Money & rewards (financial safety)

The app never holds, moves, or invests money. It suggests a share of the user's
**own, self-set, affordable** budget, difficulty-weighted and capped, always with
non-removable disclaimers (`RewardAllowanceCalculator`). This sidesteps financial
regulation and the harm of encouraging overspending.

## Open questions deferred to future research

These were intentionally left as tunable defaults (in `RewardConfig` /
`SafetyGuard.Config`) so they can be calibrated with real usage data and deeper
study without code restructuring:

- Exact XP curve steepness and the consistency-bonus cap.
- Anti-farm decay rate and floor.
- Safety thresholds (rest streak length, overdrive count, rough-patch rate).
- Allowance blend weights (completion vs. consistency) and critical-miss penalty.
