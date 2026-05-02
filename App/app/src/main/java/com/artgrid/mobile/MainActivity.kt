/**
 * MainActivity.kt
 * Responsibility : Single activity — sets edge-to-edge content and hosts the NavGraph.
 *                  AuthInterceptor is injected here to wire the logout-event flow into AppNavGraph.
 * API calls      : none (navigation host only)
 * Injects        : AuthInterceptor
 */
package com.artgrid.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.artgrid.mobile.core.network.AuthInterceptor
import com.artgrid.mobile.ui.navigation.AppNavGraph
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * AuthInterceptor is injected into MainActivity so it can be passed directly
     * to AppNavGraph. Injecting into the Activity (rather than inside a composable)
     * ensures Hilt manages the singleton lifecycle correctly.
     */
    @Inject
    lateinit var authInterceptor: AuthInterceptor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    AppNavGraph(authInterceptor = authInterceptor)
                }
            }
        }
    }
}
