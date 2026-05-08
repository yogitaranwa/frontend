/**
 * AuthRepository.kt
 * Responsibility : Interface contract for auth operations — the only auth type that
 *                  crosses into the ViewModel layer.
 * API calls      : POST /api/v1/auth/google, POST /api/v1/auth/refresh
 * Injects        : none (interface)
 */
package com.artgrid.mobile.domain.auth

import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.domain.auth.model.AuthSession

interface AuthRepository {

    /**
     * Google sign-in.
     * [idToken]: the ID token obtained from the Google Sign-In SDK.
     * In DEMO_MODE this is an empty string and the server accepts it unconditionally.
     */
    suspend fun signInWithGoogle(idToken: String): ApiResult<AuthSession>

    /**
     * Silent token refresh.
     * Should be called when the stored JWT is within 5 minutes of expiry.
     * Returns a new [AuthSession] with an updated [AuthSession.accessToken].
     */
    suspend fun refresh(): ApiResult<AuthSession>
}
