/**
 * AuthRepositoryImpl.kt
 * Responsibility : Implements AuthRepository — calls AuthApiService, maps DTOs to
 *                  domain models, persists the access token, wraps in ApiResult.
 * API calls      : POST /api/v1/auth/google, POST /api/v1/auth/refresh
 * Injects        : AuthApiService, AuthDataStore
 */
package com.artgrid.mobile.data.auth

import com.artgrid.mobile.BuildConfig
import com.artgrid.mobile.core.auth.AuthDataStore
import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.core.network.safeApiCall
import com.artgrid.mobile.data.auth.dto.GoogleSignInRequestDto
import com.artgrid.mobile.domain.auth.AuthRepository
import com.artgrid.mobile.domain.auth.mapper.AuthMapper.toDomain
import com.artgrid.mobile.domain.auth.model.AuthSession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiService: AuthApiService,
    private val authDataStore: AuthDataStore,
) : AuthRepository {

    /**
     * Google sign-in (demo / production dual-mode).
     *
     * DEMO_MODE: [idToken] is an empty string; the server returns a mock JWT.
     * Deployed : [idToken] is the real Google ID token from the Google Sign-In SDK.
     *
     * On success the access token is persisted to DataStore so the AuthInterceptor
     * can attach it to subsequent ML and proxy requests.
     */
    override suspend fun signInWithGoogle(idToken: String): ApiResult<AuthSession> {
        // In demo mode the server accepts an empty token; still send the field so
        // the request body is structurally valid.
        val requestToken = if (BuildConfig.DEMO_MODE) "" else idToken
        return safeApiCall {
            val dto = apiService.signInWithGoogle(GoogleSignInRequestDto(idToken = requestToken))
            authDataStore.saveToken(dto.accessToken)
            dto.toDomain()
        }
    }

    /**
     * Silent access-token refresh.
     * The current JWT is already injected by AuthInterceptor — no extra header needed.
     */
    override suspend fun refresh(): ApiResult<AuthSession> = safeApiCall {
        val dto = apiService.refresh()
        authDataStore.saveToken(dto.accessToken)
        dto.toDomain()
    }
}
