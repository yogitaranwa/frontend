/**
 * ReferenceRepositoryImpl.kt
 * Responsibility : Implements ReferenceRepository using Room DAO (local reference history).
 * Pattern used   : Repository implementation
 * Dependencies   : ReferenceAssetDao, ReferenceMapper
 */
package com.artgrid.mobile.data.reference

import com.artgrid.mobile.data.db.dao.ReferenceAssetDao
import com.artgrid.mobile.data.db.entity.ReferenceAssetEntity
import com.artgrid.mobile.domain.reference.ReferenceRepository
import com.artgrid.mobile.domain.reference.mapper.ReferenceMapper.toDomain
import com.artgrid.mobile.domain.reference.mapper.ReferenceMapper.toEntity
import com.artgrid.mobile.domain.reference.model.ReferenceAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReferenceRepositoryImpl @Inject constructor(
    private val dao: ReferenceAssetDao,
) : ReferenceRepository {

    override fun observeAll(): Flow<List<ReferenceAsset>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByTag(tag: String): Flow<List<ReferenceAsset>> =
        dao.observeByTag(tag).map { list -> list.map { it.toDomain() } }

    override fun observeTags(): Flow<List<String>> = dao.observeDistinctTags()

    override suspend fun findById(id: Long): ReferenceAsset? =
        dao.findById(id)?.toDomain()

    override suspend fun findBySha256(sha256: String): ReferenceAsset? =
        dao.findBySha256(sha256)?.toDomain()

    override suspend fun save(
        localPath: String,
        label: String,
        sha256: String,
        note: String,
        tag: String,
    ): Long {
        val entity = ReferenceAssetEntity(
            localPath   = localPath,
            label       = label,
            sha256      = sha256,
            note        = note,
            tag         = tag,
            createdAtMs = System.currentTimeMillis(),
        )
        return dao.insert(entity)
    }

    override suspend fun update(asset: ReferenceAsset) {
        dao.update(asset.toEntity())
    }

    override suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}
