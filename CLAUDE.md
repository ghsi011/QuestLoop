# QuestLoop — Claude Code guide

Project conventions, gotchas, and the full CI/release workflow live in AGENTS.md.
It's imported below so it loads in Claude Code too:

@AGENTS.md

## Build & test

Gradle multi-module project. **Only `:core` builds locally** in the agent
sandbox (no Google Maven access for the Android Gradle Plugin) — validate any
`:app` / Compose change through CI.

- Core logic (runs locally): `./gradlew :core:test`
- App unit tests — JUnit + Robolectric + MockWebServer (CI): `./gradlew :app:testDebugUnitTest`
- Debug APK (CI): `./gradlew :app:assembleDebug`
- Lint (CI): `./gradlew :app:lintDebug`
- Instrumented UI on an emulator (CI only): `./gradlew :app:connectedDebugAndroidTest`

CI layout: **`smoke.yml`** runs the `:core` suite on every push/PR (fast gate,
~1 min). **`full-tests.yml`** runs app build + lint + unit + emulator UI + merged
coverage — manually, nightly, and on release (NOT per-push). `[release]` cuts a
release, `[schema]` regenerates the Room schema. Details in AGENTS.md.

## Orientation: query the knowledge graph first

A graphify knowledge graph of this repo is committed at `graphify-out/graph.json`
(plain-language report: `graphify-out/GRAPH_REPORT.md`). For "how / where / why
does X work" questions, orient from the graph **before** grepping or reading
source files:

- `graphify query "<question>"` — when the graphify CLI is installed
- otherwise read `graphify-out/graph.json` directly — it is NetworkX node-link
  JSON: `nodes[]` carry `label` / `source_file` / `file_type`, `links[]` carry
  `relation` / `confidence`. Filter to the relevant subgraph, then open the files
  it points at.

`QuestRepository` is the central hub (wires DB + engines + AI); `:core` holds the
deterministic engine (reward economy, generation, AI parse/validate, safety).
Rebuild the graph after large changes with the `/graphify` skill (it installs the
`graphifyy` package on first run).
