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
        // Create the database at the current schema version from the exported
        // JSON. This already fails if `schemas/<SCHEMA_VERSION>.json` is missing.
        helper.createDatabase(dbName, QuestLoopDatabase.SCHEMA_VERSION).close()

        // Replay the shipping migrations and validate the resulting schema matches
        // the exported one. validateDroppedTables = true catches stragglers a
        // hand-written migration might leave behind.
        helper.runMigrationsAndValidate(
            dbName,
            QuestLoopDatabase.SCHEMA_VERSION,
            true,
            *QuestLoopDatabase.MIGRATIONS,
        )
    }
}
