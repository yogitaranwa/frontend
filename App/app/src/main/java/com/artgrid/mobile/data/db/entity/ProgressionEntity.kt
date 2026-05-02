/**
 * ProgressionEntity.kt
 * Responsibility : Room entities for progressions + progression_stages tables
 *                  — multi-stage artwork comparator (F-32).
 * Pattern used   : Room entity / relational data
 * Dependencies   : Room
 */
package com.artgrid.mobile.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "progressions")
data class ProgressionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Short title, e.g. "Mountain Landscape — April 2026". */
    @ColumnInfo(name = "title")
    val title: String,

    /** Optional freeform description. */
    @ColumnInfo(name = "description")
    val description: String = "",

    /** UNIX epoch millis of creation. */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** UNIX epoch millis of last update. */
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "progression_stages",
    foreignKeys = [
        ForeignKey(
            entity        = ProgressionEntity::class,
            parentColumns = ["id"],
            childColumns  = ["progression_id"],
            onDelete      = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("progression_id")],
)
data class ProgressionStageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "progression_id")
    val progressionId: Long,

    /** Display order within the progression (0-indexed). */
    @ColumnInfo(name = "stage_order")
    val stageOrder: Int,

    /** Label, e.g. "Sketch", "Inking", "Base color", "Shading". */
    @ColumnInfo(name = "label")
    val label: String,

    /** Absolute path to the image for this stage. */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    /** SHA-256 hex for cache deduplication. */
    @ColumnInfo(name = "sha256")
    val sha256: String,

    /** Optional freeform note per stage. */
    @ColumnInfo(name = "note")
    val note: String = "",

    /** UNIX epoch millis when this stage was added. */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** Whether the cm grid overlay is toggled on for this stage in the comparator. */
    @ColumnInfo(name = "grid_overlay_enabled")
    val gridOverlayEnabled: Boolean = false,

    /** Grid line alpha [0.0, 1.0] stored per stage. */
    @ColumnInfo(name = "grid_alpha")
    val gridAlpha: Float = 0.4f,
)
