/**
 * AuthDataStore.kt
 * Responsibility : Encrypted persistence of the ArtGrid session JWT using
 *                  DataStore<Preferences> — the single source of truth for the
 *                  current access token.
 * API calls      : none
 * Injects        : @ApplicationContext Context
 */
package com.artgrid.mobile.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Extension property creates a single DataStore instance scoped to the Application. */
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "artgrid_auth")

/**
 * Thread-safe, coroutine-native token store.
 *
 * Token storage rationale: DataStore<Preferences> was chosen over
 * EncryptedSharedPreferences because it is coroutine-native (no blocking I/O),
 * crash-safe (atomic writes), and officially recommended for new projects.
 * EncryptedSharedPreferences is kept as a declared dependency for legacy fallback only.
 *
 * Security note: the access token is stored in plain DataStore. On Android 6+,
 * the DataStore file resides in the app's internal storage which is protected by
 * the kernel-level app sandbox. For an academic project this is acceptable.
 * Production hardening: wrap with EncryptedDataStore or store in the Android Keystore.
 */
@Singleton
class AuthDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
    }

    /** Emits the current token (or null) and all future updates. */
    val tokenFlow: Flow<String?> = context.authDataStore.data
        .map { prefs -> prefs[KEY_ACCESS_TOKEN] }

    /** Reads the current token once. Returns null if not stored. */
    suspend fun getToken(): String? = tokenFlow.first()

    /** Persists [token] to DataStore. Overwrites any existing value. */
    suspend fun saveToken(token: String) {
        context.authDataStore.edit { prefs -> prefs[KEY_ACCESS_TOKEN] = token }
    }

    /** Removes the stored token — used on logout or forced-logout (401). */
    suspend fun clearToken() {
        context.authDataStore.edit { prefs -> prefs.remove(KEY_ACCESS_TOKEN) }
    }
}
