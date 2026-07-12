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

    /** Keyed lookup of a single active quest (e.g. the widget's completion menu). */
    @Query("SELECT * FROM quests WHERE archived = 0 AND id = :id LIMIT 1")
    suspend fun getActiveById(id: String): QuestEntity?

    /** Every quest including archived — used for a complete export. */
    @Query("SELECT * FROM quests")
    suspend fun getAll(): List<QuestEntity>

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

    /** Full history — data export and the Completed screen's all-time slice (which
     *  filters/sorts off the main thread and keeps skipped rows, so it can't use a
     *  COMPLETED-only SQL query). */
    @Query("SELECT * FROM completions ORDER BY epochDay")
    suspend fun all(): List<CompletionEntity>

    /** Recent records, for reward context / safety / streak (avoids loading all rows). */
    @Query("SELECT * FROM completions WHERE epochDay >= :sinceEpochDay")
    suspend fun since(sinceEpochDay: Long): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE instanceId = :instanceId LIMIT 1")
    suspend fun find(instanceId: String): CompletionEntity?

    /**
     * One point-query for many records: used to fetch every candidate quest's
     * current-interval record in a single round-trip (see [QuestRepository]'s
     * per-call DayContext) instead of a [find] per quest.
     */
    @Query("SELECT * FROM completions WHERE instanceId IN (:instanceIds)")
    suspend fun findByInstanceIds(instanceIds: List<String>): List<CompletionEntity>

    /** The ledger is the source of truth for total XP. */
    @Query("SELECT COALESCE(SUM(xpAwarded), 0) FROM completions")
    suspend fun totalXp(): Long

    @Query("SELECT COALESCE(SUM(xpAwarded), 0) FROM completions")
    fun observeTotalXp(): Flow<Long>

    /**
     * Cheap change stamp for observers that only need a "ledger changed" tick
     * (e.g. the widget refresher): Room re-runs flow queries on every write to
     * the table, and MAX(rowid) reads a single index entry — no rows are loaded
     * or mapped, unlike [observeAll]. The value itself is not meaningful (a
     * REPLACE mints a fresh rowid; a delete can leave the max unchanged) —
     * treat emissions as a signal only.
     */
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM completions")
    fun observeChangeStamp(): Flow<Long>

    // A zero-progress partial (e.g. "0 of 8 glasses") carries no penalty but is
    // not real activity, so it must not count toward completions/streaks/wins.
    @Query("SELECT COUNT(*) FROM completions WHERE result = 'COMPLETED' OR (result = 'PARTIAL' AND fraction > 0)")
    suspend fun countCompleted(): Int

    @Query(
        "SELECT COUNT(DISTINCT category) FROM completions " +
            "WHERE result = 'COMPLETED' OR (result = 'PARTIAL' AND fraction > 0)",
    )
    suspend fun countDistinctCompletedCategories(): Int

    @Query(
        "SELECT COUNT(*) FROM completions WHERE category = 'BAD_HABIT_REDUCTION' " +
            "AND result IN ('FAILED', 'SKIPPED')",
    )
    suspend fun countHonestyLogs(): Int

    @Query(
        "SELECT COUNT(*) FROM completions WHERE category = 'BAD_HABIT_REDUCTION' " +
            "AND (result = 'COMPLETED' OR (result = 'PARTIAL' AND fraction > 0))",
    )
    suspend fun countReductionWins(): Int

    /** Distinct days with at least one real completion/partial (for streaks). */
    @Query(
        "SELECT DISTINCT epochDay FROM completions " +
            "WHERE result = 'COMPLETED' OR (result = 'PARTIAL' AND fraction > 0)",
    )
    suspend fun activeDays(): List<Long>

    /** Most recent fully-completed day per quest, for recurrence scheduling. */
    @Query("SELECT questId, MAX(epochDay) AS lastDay FROM completions WHERE result = 'COMPLETED' GROUP BY questId")
    suspend fun lastCompletedDays(): List<LastCompletion>

    /**
     * Fully-completed interval count per quest (records are keyed one-per-slot, so
     * COUNT(*) of COMPLETED rows = completed intervals). Drives the occurrence
     * limit ("medicine for 5 days") and its "k of N" progress display.
     */
    @Query("SELECT questId, COUNT(*) AS n FROM completions WHERE result = 'COMPLETED' GROUP BY questId")
    suspend fun completedOccurrenceCounts(): List<QuestOccurrenceCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(completion: CompletionEntity)

    @Query("DELETE FROM completions WHERE instanceId = :instanceId")
    suspend fun delete(instanceId: String)

    @Query("DELETE FROM completions")
    suspend fun clear()
}
