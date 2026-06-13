package com.questloop.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QuestEntity::class, CompletionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class QuestLoopDatabase : RoomDatabase() {
    abstract fun questDao(): QuestDao
    abstract fun completionDao(): CompletionDao

    companion object {
        @Volatile
        private var instance: QuestLoopDatabase? = null

        fun get(context: Context): QuestLoopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuestLoopDatabase::class.java,
                    "questloop.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
