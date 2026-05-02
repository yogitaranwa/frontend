/**
 * ProgressionDao.kt
 * Responsibility : Room DAO for progressions + progression_stages tables (F-32).
 * Pattern used   : Room DAO
 * Dependencies   : Room, Coroutines Flow
 */
package com.artgrid.mobile.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.artgrid.mobile.data.db.entity.ProgressionEntity
import com.artgrid.mobile.data.db.entity.ProgressionStageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressionDao {

    // ── Progressions ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM progressions ORDER BY updated_at_ms DESC")
    fun observeAll(): Flow<List<ProgressionEntity>>

    @Query("SELECT * FROM progressions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ProgressionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgression(entity: ProgressionEntity): Long

    @Update
    suspend fun updateProgression(entity: ProgressionEntity)

    @Delete
    suspend fun deleteProgression(entity: ProgressionEntity)

    @Query("DELETE FROM progressions WHERE id = :id")
    suspend fun deleteProgressionById(id: Long)

    // ── Stages ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM progression_stages WHERE progression_id = :progressionId ORDER BY stage_order ASC")
    fun observeStages(progressionId: Long): Flow<List<ProgressionStageEntity>>

    @Query("SELECT * FROM progression_stages WHERE id = :id LIMIT 1")
    suspend fun findStageById(id: Long): ProgressionStageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStage(entity: ProgressionStageEntity): Long

    @Update
    suspend fun updateStage(entity: ProgressionStageEntity)

    @Delete
    suspend fun deleteStage(entity: ProgressionStageEntity)

    @Query("DELETE FROM progression_stages WHERE id = :id")
    suspend fun deleteStageById(id: Long)

    /** Reorders stages within a progression — updates all affected stage_order values. */
    @Transaction
    suspend fun reorderStages(stages: List<ProgressionStageEntity>) {
        stages.forEach { updateStage(it) }
    }

    /** Count of stages for a given progression. */
    @Query("SELECT COUNT(*) FROM progression_stages WHERE progression_id = :progressionId")
    suspend fun countStages(progressionId: Long): Int
}
