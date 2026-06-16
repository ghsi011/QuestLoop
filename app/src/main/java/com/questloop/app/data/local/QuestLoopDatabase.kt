package com.questloop.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QuestEntity::class, CompletionEntity::class],
    version = 2,
    // Export the schema so future versions can be migrated (and migrations tested)
    // rather than guessed at.
    exportSchema = true,
)
abstract class QuestLoopDatabase : RoomDatabase() {
    abstract fun questDao(): QuestDao
    abstract fun completionDao(): CompletionDao

    companion object {
        /**
         * Migrations between schema versions. Add a [Migration] for every version
         * bump from v2 onward; the builder no longer wipes data on a missing
         * migration, so a forgotten one fails loudly instead of silently erasing
         * the user's quests, XP and history.
         */
        private val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()

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
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { instance = it }
            }
    }
}
