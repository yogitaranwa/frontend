/**
 * LoadingScreen.kt
 * Responsibility : Full-screen centered loading indicator — shown during async data fetches.
 * API calls      : none
 * Injects        : none
 */
package com.artgrid.mobile.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier          = modifier.fillMaxSize(),
        contentAlignment  = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
