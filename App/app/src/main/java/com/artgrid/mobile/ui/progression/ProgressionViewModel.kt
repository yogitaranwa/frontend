/**
 * ProgressionViewModel.kt
 * Responsibility : F-32 multi-stage progression comparator ViewModel.
 *                  Manages progression list, stage CRUD, and grid overlay state per stage.
 * Pattern used   : HiltViewModel + StateFlow
 * Dependencies   : ProgressionRepository
 */
package com.artgrid.mobile.ui.progression

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.domain.progression.ProgressionRepository
import com.artgrid.mobile.domain.progression.model.Progression
import com.artgrid.mobile.domain.progression.model.ProgressionStage
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
import java.security.MessageDigest
import javax.inject.Inject

data class ProgressionListUiState(
    val progressions: List<Progression> = emptyList(),
    val snackbarMessage: String?        = null,
    val showCreateDialog: Boolean       = false,
)

data class ProgressionDetailUiState(
    val progression: Progression?         = null,
    val stages: List<ProgressionStage>    = emptyList(),
    val compareIndexA: Int                = 0,
    val compareIndexB: Int                = 1,
    val gridOverlayEnabled: Boolean       = false,
    val gridOpacity: Float                = 0.4f,
    val snackbarMessage: String?          = null,
    val showAddStageDialog: Boolean       = false,
    val pendingStageUri: Uri?             = null,
)

@HiltViewModel
class ProgressionViewModel @Inject constructor(
    private val repo: ProgressionRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _listState   = MutableStateFlow(ProgressionListUiState())
    val listState: StateFlow<ProgressionListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(ProgressionDetailUiState())
    val detailState: StateFlow<ProgressionDetailUiState> = _detailState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { progressions ->
                _listState.update { it.copy(progressions = progressions) }
            }
        }
    }

    // ── List operations ───────────────────────────────────────────────────────

    fun showCreateDialog() { _listState.update { it.copy(showCreateDialog = true) } }
    fun dismissCreateDialog() { _listState.update { it.copy(showCreateDialog = false) } }

    fun createProgression(title: String, description: String) {
        viewModelScope.launch {
            repo.create(title.trim(), description.trim())
            _listState.update { it.copy(showCreateDialog = false, snackbarMessage = "Progression created") }
        }
    }

    fun deleteProgression(id: Long) {
        viewModelScope.launch {
            repo.deleteProgression(id)
            _listState.update { it.copy(snackbarMessage = "Deleted") }
        }
    }

    fun clearListSnackbar() { _listState.update { it.copy(snackbarMessage = null) } }

    // ── Detail operations ─────────────────────────────────────────────────────

    fun loadProgression(progressionId: Long) {
        viewModelScope.launch {
            val progression = repo.findById(progressionId)
            _detailState.update { it.copy(progression = progression) }
            repo.observeStages(progressionId).collect { stages ->
                _detailState.update { it.copy(stages = stages) }
            }
        }
    }

    fun setCompareA(index: Int) { _detailState.update { it.copy(compareIndexA = index) } }
    fun setCompareB(index: Int) { _detailState.update { it.copy(compareIndexB = index) } }

    fun toggleGridOverlay() { _detailState.update { it.copy(gridOverlayEnabled = !it.gridOverlayEnabled) } }
    fun setGridOpacity(alpha: Float) { _detailState.update { it.copy(gridOpacity = alpha.coerceIn(0f, 1f)) } }

    fun promptAddStage(uri: Uri) {
        _detailState.update { it.copy(pendingStageUri = uri, showAddStageDialog = true) }
    }

    fun dismissAddStage() {
        _detailState.update { it.copy(pendingStageUri = null, showAddStageDialog = false) }
    }

    fun confirmAddStage(label: String, note: String) {
        val progressionId = _detailState.value.progression?.id ?: return
        val uri           = _detailState.value.pendingStageUri ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                saveStageToDisk(uri, progressionId, label, note)
            }
            _detailState.update {
                it.copy(
                    showAddStageDialog = false,
                    pendingStageUri    = null,
                    snackbarMessage    = if (result) "Stage added" else "Failed to save image",
                )
            }
        }
    }

    fun deleteStage(stageId: Long) {
        viewModelScope.launch {
            repo.deleteStage(stageId)
            _detailState.update { it.copy(snackbarMessage = "Stage removed") }
        }
    }

    fun updateStageGrid(stage: ProgressionStage, enabled: Boolean, alpha: Float) {
        viewModelScope.launch {
            repo.updateStage(stage.copy(gridOverlayEnabled = enabled, gridAlpha = alpha))
        }
    }

    fun clearDetailSnackbar() { _detailState.update { it.copy(snackbarMessage = null) } }

    private suspend fun saveStageToDisk(uri: Uri, progressionId: Long, label: String, note: String): Boolean = try {
        val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return false
        val sha256 = sha256Hex(bytes)
        val dir = File(context.filesDir, "progressions/$progressionId").also { it.mkdirs() }
        val file = File(dir, "$sha256.jpg")
        if (!file.exists()) file.writeBytes(bytes)
        repo.addStage(progressionId, label = label, localPath = file.absolutePath, sha256 = sha256, note = note)
        true
    } catch (e: Exception) {
        false
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
