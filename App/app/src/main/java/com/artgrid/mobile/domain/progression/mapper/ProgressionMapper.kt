/**
 * ProgressionMapper.kt
 * Responsibility : Maps Room entities ↔ Progression domain models.
 * Pattern used   : Mapper object with extension functions
 * Dependencies   : Room entities, domain models
 */
package com.artgrid.mobile.domain.progression.mapper

import com.artgrid.mobile.data.db.entity.ProgressionEntity
import com.artgrid.mobile.data.db.entity.ProgressionStageEntity
import com.artgrid.mobile.domain.progression.model.Progression
import com.artgrid.mobile.domain.progression.model.ProgressionStage

object ProgressionMapper {

    fun ProgressionEntity.toDomain(stageCount: Int = 0): Progression = Progression(
        id           = id,
        title        = title,
        description  = description,
        createdAtMs  = createdAtMs,
        updatedAtMs  = updatedAtMs,
        stageCount   = stageCount,
    )

    fun Progression.toEntity(): ProgressionEntity = ProgressionEntity(
        id          = id,
        title       = title,
        description = description,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    fun ProgressionStageEntity.toDomain(): ProgressionStage = ProgressionStage(
        id                 = id,
        progressionId      = progressionId,
        stageOrder         = stageOrder,
        label              = label,
        localPath          = localPath,
        sha256             = sha256,
        note               = note,
        createdAtMs        = createdAtMs,
        gridOverlayEnabled = gridOverlayEnabled,
        gridAlpha          = gridAlpha,
    )

    fun ProgressionStage.toEntity(): ProgressionStageEntity = ProgressionStageEntity(
        id                 = id,
        progressionId      = progressionId,
        stageOrder         = stageOrder,
        label              = label,
        localPath          = localPath,
        sha256             = sha256,
        note               = note,
        createdAtMs        = createdAtMs,
        gridOverlayEnabled = gridOverlayEnabled,
        gridAlpha          = gridAlpha,
    )
}
