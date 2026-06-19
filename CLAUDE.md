# QuestLoop — Claude Code guide

Project conventions, gotchas, and the full CI/release workflow live in AGENTS.md.
It's imported below so it loads in Claude Code too:

@AGENTS.md

## Build & test

Gradle multi-module project. **Both modules build locally** once you've run
`scripts/setup-android.sh` (installs JDK 17 + a minimal Android SDK). The only
thing that can't run locally is the emulator suite — no `/dev/kvm` in the
sandbox — so validate Compose/UI behavior through CI's emulator job.

- Core logic: `./gradlew :core:test`
- App unit tests — JUnit + Robolectric + MockWebServer: `./gradlew :app:testDebugUnitTest`
- Debug APK: `./gradlew :app:assembleDebug`
- Lint: `./gradlew :app:lintDebug`
- Instrumented UI on an emulator (CI only): `./gradlew :app:connectedDebugAndroidTest`

CI layout: **`smoke.yml`** runs the `:core` suite on every push/PR (fast gate,
~1 min). **`full-tests.yml`** runs app build + lint + unit + emulator UI + merged
coverage — manually, nightly, and on release (NOT per-push), or push a commit with
`[uitest]` to run it on demand. `[release]` cuts a release, `[schema]` regenerates
the Room schema. Details in AGENTS.md.

## graphify — query the knowledge graph first

This project has a knowledge graph (god nodes, community structure, cross-file
relationships). It is **generated, not committed** — build/refresh it locally with
the `/graphify` skill, and run `graphify update .` after changing code (AST-only,
no API cost).

- For codebase questions, first run `graphify query "<question>"` when
  `graphify-out/graph.json` exists. Use `graphify path "<A>" "<B>"` for
  relationships and `graphify explain "<concept>"` for focused concepts — these
  return a scoped subgraph, usually far smaller than `GRAPH_REPORT.md` or raw grep.
- If `graphify-out/wiki/index.md` exists, use it for broad navigation instead of
  raw source browsing.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review, or when
  query/path/explain don't surface enough context.

`QuestRepository` is the central hub (wires DB + engines + AI); `:core` holds the
deterministic engine (reward economy, generation, AI parse/validate, safety).
