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
- **Both modules build locally** once the Android SDK + JDK 17 are present — run
  `scripts/setup-android.sh` (installs JDK 17, the Android cmdline-tools, the
  compileSdk platform + matching build-tools). Then `:core`, `:app`
  unit/Robolectric tests, `assembleDebug`/`assembleRelease`, and `lintDebug` all
  run locally. The **only** exception is the emulator suite
  (`:app:connectedDebugAndroidTest`): it needs hardware virtualization
  (`/dev/kvm`), absent in these sandboxes, so it stays on CI (the `[uitest]`
  trigger). CI remains the authoritative gate — still review app/Compose edits
  carefully, and prefer a `[uitest]` run to validate UI behavior.
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
  job. Triggers: manual dispatch, nightly, release — or push a commit with
  **`[uitest]`** in the message to run the whole app + emulator suite on demand
  (other pushes trigger the workflow but every job is gated off). The standalone
  `ui-tests.yml` is gone; the emulator job now lives in full-tests. `AppSmokeTest`
  walks the main screens, hard-asserts each rendered, and writes a screenshot per
  screen to Test Storage, uploaded as the **`ui-screenshots`** artifact (+ HTML report).
- **Releases**: tag pushes are 403-blocked, so releases are cut server-side by
  putting **`[release]`** in the commit message (empty commit is fine). It
  refreshes the rolling `v0.1.0-experimental` prerelease APK (debug-signed).
  The release job gates on `:core:test` + `:app:testDebugUnitTest` + `lintDebug`
  before publishing (full-tests only fires after a release is public), and a
  release-signed build also attaches R8's `mapping.txt` to the release so
  obfuscated crash logs stay retraceable.
- **Reading CI status**: `mcp__github__actions_list` output is large and exceeds
  the tool token limit — it's saved to a file; parse it with `python3 -c "import
  json; ..."` rather than reading raw.
- **Artifacts (screenshots)**: the agent sandbox's network egress blocks GitHub's
  artifact blob host (`*.blob.core.windows.net`), so screenshots can't be
  auto-downloaded yet — the user grabs them from the Actions UI. Add that host to
  the environment's egress allowlist to enable agent-side visual review.
- Trunk is `main`; feature work happens on `claude/*` branches (cloud sessions).
  `release.yml` / `export-room-schema.yml` trigger on `["main", "claude/**"]`.
- **CI trigger tokens match ANYWHERE in the commit message** — never write
  `[release]` / `[uitest]` / `[schema]` in commit-message prose (a body line
  *mentioning* the release trigger once cut an accidental release). Spell them
  out ("the release trigger") unless you mean to fire them.

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

**AI (provider-pluggable: OpenRouter or OpenAI/ChatGPT)**
- Two backends, chosen in Settings via `AiConfig.provider` (`AiProvider`):
  `OPENROUTER` (paste an API key) or `OPENAI` ("Sign in with ChatGPT" OAuth). The
  repository's `llmClient(config)` picks the `LlmClient` impl per provider; the
  rest of the AI pipeline (`AiQuestService`/`AiNarrator`) is provider-agnostic.
- OpenAI uses the Codex OAuth flow (loopback PKCE, client `app_EMoamEEZ…`) like
  opencode/Codex CLI. Pure protocol bits — PKCE, authorize URL, token + JWT parse,
  the Responses request/SSE codec — live in `:core` (`com.questloop.core.ai.openai`,
  fully unit-tested); `:app` owns the loopback `ServerSocket` (127.0.0.1:1455),
  browser intent, OkHttp, and secure token storage (`OpenAiAuthService`/`OpenAiClient`).
  The chatgpt.com Codex backend is reverse-engineered and **can't be tested in the
  sandbox** — validate live changes by hand. Tokens rotate on refresh: serialise
  refresh+persist (`aiAuthMutex`) so concurrent calls don't burn each other's token.
  Disconnect + delete-all take the same mutex (and the refresh re-reads the config
  before persisting) so a sign-out/wipe can't be resurrected by an in-flight refresh.
- Hold a partial `WAKE_LOCK` around the network call so a slow response survives
  the screen turning off (`AiCallGuard`).
- Surface the provider's error body; never silently echo the deterministic
  fallback as if it were AI output. Pass the user's existing quests for dedup.
- The prompt asks the model to choose difficulty (→ XP), `completionStyle`,
  `frequency`, `priority`, and (when the wording gives one) the schedule fields
  (times of day, anchor day, occurrence limit, reminders). Default model
  `openrouter/free` (auto-router)
  avoids stale-slug 404s; specific free-model slugs go stale often. OpenAI sends our
  own `originator`/User-Agent (`questloop`/`QuestLoop`, like opencode sends `opencode`),
  and the Codex model line rotates (default `gpt-5.4`; the field is free-text).
- Generated quests are reviewed/edited before saving — never auto-persisted.
- **AI quick-add is the user's primary way to create quests — keep it in lockstep
  with quest creation.** Any change to the Quest model or creation semantics (new
  fields, completion styles, scheduling/recurrence, defaults) MUST update, in the
  same change: the shared response schema + inference rules
  (`AiQuestService.SCHEMA_BODY` — it rides on every call path: quick add, goal
  decomposition, refine), the quest-design rules in
  `PromptLibrary.QUEST_GENERATION_SYSTEM` (and bump the prompt versions),
  `AiQuestDto` + `toQuest` + `dtoFrom` (refine must round-trip new fields),
  normalization/validator guardrails, the suggestion-card editor, and
  `AiQuestServiceTest`. If a field is deliberately not AI-inferable, leave a
  comment saying why. Convention for anything notification-like: default OFF
  unless it's medication/treatment-critical timing or the user explicitly asked.

**Security / privacy**
- Credentials (OpenRouter key + OpenAI OAuth tokens): `EncryptedSharedPreferences`
  (Keystore) via `SecureKeyStore`, never plaintext DataStore; excluded from export
  (`ExportSnapshot` omits `AiConfig`) and scrubbed from the diagnostics log
  (`redactSecrets` strips the key + access/refresh tokens). Wrap the encrypted
  store in `runCatching` to fail safe.
- All `PendingIntent`s use `FLAG_IMMUTABLE`; exported receivers validate
  `intent.action`.

**Reminders**
- Self-healing one-shot alarms that re-arm on each fire (plus boot, app-open,
  and timezone/clock changes — armed triggers are fixed epoch instants, so a
  zone change would otherwise fire them at the old zone's wall time), not
  `setInexactRepeating`. Stamp the firing epoch-day into the "Mark done"
  intent so a late tap credits the right day.

## Voice & content
- No developer-facing text in the UI. Follow `docs/CONTENT_STYLE.md` (warm,
  plain, brief, non-shaming).

## Process — mandatory workflow for every change

Spec → Implement → Verify → Review → Push. The review gate is enforced by a
hook: `git push` is blocked until `.git/questloop-review-stamp` matches HEAD.
Reviews run by default — never wait for the user to ask for one.

**0. Spec (before writing code).** Restate the task as acceptance criteria
(≤10 lines). Sweep the edge-case matrix (`.claude/rules/edge-cases.md`) and
mark every dimension in-scope / out-of-scope / N/A — "didn't think about it"
is not a state. Classify risk:
- **Risky** if any of: reward economy / completion ledger / Room schema;
  credentials or the AI pipeline; widget / reminders / boot (code that runs
  without the app open); a new user-facing feature or behavior change;
  >~150 changed lines of product source.
- **Standard** otherwise (small fixes, refactors, copy tweaks).
- Docs/CI-only changes skip phases 0–3 (stamp directly before pushing).

**1. Implement** per the conventions above; add tests alongside (prefer
`:core` — it gates every push).

**2. Verify.** Run the affected suites (`:core:test`; `:app:testDebugUnitTest`
when `:app` changed). For UI-behavior changes, judge whether a `[uitest]` run
is warranted.

**3. Review gate (always, unasked).**
- Spawn the **`code-reviewer`** agent on the final diff — for every code change.
- For **risky** changes, also spawn the **`skeptic`** agent — in the same
  message so they run in parallel.
- Hand each agent only: the phase-0 acceptance criteria, the base ref
  (e.g. `origin/main`), and the risk class. Never paste the diff or file
  contents into the prompt — the agents read `git diff` themselves.
- Fix confirmed findings, re-run tests, then re-review **only the fix diff**
  (`git diff <reviewed-sha>..HEAD`) — not the whole feature again. After a
  history rewrite (amend/rebase) the old sha no longer describes the branch:
  re-diff against `origin/main` instead.
- Once the final state is reviewed and committed, stamp (its own command;
  works in linked worktrees too):
  `git rev-parse HEAD > "$(git rev-parse --git-path questloop-review-stamp)"`
- HEAD moves that add no new code — empty `[release]` commits, merges from
  `origin/main`, clean rebases of already-reviewed work — re-stamp directly.
- The gate certifies HEAD only: review, stamp, and push from the same
  checkout; never wrap `git push` in scripts or `bash -c`; don't delegate the
  review/stamp/push step to a subagent (subagents can't spawn the reviewers).

**4. Push & watch CI**; fix failures (re-review only non-trivial fixes, then
re-stamp). For substantial features, run `[uitest]` before releasing.

Don't commit secrets. The OpenRouter key lives only in on-device storage.

### Token discipline
- Review once, on the final diff — not per-commit while developing.
- Subagent handoffs are pointers, not payloads: acceptance criteria + refs;
  no code dumps. Reviewers return findings only (`file:line`, severity, why).
- Reuse the phase-0 spec everywhere (plan, reviewer prompts, PR body) instead
  of re-deriving requirements at each step.
- Orient before searching: graphify when the graph exists (the hint hook fires
  once per session), else `docs/ARCHITECTURE.md` — don't re-read files you've
  already seen this session, and don't grep broadly for what a scoped query
  answers.
