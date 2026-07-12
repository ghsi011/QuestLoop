package com.questloop.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QuestEntity::class, CompletionEntity::class],
    version = QuestLoopDatabase.SCHEMA_VERSION,
    // Export the schema so future versions can be migrated (and migrations tested)
    // rather than guessed at.
    exportSchema = true,
)
abstract class QuestLoopDatabase : RoomDatabase() {
    abstract fun questDao(): QuestDao
    abstract fun completionDao(): CompletionDao

    companion object {
        /**
         * Current schema version. Bump this in lockstep with adding a [Migration]
         * below and committing the exported `schemas/<n>.json`; the migration test
         * walks every version up to here, so a forgotten migration fails in CI.
         */
        const val SCHEMA_VERSION = 5

        /**
         * Migrations between schema versions. Add a [Migration] for every version
         * bump from v2 onward; the builder no longer wipes data on a missing
         * migration, so a forgotten one fails loudly instead of silently erasing
         * the user's quests, XP and history. Visible to the androidTest migration
         * guard so the test exercises exactly what ships.
         */
        /** v2 → v3: add `allowOverCompletion` to quests (measured over-completion). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE quests ADD COLUMN allowOverCompletion INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v3 → v4: secondary indexes on completions (epochDay / questId / result)
         * so the windowed and aggregate ledger queries stop full-scanning. Names
         * must match what Room derives for `@Entity(indices = ...)` or the
         * migrated DB fails validation against the exported schema.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_completions_epochDay` ON `completions` (`epochDay`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_completions_questId` ON `completions` (`questId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_completions_result` ON `completions` (`result`)",
                )
            }
        }

        /**
         * v4 → v5: per-quest scheduling — set times of day, weekly/monthly anchor
         * day, a total-occurrence limit, and a per-quest reminder toggle. Defaults
         * match the entity's (untimed, unanchored, unlimited, reminders off) so
         * existing quests behave exactly as before.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quests ADD COLUMN scheduledTimes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quests ADD COLUMN scheduledDayOfWeek INTEGER")
                db.execSQL("ALTER TABLE quests ADD COLUMN scheduledDayOfMonth INTEGER")
                db.execSQL("ALTER TABLE quests ADD COLUMN totalOccurrences INTEGER")
                db.execSQL("ALTER TABLE quests ADD COLUMN remindersEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        @Volatile
        private var instance: QuestLoopDatabase? = null

        fun get(context: Context): QuestLoopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuestLoopDatabase::class.java,
                    "questloop.db",
                )
                    .addMigrations(*MIGRATIONS)
                    // Only ever destroy data when upgrading from the pre-schema-export
                    // v1 (no migration is authorable for it). Any future v2+ gap throws.
                    // Room 2.8 requires the dropAllTables flag; true preserves the prior
                    // destructive behaviour (drop every table, not just Room-known ones).
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                    .build()
                    .also { instance = it }
            }
    }
}
