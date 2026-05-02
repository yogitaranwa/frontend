/**
 * AuthViewModel.kt
 * Responsibility : Manages sign-in, demo-mode bypass, and session initialisation.
 *                  Reads DEMO_MODE from BuildConfig: if true, triggers a mock sign-in
 *                  immediately without showing the Google OAuth flow.
 * API calls      : POST /api/v1/auth/google (sign-in), POST /api/v1/auth/refresh
 * Injects        : AuthRepository, AuthDataStore
 */
package com.artgrid.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.BuildConfig
import com.artgrid.mobile.core.auth.AuthDataStore
import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.domain.auth.AuthRepository
import com.artgrid.mobile.domain.auth.model.AuthSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UI State ─────────────────────────────────────────────────────────────────

data class AuthUiState(
    val isLoading:       Boolean     = false,
    val isAuthenticated: Boolean     = false,
    val isInitialized:   Boolean     = false,   // false until cold-start check completes
    val isDemo:          Boolean     = false,
    val error:           String?     = null,
    val session:         AuthSession? = null,
)

sealed class AuthUiEvent {
    data object NavigateToHome : AuthUiEvent()
}

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authDataStore: AuthDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /**
     * Called from MainActivity.onCreate / AppNavGraph on cold start.
     *
     * Flow:
     *  1. If DEMO_MODE AND no stored token → auto sign-in with empty ID token.
     *  2. If token already stored → attempt a silent refresh.
     *  3. Otherwise mark as initialized and unauthenticated (show sign-in screen).
     */
    fun initialize() {
        if (_uiState.value.isInitialized) return   // guard against double-init on recomposition
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val storedToken = authDataStore.getToken()

            when {
                BuildConfig.DEMO_MODE && storedToken == null -> {
                    // Demo cold-start: sign in automatically with empty token.
                    handleSignIn(authRepository.signInWithGoogle(idToken = ""))
                }
                storedToken != null -> {
                    // Existing session: attempt silent refresh.
                    when (val result = authRepository.refresh()) {
                        is ApiResult.Success -> applySession(result.data)
                        is ApiResult.Error,
                        is ApiResult.NetworkError -> {
                            // Refresh failed (network error) — still allow access if
                            // a token exists; hard failures will surface on the first API call.
                            _uiState.value = _uiState.value.copy(
                                isLoading       = false,
                                isAuthenticated = true,
                                isInitialized   = true,
                                isDemo          = BuildConfig.DEMO_MODE,
                            )
                        }
                    }
                }
                else -> {
                    // No token — show sign-in screen.
                    _uiState.value = _uiState.value.copy(
                        isLoading     = false,
                        isInitialized = true,
                    )
                }
            }
        }
    }

    /**
     * Called by SignInScreen when the user taps "Sign in with Google".
     * [idToken]: the Google ID token from the Google Sign-In SDK.
     *            In DEMO_MODE the sign-in screen is not shown, but this can also
     *            be called directly with an empty string for programmatic demo sign-in.
     */
    fun signIn(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            handleSignIn(authRepository.signInWithGoogle(idToken = idToken))
        }
    }

    /** Clears the stored token and resets state to show the sign-in screen. */
    fun signOut() {
        viewModelScope.launch {
            authDataStore.clearToken()
            _uiState.value = AuthUiState(isInitialized = true)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun handleSignIn(result: ApiResult<AuthSession>) {
        when (result) {
            is ApiResult.Success  -> applySession(result.data)
            is ApiResult.Error    -> _uiState.value = _uiState.value.copy(
                isLoading = false,
                isInitialized = true,
                error = result.message,
            )
            is ApiResult.NetworkError -> {
                val detail = if (BuildConfig.DEBUG) {
                    " (${result.throwable.javaClass.simpleName}: ${result.throwable.message})"
                } else {
                    ""
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialized = true,
                    error = "No network connection. Check your Wi-Fi and try again.$detail",
                )
            }
        }
    }

    private fun applySession(session: AuthSession) {
        _uiState.value = _uiState.value.copy(
            isLoading       = false,
            isAuthenticated = true,
            isInitialized   = true,
            isDemo          = session.isDemo,
            error           = null,
            session         = session,
        )
    }
}
