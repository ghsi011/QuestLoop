# Design Decisions

Answers to the spec's open design questions (§12), plus self-review notes and
deferred work. These reflect MVP choices, not permanent commitments.

## Answers to open design questions

1. **Game vs productivity tool?** A productivity tool with a light game layer.
   XP/levels/streaks motivate, but quests map 1:1 to real tasks and the UI leads
   with the task, not the points.
2. **How is difficulty calculated?** A small fixed ladder (TRIVIAL→EPIC) chosen
   by the user/AI, each with a base XP and a reward weight. Simple, predictable,
   hard to game. Difficulty is separate from priority.
3. **XP ↔ real-world reward value?** Decoupled. XP drives in-app progression;
   the real-world allowance is a separate, difficulty-weighted % of the user's
   own budget. Money is never minted from XP.
4. **Preventing farming?** Same-day repeat decay, meta-maintenance daily cap,
   difficulty-weighted progress, and honesty rewards. See `REWARD_ECONOMY.md`.
5. **Bad-habit relapse without shame?** Relapse logged honestly is never
   penalised; it earns small honesty XP with supportive framing.
6. **Automation vs control?** AI/auto-generation proposes; the user disposes.
   Everything is editable/rejectable; generation is transparent and explainable.
7. **How much AI explanation?** Every generated quest carries a short rationale;
   every XP change returns a plain-language explanation.
8. **Essential MVP integrations?** None. MVP is local-first manual entry +
   quick-add. Calendar/health/todo integrations are explicitly future work.
9. **Narrative framing?** Minimal and practical for MVP ("quests", "level"),
   with room to add storylines later. No forced narrative.
10. **Rest/recovery/low-energy days?** First-class: energy check-in shrinks the
    plan and caps difficulty; `SafetyGuard` suggests rest and recovery mode;
    streak grace days protect against single misses.
11. **Custom reward rules?** MVP supports a user-set affordable budget cap and
    suggested allowance; richer custom rules are deferred.
12. **Sensitive goals/health/finances?** Local-first storage, opt-in sensitive
    notifications, no medical/financial advice, money never handled in-app.
13. **Local vs cloud data?** All local in the MVP (Room + DataStore); backups
    off by default. Cloud sync is future work and would be opt-in.
14. **Social/accountability?** Private by default; social/party/household modes
    are future features.
15. **Monthly reward reviews without overspending?** Allowance is a % of a
    self-set affordable cap, never exceeds it, is reduced by missed critical
    tasks, and always ships with affordability disclaimers.
16. **Which features need research first?** Reward/anti-farm balance, habit
    formation, healthy gamification, relapse handling, and AI fairness — the
    rules encoded in `RewardConfig`/`SafetyGuard` are the research surface and
    are isolated so they can be tuned as research lands.
17. **Minimum test coverage for critical systems?** Reward economy, quest
    scoring, completion/fairness and safety logic carry direct unit tests (63
    tests today). These are the gate; new economy/safety behaviour must ship
    with tests.
18. **Evaluating AI quest quality before release?** Versioned prompts + a
    guardrail validator with test cases for shaming, financial/medical advice,
    duplicates, and unrealistic estimates; deterministic fallback as a floor.
19. **Motivating-but-not-exploitative mechanics?** Capped consistency bonus,
    gentle capped penalties, anti-farm decay, meta caps, honesty rewards — all
    chosen to avoid compulsive/loss-aversion loops.
20. **Preventing feature creep while allowing research?** The module split and
    `RewardConfig` confine change to small, tested surfaces; the MVP scope list
    (README) is the contract, and deferred work is tracked below.

## Self-review notes

- **Determinism:** generation and scoring avoid randomness so behaviour is
  reproducible and testable; time is injected as `epochDay`.
- **Single source of economy truth:** all weights/caps in `RewardConfig`; no
  magic numbers scattered through the app.
- **Failure modes considered:** empty candidate pool (always offer one easy
  quest), zero budget (clear guidance, no allowance), AI unavailable (fallback),
  penalty/meta caps (no spiral, no farming), XP underflow (clamped).
- **Edge cases tested:** anti-farm floor, streak grace boundaries, level
  inversion at every boundary, missed critical tasks, partial completion.

## Known limitations & deferred work

- The `:app` module is not compiled in the authoring sandbox (no Android SDK /
  Google Maven); it is validated by CI's `android-build` job.
- Achievements/badges/titles/collections are scaffolded conceptually but not yet
  surfaced as a dedicated screen.
- AI quest generation currently runs through the deterministic `FallbackSuggester`
  behind the same guardrails; wiring a live model is a drop-in at the app/server
  layer using `PromptLibrary`.
- No calendar/health/todo integrations, no cloud sync, no social modes (all
  future per SPEC §10).
- Instrumented UI tests are minimal; ViewModel/Compose tests are deferred.
- Reward weights are first-pass defaults pending the research in §16/§17.
