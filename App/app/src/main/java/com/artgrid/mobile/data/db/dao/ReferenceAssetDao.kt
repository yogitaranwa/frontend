/**
 * ReferenceAssetDao.kt
 * Responsibility : Room DAO for the reference_assets table (F-31 local history).
 * Pattern used   : Room DAO
 * Dependencies   : Room, Coroutines Flow
 */
package com.artgrid.mobile.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.artgrid.mobile.data.db.entity.ReferenceAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReferenceAssetDao {

    @Query("SELECT * FROM reference_assets ORDER BY created_at_ms DESC")
    fun observeAll(): Flow<List<ReferenceAssetEntity>>

    @Query("SELECT * FROM reference_assets WHERE tag = :tag ORDER BY created_at_ms DESC")
    fun observeByTag(tag: String): Flow<List<ReferenceAssetEntity>>

    @Query("SELECT * FROM reference_assets WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ReferenceAssetEntity?

    @Query("SELECT * FROM reference_assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): ReferenceAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReferenceAssetEntity): Long

    @Update
    suspend fun update(entity: ReferenceAssetEntity)

    @Delete
    suspend fun delete(entity: ReferenceAssetEntity)

    @Query("DELETE FROM reference_assets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT DISTINCT tag FROM reference_assets WHERE tag != '' ORDER BY tag ASC")
    fun observeDistinctTags(): Flow<List<String>>
}
