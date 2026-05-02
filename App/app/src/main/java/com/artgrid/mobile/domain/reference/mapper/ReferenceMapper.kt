/**
 * ReferenceMapper.kt
 * Responsibility : Maps ReferenceAssetEntity ↔ ReferenceAsset domain model.
 * Pattern used   : Mapper object with extension functions
 * Dependencies   : Room entity, domain model
 */
package com.artgrid.mobile.domain.reference.mapper

import com.artgrid.mobile.data.db.entity.ReferenceAssetEntity
import com.artgrid.mobile.domain.reference.model.ReferenceAsset

object ReferenceMapper {

    fun ReferenceAssetEntity.toDomain(): ReferenceAsset = ReferenceAsset(
        id          = id,
        localPath   = localPath,
        label       = label,
        note        = note,
        sha256      = sha256,
        createdAtMs = createdAtMs,
        tag         = tag,
    )

    fun ReferenceAsset.toEntity(): ReferenceAssetEntity = ReferenceAssetEntity(
        id          = id,
        localPath   = localPath,
        label       = label,
        note        = note,
        sha256      = sha256,
        createdAtMs = createdAtMs,
        tag         = tag,
    )
}
