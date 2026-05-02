/**
 * ReferenceAsset.kt
 * Responsibility : Domain model for a saved reference image (F-31 local history).
 * Pattern used   : Immutable domain model
 * Dependencies   : none
 */
package com.artgrid.mobile.domain.reference.model

data class ReferenceAsset(
    val id: Long,
    val localPath: String,
    val label: String,
    val note: String,
    val sha256: String,
    val createdAtMs: Long,
    val tag: String,
)
