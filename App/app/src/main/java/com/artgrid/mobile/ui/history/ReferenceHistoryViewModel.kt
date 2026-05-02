/**
 * ReferenceHistoryViewModel.kt
 * Responsibility : F-31 reference history — load/save/delete saved reference images
 *                  from local Room database.
 * Pattern used   : HiltViewModel + StateFlow
 * Dependencies   : ReferenceRepository
 */
package com.artgrid.mobile.ui.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.domain.reference.ReferenceRepository
import com.artgrid.mobile.domain.reference.model.ReferenceAsset
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject

data class ReferenceHistoryUiState(
    val assets: List<ReferenceAsset>  = emptyList(),
    val tags: List<String>            = emptyList(),
    val selectedTag: String?          = null,
    val isSaving: Boolean             = false,
    val snackbarMessage: String?      = null,
    val editingAsset: ReferenceAsset? = null,
)

@HiltViewModel
class ReferenceHistoryViewModel @Inject constructor(
    private val repo: ReferenceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReferenceHistoryUiState())
    val uiState: StateFlow<ReferenceHistoryUiState> = _uiState.asStateFlow()

    init {
        observeAssets()
        observeTags()
    }

    private fun observeAssets() {
        viewModelScope.launch {
            repo.observeAll().collect { assets ->
                _uiState.update { it.copy(assets = assets) }
            }
        }
    }

    private fun observeTags() {
        viewModelScope.launch {
            repo.observeTags().collect { tags ->
                _uiState.update { it.copy(tags = tags) }
            }
        }
    }

    fun filterByTag(tag: String?) {
        _uiState.update { it.copy(selectedTag = tag) }
        if (tag == null) observeAssets()
        else viewModelScope.launch {
            repo.observeByTag(tag).collect { assets ->
                _uiState.update { it.copy(assets = assets) }
            }
        }
    }

    fun saveImage(uri: Uri, label: String, tag: String, note: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = withContext(Dispatchers.IO) { saveImageToLocal(uri, label, tag, note) }
            _uiState.update {
                it.copy(
                    isSaving       = false,
                    snackbarMessage = if (result) "Saved to history" else "Failed to save image",
                )
            }
        }
    }

    fun deleteAsset(id: Long) {
        viewModelScope.launch {
            repo.deleteById(id)
            _uiState.update { it.copy(snackbarMessage = "Reference deleted") }
        }
    }

    fun startEdit(asset: ReferenceAsset) {
        _uiState.update { it.copy(editingAsset = asset) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingAsset = null) }
    }

    fun commitEdit(label: String, note: String, tag: String) {
        val asset = _uiState.value.editingAsset ?: return
        viewModelScope.launch {
            repo.update(asset.copy(label = label, note = note, tag = tag))
            _uiState.update { it.copy(editingAsset = null, snackbarMessage = "Updated") }
        }
    }

    fun clearSnackbar() { _uiState.update { it.copy(snackbarMessage = null) } }

    private suspend fun saveImageToLocal(uri: Uri, label: String, tag: String, note: String): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bytes       = inputStream.readBytes()
            inputStream.close()
            val sha256 = sha256Hex(bytes)
            if (repo.findBySha256(sha256) != null) return true
            val file = File(context.filesDir, "refs/${sha256}.jpg").also { it.parentFile?.mkdirs() }
            file.writeBytes(bytes)
            repo.save(localPath = file.absolutePath, label = label, sha256 = sha256, note = note, tag = tag)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
