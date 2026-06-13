# QuestLoop

A gamified, quest-based todo & habit system for Android that turns real-life
tasks, habits, goals and behaviour change into a personalised quest system —
motivating without being manipulative, punishing, or financially pressuring.

This repository implements the MVP described in the product spec, with a strong
emphasis on a **testable, safe reward economy**.

## Architecture at a glance

QuestLoop is a Gradle multi-module project deliberately split so that the
product-critical logic is decoupled from Android and fully unit-tested:

| Module  | What it is | Builds without Android SDK? |
|---------|------------|------------------------------|
| `:core` | Pure Kotlin/JVM. All business logic: reward economy, quest generation, completion/fairness, safety rules, reviews, AI guardrails. | ✅ Yes |
| `:app`  | Android app (Jetpack Compose UI, Room, DataStore) that depends on `:core`. | ❌ Needs the Android SDK + Google Maven |

The split is intentional (SPEC §11: *"clear separation between product logic, AI
prompts, data models, and UI behavior"*). Anyone can run the entire economy test
suite with a plain JDK — no emulator, no SDK.

```
:app  ──depends on──▶  :core
 │                       │
 UI (Compose)            RewardEngine, QuestGenerator, SafetyGuard,
 Room + DataStore        LevelSystem, StreakTracker, ReviewGenerator,
 ViewModels              RewardAllowanceCalculator, AI guardrails
```

## Building & testing

### Run the core logic tests (no Android SDK required)

```bash
./gradlew :core:test
```

This runs the full economy / generation / safety suite (63 tests) on a plain
JVM. It is the primary correctness gate and is what the `core-tests` CI job runs.

### Build the Android app

```bash
./gradlew :app:assembleDebug
```

Requires the Android SDK and access to Google's Maven repository
(`dl.google.com`) for the Android Gradle Plugin and AndroidX. CI does this in
the `android-build` job on a standard GitHub-hosted runner.

> **Note on the development sandbox:** the environment this MVP was authored in
> had no Android SDK and blocked `dl.google.com`, so only `:core` is compiled
> and tested locally there. The `:app` module is written to standard AGP 8.5 /
> Compose conventions and builds in CI / any normal Android dev environment.
> Configuration-on-demand keeps `:core:test` from ever needing Google Maven.

## What's implemented (MVP scope, SPEC §10)

- Manual todo/habit entry and a "quick add from a list" path
- **Minimal daily loop**: one instant morning review micro-quest and a short
  (~2 min) evening wrap-up (review completed + add todos / quick check-in)
- **Non-binary completion**: binary, quantitative (6/8 glasses), duration
  (20/50 focus minutes), and subjective (self-rated) quests — progress is always
  credited proportionally, never penalised
- Daily quest generation with time + energy budgeting and category variety
- XP, a smooth level curve, streaks with grace days, achievements scaffolding
- Reward economy with **anti-farming**, **gentle capped penalties**,
  **meta-maintenance caps**, and **bad-habit honesty rewards**
- Weekly & monthly review screens
- User-managed real-world reward planning with a suggested allowance and
  **mandatory, non-removable disclaimers** (the app never touches money)
- Safety signals: rest-day suggestions, overdrive warnings, recovery-mode framing
- AI prompt library + output guardrails + deterministic fallback

## Documentation

- [`docs/CURRENT_STATE.md`](docs/CURRENT_STATE.md) — honest map of what's implemented vs. spec
- [`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md) — review findings and suggested order of work
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module layout, data flow, persistence
- [`docs/REWARD_ECONOMY.md`](docs/REWARD_ECONOMY.md) — how XP, anti-farming, caps and allowances work
- [`docs/SAFETY_AND_PRIVACY.md`](docs/SAFETY_AND_PRIVACY.md) — safety signals, anti-abuse, privacy posture
- [`docs/DESIGN_DECISIONS.md`](docs/DESIGN_DECISIONS.md) — answers to the spec's open design questions
- [`docs/RESEARCH_NOTES.md`](docs/RESEARCH_NOTES.md) — behavioural-design principles behind the MVP and where they live in code

## Project status

This is an MVP foundation. Known limitations and deferred work are tracked in
[`docs/DESIGN_DECISIONS.md`](docs/DESIGN_DECISIONS.md#known-limitations--deferred-work).
