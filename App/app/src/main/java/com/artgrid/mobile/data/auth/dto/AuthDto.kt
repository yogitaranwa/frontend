/**
 * AuthDto.kt
 * Responsibility : Moshi-annotated DTOs for auth service request/response bodies.
 *                  Mirrors the Prompt2output.md auth contract exactly — no extra fields.
 * API calls      : POST /api/v1/auth/google, POST /api/v1/auth/refresh
 * Injects        : none (plain data containers)
 */
package com.artgrid.mobile.data.auth.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Requests ─────────────────────────────────────────────────────────────────

/**
 * Body for POST /api/v1/auth/google.
 * In DEMO_MODE the server ignores [idToken]; an empty string is accepted.
 */
@JsonClass(generateAdapter = true)
data class GoogleSignInRequestDto(
    @Json(name = "id_token") val idToken: String,
)

// ── Responses ─────────────────────────────────────────────────────────────────

/**
 * Successful response from POST /api/v1/auth/google.
 * [userId] and [email] are null in demo mode per API contract.
 */
@JsonClass(generateAdapter = true)
data class AuthResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type")   val tokenType: String,
    @Json(name = "expires_in")   val expiresIn: Int,
    @Json(name = "user_id")      val userId: String?,
    @Json(name = "email")        val email: String?,
    @Json(name = "is_demo")      val isDemo: Boolean,
)

/**
 * Successful response from POST /api/v1/auth/refresh.
 * Slimmer shape — only the new token and its TTL.
 */
@JsonClass(generateAdapter = true)
data class RefreshResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in")   val expiresIn: Int,
)

/**
 * Server error body returned on 401 / 503 / 400.
 * Used for display only; code inspection uses HTTP status code.
 */
@JsonClass(generateAdapter = true)
data class AuthErrorDto(
    @Json(name = "error")   val error: String,
    @Json(name = "message") val message: String,
)
