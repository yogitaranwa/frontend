/**
 * ChatDto.kt
 * Responsibility : Moshi-annotated DTOs for artgrid-ai-proxy chat request body.
 *                  SSE response lines are parsed by SseReader, not Moshi.
 * API calls      : POST /api/v1/chat
 * Injects        : none (plain data container)
 */
package com.artgrid.mobile.data.chat.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Request body for POST /api/v1/chat.
 * [imageB64]: optional base64-encoded JPEG of the current reference image (max 1MB encoded).
 * [sessionId]: client-generated UUID grouping conversation turns for the same image session.
 */
@JsonClass(generateAdapter = true)
data class ChatRequestDto(
    @Json(name = "message")    val message: String,
    @Json(name = "image_b64")  val imageB64: String?,
    @Json(name = "session_id") val sessionId: String,
)
