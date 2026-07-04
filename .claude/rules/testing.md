---
paths:
  - "**/test/**"
  - "**/androidTest/**"
---

# Testing conventions

How tests are wired in this repo. (The reward/data invariants the tests protect
live in AGENTS.md — don't restate them here.)

## Where tests run
- `:core` (`core/src/test`, `kotlin-test`): pure JVM, runs locally and on every
  push via `smoke.yml`. The default home for logic / economy / generation /
  AI-parse / safety tests.
- `:app` unit (`app/src/test`, JUnit4 + Robolectric + MockWebServer): runs in
  `full-tests.yml` (manual / nightly / release) and as `release.yml`'s
  pre-publish gate — **not** on every push.
- `:app` instrumented (`app/src/androidTest`): emulator only, in `full-tests.yml`'s
  emulator job (manual / nightly / release, or a commit with `[uitest]`).

## ViewModel unit tests (`app/src/test/.../ui`)
- Swap the Main dispatcher: `Dispatchers.setMain(UnconfinedTestDispatcher())` in
  `@Before`, `resetMain()` in `@After` (or the shared rule).
- Back the repository with the in-test `FakePrefs : ProfilePreferences` and an
  in-memory Room DB (`Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`).
- Assert on the collected `uiState` StateFlow value, not on side effects.

## Repository tests (`app/src/test/.../data`)
- Robolectric for the Android `Context`; in-memory Room; `FakePrefs` for prefs.
- Cover the derived-quest path (habits/goals via `HabitQuestFactory.deriveAll`),
  not just rows in the `quests` table.

## OpenRouter / network
- Use `MockWebServer`; assert the request carries auth + model and that provider
  error bodies surface (never silently fall back to the deterministic suggester
  as if it were AI output).

When adding a feature, prefer a `:core` test (fast, always-gated) over an `:app`
test whenever the logic can live in `:core`.
