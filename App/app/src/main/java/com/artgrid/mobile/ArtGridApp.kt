/**
 * ArtGridApp.kt
 * Responsibility : Hilt application entry point — annotated @HiltAndroidApp.
 * API calls      : none
 * Injects        : none (Hilt generates the component graph from this class)
 */
package com.artgrid.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ArtGridApp : Application()
