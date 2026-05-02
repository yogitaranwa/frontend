/**
 * ReferenceRepository.kt
 * Responsibility : Interface for reference history operations (F-31).
 * Pattern used   : Repository interface
 * Dependencies   : none (interface)
 */
package com.artgrid.mobile.domain.reference

import com.artgrid.mobile.domain.reference.model.ReferenceAsset
import kotlinx.coroutines.flow.Flow

interface ReferenceRepository {

    /** Observe all saved references, newest first. */
    fun observeAll(): Flow<List<ReferenceAsset>>

    /** Observe references filtered by [tag], newest first. */
    fun observeByTag(tag: String): Flow<List<ReferenceAsset>>

    /** Observe all distinct non-empty tags. */
    fun observeTags(): Flow<List<String>>

    /** Return asset by ID, or null if not found. */
    suspend fun findById(id: Long): ReferenceAsset?

    /**
     * Save a reference image.
     * Returns the new row ID.
     * If an image with the same SHA-256 already exists, it is returned unchanged
     * (caller should check [findBySha256] first to avoid duplicate paths).
     */
    suspend fun save(
        localPath: String,
        label: String,
        sha256: String,
        note: String = "",
        tag: String = "",
    ): Long

    /** Update label/note/tag for an existing reference. */
    suspend fun update(asset: ReferenceAsset)

    /** Permanently delete a reference by ID. Does NOT delete the file on disk. */
    suspend fun deleteById(id: Long)

    /** Returns existing asset if sha256 matches, null otherwise. */
    suspend fun findBySha256(sha256: String): ReferenceAsset?
}
