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

    @Query("DELETE FROM quests")
    suspend fun clear()
}

@Dao
interface CompletionDao {
    @Query("SELECT * FROM completions ORDER BY epochDay DESC")
    fun observeAll(): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM completions WHERE epochDay BETWEEN :start AND :end")
    suspend fun between(start: Long, end: Long): List<CompletionEntity>

    /** Recent records, for reward context / safety / streak (avoids loading all rows). */
    @Query("SELECT * FROM completions WHERE epochDay >= :sinceEpochDay")
    suspend fun since(sinceEpochDay: Long): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE instanceId = :instanceId LIMIT 1")
    suspend fun find(instanceId: String): CompletionEntity?

    /** The ledger is the source of truth for total XP. */
    @Query("SELECT COALESCE(SUM(xpAwarded), 0) FROM completions")
    suspend fun totalXp(): Long

    @Query("SELECT COALESCE(SUM(xpAwarded), 0) FROM completions")
    fun observeTotalXp(): Flow<Long>

    @Query("SELECT COUNT(*) FROM completions WHERE result IN ('COMPLETED', 'PARTIAL')")
    suspend fun countCompleted(): Int

    @Query("SELECT COUNT(DISTINCT category) FROM completions WHERE result IN ('COMPLETED', 'PARTIAL')")
    suspend fun countDistinctCompletedCategories(): Int

    @Query(
        "SELECT COUNT(*) FROM completions WHERE category = 'BAD_HABIT_REDUCTION' " +
            "AND result IN ('FAILED', 'SKIPPED')",
    )
    suspend fun countHonestyLogs(): Int

    @Query(
        "SELECT COUNT(*) FROM completions WHERE category = 'BAD_HABIT_REDUCTION' " +
            "AND result IN ('COMPLETED', 'PARTIAL')",
    )
    suspend fun countReductionWins(): Int

    /** Distinct days with at least one completed/partial quest (for streaks). */
    @Query("SELECT DISTINCT epochDay FROM completions WHERE result IN ('COMPLETED', 'PARTIAL')")
    suspend fun activeDays(): List<Long>

    /** Most recent fully-completed day per quest, for recurrence scheduling. */
    @Query("SELECT questId, MAX(epochDay) AS lastDay FROM completions WHERE result = 'COMPLETED' GROUP BY questId")
    suspend fun lastCompletedDays(): List<LastCompletion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(completion: CompletionEntity)

    @Query("DELETE FROM completions WHERE instanceId = :instanceId")
    suspend fun delete(instanceId: String)

    @Query("DELETE FROM completions")
    suspend fun clear()
}
