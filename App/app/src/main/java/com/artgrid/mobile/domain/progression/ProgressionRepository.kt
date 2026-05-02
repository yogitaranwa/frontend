/**
 * ProgressionRepository.kt
 * Responsibility : Interface for multi-stage progression CRUD (F-32).
 * Pattern used   : Repository interface
 * Dependencies   : none (interface)
 */
package com.artgrid.mobile.domain.progression

import com.artgrid.mobile.domain.progression.model.Progression
import com.artgrid.mobile.domain.progression.model.ProgressionStage
import kotlinx.coroutines.flow.Flow

interface ProgressionRepository {

    /** Observe all progressions, most recently updated first. */
    fun observeAll(): Flow<List<Progression>>

    /** Observe all stages for a given progression, ordered by [ProgressionStage.stageOrder]. */
    fun observeStages(progressionId: Long): Flow<List<ProgressionStage>>

    suspend fun findById(progressionId: Long): Progression?

    /** Create a new progression. Returns the new row ID. */
    suspend fun create(title: String, description: String = ""): Long

    /** Update title/description and bump updatedAtMs. */
    suspend fun updateProgression(progression: Progression)

    /** Delete a progression and cascade-delete all its stages. */
    suspend fun deleteProgression(progressionId: Long)

    /** Add a stage to a progression. Returns the new stage row ID. */
    suspend fun addStage(
        progressionId: Long,
        label: String,
        localPath: String,
        sha256: String,
        note: String = "",
    ): Long

    /** Update stage metadata (label, note, grid settings). */
    suspend fun updateStage(stage: ProgressionStage)

    /** Delete a single stage. */
    suspend fun deleteStage(stageId: Long)

    /**
     * Reorder stages within a progression.
     * [orderedIds] should contain all stage IDs in the desired order.
     */
    suspend fun reorderStages(progressionId: Long, orderedIds: List<Long>)
}
