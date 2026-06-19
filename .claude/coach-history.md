# Coach History — QuestLoop

- **Last coach run**: 2026-06-19 — score 6/10 (first run)
- **Last deep CLAUDE.md optimization**: 2026-06-19 (initial CLAUDE.md created)

## 2026-06-19 — first full audit (cloud-first)

Implemented (committed to the branch so cloud agents inherit them):
- `.gitignore`: `graphify-out/` allowlist — track `graph.json` + `GRAPH_REPORT.md`, ignore local artifacts
- Created root `CLAUDE.md` — imports `@AGENTS.md`, build/test commands, graphify orientation (with no-CLI fallback)
- Committed graphify orientation hooks → `.claude/settings.json`
- Vendored the graphify skill → `.claude/skills/graphify/`
- Added scoped test rule → `.claude/rules/testing.md` (paths: test/androidTest)
- Fixed stale CI references in `AGENTS.md` (smoke/full-tests split, trunk = `main`, `[uitest]`/`ci.yml`/`ui-tests.yml` retired)
- Committed `graphify-out/graph.json` + `GRAPH_REPORT.md`

Implemented (local only — not committed, won't reach cloud):
- Seeded project memory: `questloop-overview`, `cloud-first-dev`, `ci-structure`

Deferred (rationale):
- Kotlin LSP, Context7 — require editing global `~/.claude/settings.json` (out of coach project scope) and cloud availability is unverified. Manual follow-up.
- Usage-tracking hook — would live in git-ignored `settings.local.json` and write to a machine-local log; no value for cloud-first development.

Flagged for the user (not auto-changed):
- Global `~/.claude/CLAUDE.md` hardcodes a `Today's date` line that goes stale.

## 2026-06-19 — follow-up (same session)

User approved fixing the two CI items I'd flagged:
- **Pruned stale branch filters** in `release.yml` + `export-room-schema.yml` →
  `["main", "claude/**"]` (dropped dead `master` and the old hardcoded claude branch).
- **Re-wired `[uitest]`**: `full-tests.yml` now also triggers on push, with every
  job gated on `contains(commit message, '[uitest]')` so plain pushes stay a no-op;
  the coverage-badge job is restricted to schedule / release / manual-from-main so a
  `[uitest]` run can't clobber the published number. Docs (AGENTS.md, CLAUDE.md, the
  testing rule, the `ci-structure` memory) updated to match — `[uitest]` is live again.
