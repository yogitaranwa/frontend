/**
 * ArtGridDatabase.kt
 * Responsibility : Room database — single entry point for all local SQLite tables.
 *                  Tables: reference_assets, progressions, progression_stages.
 * Pattern used   : Room singleton database
 * Dependencies   : Room, Hilt (provided via DatabaseModule)
 */
package com.artgrid.mobile.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.artgrid.mobile.data.db.dao.ProgressionDao
import com.artgrid.mobile.data.db.dao.ReferenceAssetDao
import com.artgrid.mobile.data.db.entity.ProgressionEntity
import com.artgrid.mobile.data.db.entity.ProgressionStageEntity
import com.artgrid.mobile.data.db.entity.ReferenceAssetEntity

@Database(
    entities = [
        ReferenceAssetEntity::class,
        ProgressionEntity::class,
        ProgressionStageEntity::class,
    ],
    version  = 1,
    exportSchema = false,
)
abstract class ArtGridDatabase : RoomDatabase() {
    abstract fun referenceAssetDao(): ReferenceAssetDao
    abstract fun progressionDao(): ProgressionDao
}
