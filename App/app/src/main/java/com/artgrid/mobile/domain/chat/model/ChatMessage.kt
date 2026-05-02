/**
 * ChatMessage.kt
 * Responsibility : Domain model for a single AI chat conversation turn.
 * API calls      : none (pure domain type)
 * Injects        : none
 */
package com.artgrid.mobile.domain.chat.model

/** Whether this turn was written by the user or the AI assistant. */
enum class ChatRole { USER, ASSISTANT }

/**
 * A single conversation turn stored locally in SQLite (chat_history table).
 *
 * [sessionId]: client-generated UUID grouping all turns for one image session.
 * [imageRefPath]: path to the image sent with this turn (user turns only; null otherwise).
 * [finishReason]: "stop" | "error" | null — populated only for assistant turns.
 * [isStreaming]: true while the assistant is still generating (UI shows in-progress cursor).
 */
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: ChatRole,
    val text: String,
    val imageRefPath: String?,
    val finishReason: String?,
    val isStreaming: Boolean,
    val createdAtEpoch: Long,
)
