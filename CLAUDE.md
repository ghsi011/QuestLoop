# QuestLoop — Claude Code guide

Conventions, gotchas, CI/release triggers (`[uitest]` `[release]` `[schema]`),
and the **mandatory review workflow** (spec → implement → verify → review →
push; enforced by a `git push` hook) live in AGENTS.md, imported below:

@AGENTS.md

## Build & test

Gradle multi-module. Both modules build locally after `scripts/setup-android.sh`
(JDK 17 + minimal Android SDK); only the emulator suite is CI-only (no
`/dev/kvm` in the sandbox) — validate Compose/UI behavior via CI's `[uitest]` job.

- Core logic (gates every push): `./gradlew :core:test`
- App unit tests (JUnit + Robolectric + MockWebServer): `./gradlew :app:testDebugUnitTest`
- Debug APK: `./gradlew :app:assembleDebug`
- Lint: `./gradlew :app:lintDebug`
- Instrumented UI (CI emulator only): `./gradlew :app:connectedDebugAndroidTest`

## graphify

The knowledge graph is generated, not committed — `/graphify` builds it;
`graphify update .` after code changes (AST-only, free). When
`graphify-out/graph.json` exists, orient with `graphify query "<question>"`
(or `path`/`explain`) before raw greps or file reads; use
`graphify-out/wiki/index.md` for broad navigation when present.

`QuestRepository` is the central hub (wires DB + engines + AI); `:core` holds the
deterministic engine (reward economy, generation, AI parse/validate, safety).
