/**
 * AuthInterceptor.kt
 * Responsibility : Injects "Authorization: Bearer <token>" into every outgoing OkHttp
 *                  request and emits a logout event on 401.
 * API calls      : none (infrastructure)
 * Injects        : AuthDataStore
 */
package com.artgrid.mobile.core.network

import com.artgrid.mobile.core.auth.AuthDataStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton interceptor attached to the OkHttpClient.
 * Reads the stored JWT from [AuthDataStore] synchronously via [runBlocking]
 * (safe here: OkHttp dispatches on its own IO threads, not the main thread).
 *
 * On HTTP 401 the token is cleared and [logoutEvent] emits — AppNavGraph
 * collects this flow and redirects to the sign-in screen.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authDataStore: AuthDataStore,
) : Interceptor {

    private val _logoutEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collect this in AppNavGraph to react to forced-logout events. */
    val logoutEvent: SharedFlow<Unit> = _logoutEvent.asSharedFlow()

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { authDataStore.getToken() }

        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        if (response.code == 401) {
            runBlocking { authDataStore.clearToken() }
            _logoutEvent.tryEmit(Unit)
        }

        return response
    }
}
