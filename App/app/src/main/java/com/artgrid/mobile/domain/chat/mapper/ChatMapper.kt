/**
 * ChatMapper.kt
 * Responsibility : Converts raw SSE delta strings to ChatMessage domain objects.
 *                  Also provides factory functions for creating local message entries.
 * API calls      : none (mapping only)
 * Injects        : none
 */
package com.artgrid.mobile.domain.chat.mapper

import com.artgrid.mobile.domain.chat.model.ChatMessage
import com.artgrid.mobile.domain.chat.model.ChatRole
import java.util.UUID

object ChatMapper {

    /** Creates a user-turn [ChatMessage] before the request is sent. */
    fun createUserMessage(
        text: String,
        sessionId: String,
        imageRefPath: String?,
    ): ChatMessage = ChatMessage(
        id             = UUID.randomUUID().toString(),
        sessionId      = sessionId,
        role           = ChatRole.USER,
        text           = text,
        imageRefPath   = imageRefPath,
        finishReason   = null,
        isStreaming    = false,
        createdAtEpoch = System.currentTimeMillis(),
    )

    /**
     * Creates a placeholder assistant [ChatMessage] with [isStreaming] = true.
     * The ViewModel updates [text] in-place as SSE deltas arrive, then sets
     * [isStreaming] = false and [finishReason] on [SseEvent.Done].
     */
    fun createStreamingAssistantMessage(sessionId: String): ChatMessage = ChatMessage(
        id             = UUID.randomUUID().toString(),
        sessionId      = sessionId,
        role           = ChatRole.ASSISTANT,
        text           = "",
        imageRefPath   = null,
        finishReason   = null,
        isStreaming    = true,
        createdAtEpoch = System.currentTimeMillis(),
    )

    /** Finalises a streaming message when the SSE stream completes. */
    fun ChatMessage.withCompleted(finishReason: String?): ChatMessage =
        copy(isStreaming = false, finishReason = finishReason)

    /** Appends a delta token to a streaming assistant message. */
    fun ChatMessage.appendDelta(delta: String): ChatMessage =
        copy(text = text + delta)
}
