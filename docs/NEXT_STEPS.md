# Next steps / deferred work

Recorded backlog from the roadmap and review cycles. Not yet started — pick up here.

## Horizon 1 — trust & a real release (highest priority)
- **Release signing + run `assembleRelease`** — `release` build type has ProGuard but
  no signingConfig; `release.yml` ships `assembleDebug`. Biggest Play-store blocker.
  Flush out R8/ProGuard issues against Room / kotlinx-serialization / Glance early.
- **Room migration-discipline CI test** — `MIGRATIONS` is empty at `version = 2` with
  `fallbackToDestructiveMigrationFrom(1)`. Add a MigrationTestHelper test that boots
  each shipped schema and asserts a real `Migration` exists per bump (schemas are
  exported by `export-room-schema.yml`). Fiddly setup — do it deliberately.
- **Play listing + privacy policy + Data Safety form** — must disclose the OpenRouter
  call (data leaves device only when AI is on). Store copy/positioning already in
  `external_research/QuestLoop_Competitive_Landscape.md`.
- **targetSdk 35** + permission/notification re-check (reminders use inexact alarms — good).
- **Key-safety**: a test asserting the API key never appears in `ExportSnapshot` JSON
  or `AiDiagnostics` (export already excludes it; lock it in).

## Horizon 2 — surface the moat & deepen safe AI
- **Advisory anti-farming** — surface the existing `RewardEngine.capReason` as a gentle,
  honest note (pair with `SafetyGuard` thresholds), instead of a silent clamp.
- **Conversational onboarding / single-box capture** — AI structures a brain-dump into
  the first plan; lowers setup burden (LifeUp's weakness). Don't gate onboarding on AI.
- **Honesty / recovery framing made visible** — give bad-habit honest logging and
  recovery mode a distinct, warm treatment beyond the XP line.

## Horizon 3 — durability, reach, ethical revenue
- **Account-less, user-owned sync** (Drive app-folder / SAF / scheduled encrypted
  export), building on import/export. No backend; keep the AI key off-device-blob.
- **Ethical monetization** — one-time purchase and/or generous-free + optional supporter
  unlock. No subscriptions / loot boxes / ads. Never paywall the safety floor or the BYO key.
- **Progression "journey" view**, **mood check-in** (pairs with energy; aggregates-only AI).
- **Competitor watch**: if MainQuest/LifeUp ship energy-aware planning or proportional
  crediting, re-prioritize depth there.

## Smaller deferred review nits
- ProfileStore `streakGraceDays` `coerceIn(0,7)` clamp has no direct test (FakePrefs
  stores raw); add a ProfileStore-level (Robolectric) clamp test.
- `toQuest` fills missing `estimatedMinutes` with `defaultMinutes(difficulty)`, so an
  AI "EPIC with no minutes" survives the difficulty clamp on both suggest + decompose
  paths. Mitigated by prompt guidance; consider clamping by model-provided minutes only.
