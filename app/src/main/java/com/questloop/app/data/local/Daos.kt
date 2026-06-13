package com.questloop.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestDao {
    @Query("SELECT * FROM quests WHERE archived = 0")
    fun observeActive(): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quests WHERE archived = 0")
    suspend fun getActive(): List<QuestEntity>

    @Upsert
    suspend fun upsert(quest: QuestEntity)

    @Query("UPDATE quests SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("SELECT COUNT(*) FROM quests")
    suspend fun count(): Int
}

@Dao
interface CompletionDao {
    @Query("SELECT * FROM completions ORDER BY epochDay DESC")
    fun observeAll(): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM completions")
    suspend fun getAll(): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE epochDay BETWEEN :start AND :end")
    suspend fun between(start: Long, end: Long): List<CompletionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: CompletionEntity)

    @Query("DELETE FROM completions WHERE instanceId = :instanceId")
    suspend fun delete(instanceId: String)
}
