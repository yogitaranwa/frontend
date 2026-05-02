/**
 * Progression.kt
 * Responsibility : Domain models for multi-stage artwork progression (F-32).
 * Pattern used   : Immutable domain models
 * Dependencies   : none
 */
package com.artgrid.mobile.domain.progression.model

data class Progression(
    val id: Long,
    val title: String,
    val description: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val stageCount: Int = 0,
)

data class ProgressionStage(
    val id: Long,
    val progressionId: Long,
    val stageOrder: Int,
    val label: String,
    val localPath: String,
    val sha256: String,
    val note: String,
    val createdAtMs: Long,
    val gridOverlayEnabled: Boolean,
    val gridAlpha: Float,
)
