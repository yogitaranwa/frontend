/**
 * ProgressionRepositoryImpl.kt
 * Responsibility : Implements ProgressionRepository using Room DAO (F-32).
 * Pattern used   : Repository implementation
 * Dependencies   : ProgressionDao, ProgressionMapper
 */
package com.artgrid.mobile.data.progression

import com.artgrid.mobile.data.db.dao.ProgressionDao
import com.artgrid.mobile.data.db.entity.ProgressionEntity
import com.artgrid.mobile.data.db.entity.ProgressionStageEntity
import com.artgrid.mobile.domain.progression.ProgressionRepository
import com.artgrid.mobile.domain.progression.mapper.ProgressionMapper.toDomain
import com.artgrid.mobile.domain.progression.mapper.ProgressionMapper.toEntity
import com.artgrid.mobile.domain.progression.model.Progression
import com.artgrid.mobile.domain.progression.model.ProgressionStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressionRepositoryImpl @Inject constructor(
    private val dao: ProgressionDao,
) : ProgressionRepository {

    override fun observeAll(): Flow<List<Progression>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeStages(progressionId: Long): Flow<List<ProgressionStage>> =
        dao.observeStages(progressionId).map { list -> list.map { it.toDomain() } }

    override suspend fun findById(progressionId: Long): Progression? =
        dao.findById(progressionId)?.toDomain(dao.countStages(progressionId))

    override suspend fun create(title: String, description: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertProgression(
            ProgressionEntity(
                title       = title,
                description = description,
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
    }

    override suspend fun updateProgression(progression: Progression) {
        dao.updateProgression(
            progression.toEntity().copy(updatedAtMs = System.currentTimeMillis())
        )
    }

    override suspend fun deleteProgression(progressionId: Long) {
        dao.deleteProgressionById(progressionId)
    }

    override suspend fun addStage(
        progressionId: Long,
        label: String,
        localPath: String,
        sha256: String,
        note: String,
    ): Long {
        val nextOrder = dao.countStages(progressionId)
        return dao.insertStage(
            ProgressionStageEntity(
                progressionId = progressionId,
                stageOrder    = nextOrder,
                label         = label,
                localPath     = localPath,
                sha256        = sha256,
                note          = note,
                createdAtMs   = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun updateStage(stage: ProgressionStage) {
        dao.updateStage(stage.toEntity())
    }

    override suspend fun deleteStage(stageId: Long) {
        dao.deleteStageById(stageId)
    }

    override suspend fun reorderStages(progressionId: Long, orderedIds: List<Long>) {
        val stages = orderedIds.mapIndexedNotNull { index, id ->
            dao.findStageById(id)?.copy(stageOrder = index)
        }
        dao.reorderStages(stages)
    }
}
