# AGENTS.md — working notes for QuestLoop

Guidance for AI agents (and humans) working in this repo. Keep this current as
workflow and code conventions evolve.

## What this is
QuestLoop: a gamified quest/habit Android app. Gradle multi-module:
- `:core` — pure Kotlin/JVM (no Android): domain model, reward economy, quest
  generation/scheduling, AI prompt+parse/validate, safety, reviews. Unit-tested
  with `kotlin-test` and runs anywhere.
- `:app` — Android (Compose/Material3, Room, DataStore, Navigation, Glance
  widget, reminders). Manual DI via `AppContainer` + `appViewModelFactory`.

## Build & CI workflow (important)
- **The `:app` module does NOT build locally in the agent environment** (no
  Google Maven access for the Android Gradle Plugin). Only `./gradlew :core:test`
  runs locally. Validate all app/Compose changes via CI — review edits carefully.
- **Per-push gate = `.github/workflows/smoke.yml`** (every push/PR): runs ONLY the
  `:core` logic suite + its coverage gate (via the reusable `core-tests.yml`), in
  ~1 minute. It does NOT build `:app`, run app unit tests, or lint.
- **Everything `:app` lives in `.github/workflows/full-tests.yml`**: app unit tests
  (`:app:testDebugUnitTest` — JUnit + Robolectric + MockWebServer), `assembleDebug`,
  `assembleRelease` (R8/minify check), `lintDebug`, the emulator UI tests, and the
  merged (unit + instrumented) coverage gate + badge. It runs **manually (Actions →
  "Run workflow"), nightly (06:00 UTC), and on a published release — NOT on every
  push.** So app/Compose/lint/instrumented breakage is only caught when full-tests
  runs; the per-push smoke gate won't see it.
- **Instrumented UI** (`:app:connectedDebugAndroidTest`) runs in full-tests' emulator
  job (same triggers — there is no `[uitest]` commit trigger or `ui-tests.yml` any
  more). `AppSmokeTest` walks the main screens, hard-asserts each rendered, and writes
  a screenshot per screen to Test Storage, uploaded as the **`ui-screenshots`**
  artifact (+ HTML report).
- **Releases**: tag pushes are 403-blocked, so releases are cut server-side by
  putting **`[release]`** in the commit message (empty commit is fine). It
  refreshes the rolling `v0.1.0-experimental` prerelease APK (debug-signed).
- **Reading CI status**: `mcp__github__actions_list` output is large and exceeds
  the tool token limit — it's saved to a file; parse it with `python3 -c "import
  json; ..."` rather than reading raw.
- **Artifacts (screenshots)**: the agent sandbox's network egress blocks GitHub's
  artifact blob host (`*.blob.core.windows.net`), so screenshots can't be
  auto-downloaded yet — the user grabs them from the Actions UI. Add that host to
  the environment's egress allowlist to enable agent-side visual review.
- Trunk is `main`; feature work happens on `claude/*` branches (cloud sessions).
  Note: `release.yml` / `export-room-schema.yml` still list a stale
  `claude/gamified-quest-todo-habits-vkiiyl` branch and `master` in their push
  filters — harmless (they also list `main`), but worth pruning.

## Coding lessons / gotchas (learned the hard way)
**Reward economy & data**
- Total XP is derived from the completion ledger (`SUM(xpAwarded)`); completions
  are idempotent per `instanceId` (`questId@epochDay`). Serialize the
  read-modify-write with the repository `Mutex` (concurrent completes else
  mis-count caps).
- `LevelSystem` must tolerate non-positive XP — a gentle skip penalty can dip the
  ledger below zero; coerce to ≥0 (don't `require`).
- Room: never use blanket `fallbackToDestructiveMigration()` (silent total data
  loss on a schema bump). Use `fallbackToDestructiveMigrationFrom(1)` + real
  `Migration`s + `exportSchema = true` (schemas in `app/schemas`).
- Derived quests (from habits/bad-habits/goals via `HabitQuestFactory.deriveAll`)
  are NOT in the `quests` table — include them when building style/progress/
  dismissal maps, not just `questDao.getActive()`.
- DataStore reads: `.catch { if (it is IOException) emit(emptyPreferences()) else
  throw it }` so a corrupt prefs file doesn't crash launch.

**Compose / UI**
- One-shot events (snackbars): key `LaunchedEffect` on a monotonic counter
  (`toastId`), NOT the message string — identical consecutive messages are
  otherwise swallowed (and their Undo lost).
- Double-tap: guard completion/accept actions with an in-flight flag AND disable
  the buttons. Idempotency protects XP but not Undo state or duplicate inserts
  (e.g. "Add all" mints fresh UUIDs → real duplicates).
- `rememberSaveable`: fine for String/Int via `mutableStateOf`; for enums use a
  `stateSaver` (store `.name`). Don't pair with `mutableIntStateOf` unless verified.
- Kotlin smart-cast fails on a nullable property accessed inside a lambda (e.g.
  `result.quest` in `.map`) — capture it in a local `val` first.

**Navigation**
- Re-tapping the active bottom-nav tab should pop to that tab's root.
- Map sub-screens to their owning tab (`tabForRoute`) so the right tab stays lit.
- Open sub-screens/modals with `launchSingleTop`. If a sub-screen is reachable
  from two tabs, enter the owning tab first so back-stack/highlight stay correct.

**AI (OpenRouter)**
- Hold a partial `WAKE_LOCK` around the network call so a slow response survives
  the screen turning off (`AiCallGuard`).
- Surface the provider's error body; never silently echo the deterministic
  fallback as if it were AI output. Pass the user's existing quests for dedup.
- The prompt asks the model to choose difficulty (→ XP), `completionStyle`,
  `frequency`, and `priority`. Default model `openrouter/free` (auto-router)
  avoids stale-slug 404s; specific free-model slugs go stale often.
- Generated quests are reviewed/edited before saving — never auto-persisted.

**Security / privacy**
- API key: `EncryptedSharedPreferences` (Keystore), never plaintext DataStore;
  excluded from export (`ExportSnapshot` omits `AiConfig`) and from the
  diagnostics log. Wrap the encrypted store in `runCatching` to fail safe.
- All `PendingIntent`s use `FLAG_IMMUTABLE`; exported receivers validate
  `intent.action`.

**Reminders**
- Self-healing one-shot alarms that re-arm on each fire (plus boot + app-open),
  not `setInexactRepeating`. Stamp the firing epoch-day into the "Mark done"
  intent so a late tap credits the right day.

## Voice & content
- No developer-facing text in the UI. Follow `docs/CONTENT_STYLE.md` (warm,
  plain, brief, non-shaming).

## Process
- Review → fix → test → push → watch CI → fix failures. For substantial features,
  also run a code review and `[uitest]` before releasing.
- Don't commit secrets. The OpenRouter key lives only in on-device storage.
