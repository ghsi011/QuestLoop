# Architecture

## Goals

1. **Product-critical logic is independent of Android** so it can be tested
   deterministically and reused (web, server, future platforms).
2. **The reward economy is pure and explainable** — every XP change comes with a
   human-readable reason.
3. **Persistence is the app's concern, not the engine's** — `:core` never does
   I/O; the app passes state in and stores results out.

## Modules

### `:core` (pure Kotlin/JVM)

Package granularity (one line per package, so this stays truthful as files are
added — see the source tree for the full list):

```
com.questloop.core
├── model/        immutable domain data: Quest, CompletionRecord, habits, goals, enums
├── reward/       RewardEngine (XP scoring, anti-farm, penalties, caps), LevelSystem,
│                 StreakTracker, AchievementEngine, RewardAllowanceCalculator
├── generation/   QuestGenerator (daily plan), QuestScheduler (recurrence cadence),
│                 PeriodPlanner (week/month plan), Habit/Routine/AdminFund quest factories
├── completion/   CompletionScaling: non-binary results → reward fractions
├── calendar/     FreeBusyCalculator: calendar events → free minutes for budgeting
├── safety/       SafetyGuard: rest/overdrive/recovery signals
├── review/       ReviewGenerator: weekly/monthly aggregation
├── ai/           PromptLibrary (versioned prompts), AiQuestService (LLM orchestration,
│                 parse tolerant of chatty output), AiQuestValidator (guardrails +
│                 FallbackSuggester), AiNarrator + NarrationSanitizer, LlmClient interface
│   └── openai/   ChatGPT-login protocol bits, no I/O: OAuth/PKCE + Responses/SSE codec
└── QuestLoopEngine.kt  facade: derives RewardContext from history
```

Determinism is a design rule: `QuestGenerator` uses scoring + explicit budgets
instead of randomness, so generation is reproducible and testable. Time enters
the system only as `epochDay` values passed in by the caller.

### `:app` (Android)

```
com.questloop.app
├── QuestLoopApplication.kt   builds the AppContainer
├── MainActivity.kt           Compose entry point
├── di/AppContainer.kt        manual DI (no annotation processor)
├── data/
│   ├── local/                Room: Entities, DAOs, QuestLoopDatabase (+ migrations)
│   ├── QuestRepository.kt    single source of truth; wires DB + engines + AI clients
│   └── …                     Mappers, ProfileStore (DataStore), SecureKeyStore
│                             (Keystore-encrypted AI credentials), the per-provider
│                             LLM clients (OpenRouterClient; OpenAiClient +
│                             OpenAiAuthService for the ChatGPT sign-in), AI call
│                             guard/diagnostics, calendar readers, data export,
│                             reminder config, sample data
├── reminders/                AlarmManager scheduling + receivers (fire, boot,
│                             notification "Mark done") for daily reminders
├── util/                     small shared helpers
├── widget/                   Glance home-screen widget
└── ui/
    ├── theme/                Material 3 theme
    ├── components/           shared composables
    ├── QuestLoopApp.kt       Scaffold + bottom nav + NavHost
    ├── ViewModelFactory.kt   wires ViewModels to the repository
    └── <feature>/            screen + ViewModel per feature: today, add, quests
                              (+ quest bank), achievements, completed, habits,
                              review, rewards, settings, onboarding
```

## Data flow

1. **Read**: Room `Flow`s and DataStore `Flow`s are mapped to core models in
   `QuestRepository` and exposed to ViewModels, which expose `StateFlow` UI state.
2. **Generate**: `TodayViewModel` asks the repository for a daily plan; the
   repository feeds active quests + history + an optional energy check-in into
   `QuestGenerator`.
3. **Complete**: completing a quest calls `QuestRepository.completeQuest`, which
   builds a `CompletionRecord`, scores it with `QuestLoopEngine` (which derives
   the `RewardContext` from history), persists the record + new XP, and returns a
   `CompletionEffect` (XP delta, level-up flag, explanation) for the UI to show.

## Persistence choices

- **Room** for quests and completion history (relational, queryable by day).
- **DataStore Preferences** for scalar profile/preferences (XP, budget cap,
  max daily quests). Keeps simple reactive key/value state out of the schema.
- Enums are stored as `Enum.name` (stable across reordering), not ordinals.

## Dependency injection

Manual (`AppContainer`) rather than Hilt/Dagger. Rationale: fewer moving parts,
no annotation-processing build step beyond Room's KSP, and the wiring is small
enough that a framework adds more cost than benefit at MVP scale. ViewModels
receive the repository via a single `appViewModelFactory`.

## Why the module split also solves a build constraint

The economy must be verifiable without an emulator. Because `:core` has zero
Android dependencies, `./gradlew :core:test` runs on any JDK. Gradle
configuration-on-demand ensures running `:core:test` never configures `:app`, so
the Android Gradle Plugin (Google Maven) is not required for the logic gate.
