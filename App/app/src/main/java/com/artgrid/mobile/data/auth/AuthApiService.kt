/**
 * AuthApiService.kt
 * Responsibility : Retrofit interface for artgrid-auth-service endpoints.
 * API calls      : POST /api/v1/auth/google, POST /api/v1/auth/refresh
 * Injects        : none (Retrofit creates the implementation)
 */
package com.artgrid.mobile.data.auth

import com.artgrid.mobile.data.auth.dto.AuthResponseDto
import com.artgrid.mobile.data.auth.dto.GoogleSignInRequestDto
import com.artgrid.mobile.data.auth.dto.RefreshResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {

    /**
     * F-00 · Google OAuth Sign-In (dual-mode).
     * Demo mode: server accepts any [idToken] and returns mock JWT.
     * Deployed mode: server validates idToken against Google JWK set.
     */
    @POST("api/v1/auth/google")
    suspend fun signInWithGoogle(
        @Body request: GoogleSignInRequestDto,
    ): AuthResponseDto

    /**
     * F-00 · Session refresh.
     * Called when the stored token is within 5 minutes of expiry.
     * No request body — the current JWT is injected by AuthInterceptor.
     */
    @POST("api/v1/auth/refresh")
    suspend fun refresh(): RefreshResponseDto
}
