/**
 * ReferenceAssetEntity.kt
 * Responsibility : Room entity for the reference_assets table — stores saved
 *                  reference images with metadata for local history.
 * Pattern used   : Room entity / data holder
 * Dependencies   : Room
 */
package com.artgrid.mobile.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reference_assets")
data class ReferenceAssetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Absolute path to the cached image on device storage. */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    /** User-supplied label (e.g. "Outer lines pass 1"). */
    @ColumnInfo(name = "label")
    val label: String,

    /** Optional freeform note. */
    @ColumnInfo(name = "note")
    val note: String = "",

    /** SHA-256 hex of the image bytes — deduplication key. */
    @ColumnInfo(name = "sha256")
    val sha256: String,

    /** UNIX epoch millis when saved. */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** Optional tag for grouping (e.g. project name). */
    @ColumnInfo(name = "tag")
    val tag: String = "",
)
