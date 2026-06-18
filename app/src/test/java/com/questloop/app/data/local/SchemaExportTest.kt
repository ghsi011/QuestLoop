package com.questloop.app.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plain-JVM guard that runs in the normal CI job (unlike the emulator-only
 * [QuestLoopMigrationTest]): asserts an exported Room schema JSON exists for the
 * current [QuestLoopDatabase.SCHEMA_VERSION].
 *
 * This catches the most common migration-discipline slip — bumping the version
 * but forgetting to export/commit the new schema — on every push, instead of
 * only when someone remembers the `[uitest]` marker. (The emulator test still
 * does the deeper job of replaying MIGRATIONS against the schema.)
 */
class SchemaExportTest {

    @Test
    fun `exported schema exists for the current version`() {
        val rel = "schemas/com.questloop.app.data.local.QuestLoopDatabase/" +
            "${QuestLoopDatabase.SCHEMA_VERSION}.json"
        // Unit tests run with the module dir as the working dir, but be tolerant
        // of the repo root too so the check is robust to the runner's cwd.
        val candidates = listOf(File(rel), File("app/$rel"))
        assertTrue(
            "Missing exported schema for SCHEMA_VERSION=${QuestLoopDatabase.SCHEMA_VERSION} " +
                "(looked for: ${candidates.joinToString { it.path }}). Export and commit the " +
                "schema, and add a Migration, when bumping the version.",
            candidates.any { it.exists() },
        )
    }
}
