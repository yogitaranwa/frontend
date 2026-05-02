/**
 * AuthSession.kt
 * Responsibility : Domain model for an authenticated session — zero Retrofit/Moshi annotations.
 * API calls      : none (pure domain type)
 * Injects        : none
 */
package com.artgrid.mobile.domain.auth.model

/**
 * Represents the result of a successful sign-in or token refresh.
 *
 * [userId] and [email] are null in DEMO_MODE per the API contract.
 * [isDemo] is the definitive flag used throughout the app to suppress
 * Google OAuth UI and show the "Demo Session" indicator.
 */
data class AuthSession(
    val accessToken: String,
    val expiresIn: Int,
    val userId: String?,
    val email: String?,
    val isDemo: Boolean,
)
