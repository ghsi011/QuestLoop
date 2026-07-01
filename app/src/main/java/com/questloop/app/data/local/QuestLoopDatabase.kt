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
        const val SCHEMA_VERSION = 3

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

        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)

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
