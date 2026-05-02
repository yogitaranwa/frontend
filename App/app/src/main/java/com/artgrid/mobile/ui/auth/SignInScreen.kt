/**
 * SignInScreen.kt
 * Responsibility : Google OAuth sign-in entry point.
 *                  Demo mode: shows a single "Sign in with Google" tap that calls
 *                  signIn("") — the server returns a mock JWT with no network required.
 *                  Deployed mode: integrates the Google Sign-In SDK to obtain a real ID token.
 * API calls      : POST /api/v1/auth/google (via AuthViewModel)
 * Injects        : AuthViewModel (hiltViewModel)
 */
package com.artgrid.mobile.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.BuildConfig
import com.artgrid.mobile.ui.common.ErrorScreen
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

// ── Screen composable (reads ViewModel state) ─────────────────────────────────

@Composable
fun SignInScreen(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        uiState.error != null -> ErrorScreen(
            message = uiState.error ?: "Unknown error",
            onRetry = if (BuildConfig.DEMO_MODE) {
                { viewModel.signIn("") }
            } else {
                null  // deployed mode: user must re-tap the button
            },
        )
        else -> SignInContent(
            isDemo   = BuildConfig.DEMO_MODE,
            onSignIn = viewModel::signIn,
            modifier = modifier,
        )
    }
}

// ── Content composable (pure data + lambdas) ──────────────────────────────────

@Composable
private fun SignInContent(
    isDemo: Boolean,
    onSignIn: (idToken: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Google Sign-In launcher — only relevant in deployed mode.
    // In demo mode the launcher is declared but never invoked.
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            runCatching { task.getResult(ApiException::class.java) }
                .onSuccess { account -> onSignIn(account.idToken ?: "") }
                .onFailure { /* sign-in cancelled or failed — user taps the button again */ }
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Branding ──────────────────────────────────────────────────────────
            Text(
                text      = "ArtGrid",
                style     = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color     = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text      = "Measure. Analyse. Create.",
                style     = MaterialTheme.typography.titleMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(56.dp))

            // ── Demo mode badge ───────────────────────────────────────────────────
            if (isDemo) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        text     = "DEMO MODE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Sign-in button ─────────────────────────────────────────────────────
            if (isDemo) {
                // Demo: one tap → mock JWT, no Google network call.
                Button(
                    onClick  = { onSignIn("") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text  = "Sign in with Google",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text  = "Demo session — no Google account required",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Deployed: launch Google Sign-In SDK flow.
                FilledTonalButton(
                    onClick  = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(context.getString(
                                context.resources.getIdentifier(
                                    "default_web_client_id", "string", context.packageName
                                )
                            ))
                            .requestEmail()
                            .build()
                        val client = GoogleSignIn.getClient(context, gso)
                        googleSignInLauncher.launch(client.signInIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text  = "Sign in with Google",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
