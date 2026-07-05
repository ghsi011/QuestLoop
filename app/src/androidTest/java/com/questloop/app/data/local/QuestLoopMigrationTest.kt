package com.questloop.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration-discipline guard. Opens the database at the current
 * [QuestLoopDatabase.SCHEMA_VERSION] from its exported schema JSON and replays
 * every shipping [QuestLoopDatabase.MIGRATIONS] entry, validating the result
 * against that schema.
 *
 * With one exported version and no migrations this confirms the committed
 * `schemas/<n>.json` matches the compiled entities. Its real job is forward
 * cover: the day someone bumps `SCHEMA_VERSION` (e.g. adds a column) without
 * supplying both a [Migration] and the new exported schema, this test fails in
 * CI instead of users silently losing their quests, XP and history at runtime.
 *
 * Runs only in the emulator workflow (the `[uitest]` commit marker) like the
 * other instrumented tests; it is not part of the normal CI job.
 */
@RunWith(AndroidJUnit4::class)
class QuestLoopMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        QuestLoopDatabase::class.java,
    )

    @Test
    fun schema_and_migrations_are_in_sync() {
        // Create the database at the earliest exported schema (v2 — v1 predates
        // schema export) so the shipping migrations are actually replayed forward.
        // This fails if `schemas/2.json` is missing.
        helper.createDatabase(dbName, EARLIEST_EXPORTED_VERSION).close()

        // Replay every shipping migration up to the current version and validate
        // the result matches the exported schema. validateDroppedTables = true
        // catches stragglers a hand-written migration might leave behind.
        helper.runMigrationsAndValidate(
            dbName,
            QuestLoopDatabase.SCHEMA_VERSION,
            true,
            *QuestLoopDatabase.MIGRATIONS,
        )
    }

    @Test
    fun v2_to_v3_preserves_rows_and_adds_over_completion_column() {
        // A quest row that exists at v2 must survive the ALTER and gain the new
        // column defaulted to 0 (false) — i.e. no data loss on upgrade.
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO quests (id, title, category, frequency, difficulty, priority, origin, " +
                    "estimatedMinutes, deadlineEpochDay, isReductionQuest, completionStyle, targetCount, " +
                    "unit, tags, rationale, archived) VALUES " +
                    "('q1','Swim','HEALTH','WEEKLY','MEDIUM','NORMAL','USER_CREATED',30,NULL,0," +
                    "'QUANTITATIVE',2,'times','',NULL,0)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            dbName, QuestLoopDatabase.SCHEMA_VERSION, true, *QuestLoopDatabase.MIGRATIONS,
        )
        migrated.query("SELECT allowOverCompletion FROM quests WHERE id = 'q1'").use { c ->
            assert(c.moveToFirst())
            assert(c.getInt(0) == 0) { "existing quests default to allowOverCompletion = false" }
        }
    }

    @Test
    fun v3_to_v4_preserves_ledger_rows_and_indexes_completions() {
        // A ledger row that exists at v3 must survive the index build untouched —
        // total XP is derived from SUM(xpAwarded), so any loss is user-visible.
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO completions (instanceId, questId, category, difficulty, priority, " +
                    "result, verification, epochDay, fraction, isMeta, xpAwarded) VALUES " +
                    "('q1@20000','q1','HEALTH','MEDIUM','NORMAL','COMPLETED','MANUAL',20000,1.0,0,40)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            dbName, QuestLoopDatabase.SCHEMA_VERSION, true, *QuestLoopDatabase.MIGRATIONS,
        )
        migrated.query("SELECT xpAwarded FROM completions WHERE instanceId = 'q1@20000'").use { c ->
            assert(c.moveToFirst())
            assert(c.getLong(0) == 40L) { "ledger rows survive the v4 index migration" }
        }
        // The whole point of v4: the secondary indexes exist (validate() already
        // checked them against the exported schema; this pins the names too).
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND tbl_name = 'completions' AND name LIKE 'index_completions_%'",
        ).use { c ->
            assert(c.count == 3) { "expected epochDay/questId/result indexes on completions" }
        }
    }

    private companion object {
        const val EARLIEST_EXPORTED_VERSION = 2
    }
}
