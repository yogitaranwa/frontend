/**
 * ChatViewModel.kt
 * Responsibility : Manages the F-24 AI chatbot conversation — accumulates SSE delta
 *                  tokens into chat messages and exposes the full history as StateFlow.
 * API calls      : POST /api/v1/chat (streaming, via ChatRepository)
 * Injects        : ChatRepository
 */
package com.artgrid.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.core.network.SseEvent
import com.artgrid.mobile.domain.chat.ChatRepository
import com.artgrid.mobile.domain.chat.mapper.ChatMapper.appendDelta
import com.artgrid.mobile.domain.chat.mapper.ChatMapper.createStreamingAssistantMessage
import com.artgrid.mobile.domain.chat.mapper.ChatMapper.createUserMessage
import com.artgrid.mobile.domain.chat.mapper.ChatMapper.withCompleted
import com.artgrid.mobile.domain.chat.model.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// ── UI State ─────────────────────────────────────────────────────────────────

data class ChatUiState(
    val messages:       List<ChatMessage> = emptyList(),
    val isStreaming:    Boolean           = false,
    val inputText:      String            = "",
    val errorBanner:    String?           = null,
    val dailyLimitHit:  Boolean           = false,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Stable session ID groups all messages in this ViewModel lifecycle. */
    private val sessionId: String = UUID.randomUUID().toString()

    /** Updates the draft text in the input field. */
    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text, errorBanner = null)
    }

    /**
     * F-24 · Send a message to the AI chatbot.
     * [imageB64]: optional base64-encoded current reference image.
     *
     * Flow:
     *  1. Append user message to history immediately (optimistic UI).
     *  2. Append streaming assistant placeholder.
     *  3. Collect SSE events — accumulate deltas into placeholder.
     *  4. On Done: mark assistant message complete.
     *  5. On StreamError: replace placeholder with an error state message.
     */
    fun sendMessage(imageB64: String? = null) {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty() || _uiState.value.isStreaming) return

        // Clear input and lock sending while streaming.
        _uiState.value = _uiState.value.copy(inputText = "", isStreaming = true, errorBanner = null)

        // Append user turn.
        val userMessage = createUserMessage(text = text, sessionId = sessionId, imageRefPath = null)
        appendMessage(userMessage)

        // Append streaming assistant placeholder.
        val assistantPlaceholder = createStreamingAssistantMessage(sessionId = sessionId)
        appendMessage(assistantPlaceholder)
        val assistantId = assistantPlaceholder.id

        viewModelScope.launch {
            chatRepository.streamChat(
                message   = text,
                imageB64  = imageB64,
                sessionId = sessionId,
            )
                .catch { e ->
                    updateAssistantMessage(assistantId) {
                        withCompleted(finishReason = "error")
                            .copy(text = "⚠ Connection error: ${e.message}")
                    }
                    _uiState.value = _uiState.value.copy(isStreaming = false)
                }
                .collect { event ->
                    when (event) {
                        is SseEvent.Delta -> {
                            updateAssistantMessage(assistantId) { appendDelta(event.text) }
                        }
                        is SseEvent.Done -> {
                            updateAssistantMessage(assistantId) { withCompleted(event.finishReason) }
                            _uiState.value = _uiState.value.copy(isStreaming = false)
                        }
                        is SseEvent.StreamError -> {
                            val errorText = when (event.errorCode) {
                                "daily_limit_reached" -> "Daily limit reached (50 messages). Resets at midnight UTC."
                                "invalid_device_token" -> "Authentication error — please restart the app."
                                else -> "AI response error: ${event.errorCode}"
                            }
                            updateAssistantMessage(assistantId) {
                                withCompleted("error").copy(text = "⚠ $errorText")
                            }
                            val isLimit = event.errorCode == "daily_limit_reached"
                            _uiState.value = _uiState.value.copy(
                                isStreaming   = false,
                                dailyLimitHit = isLimit,
                                errorBanner   = if (!isLimit) errorText else null,
                            )
                        }
                    }
                }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun appendMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + message,
        )
    }

    /** Finds the assistant message by ID and applies [transform] to it in-place. */
    private fun updateAssistantMessage(id: String, transform: ChatMessage.() -> ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map { msg ->
                if (msg.id == id) msg.transform() else msg
            },
        )
    }
}
