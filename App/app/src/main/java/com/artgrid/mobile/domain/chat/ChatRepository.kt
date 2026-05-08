/**
 * ChatRepository.kt
 * Responsibility : Interface contract for the AI chatbot SSE endpoint.
 * API calls      : POST /api/v1/chat (streaming)
 * Injects        : none (interface)
 */
package com.artgrid.mobile.domain.chat

import com.artgrid.mobile.core.network.SseEvent
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    /**
     * Stream artist AI chat completions.
     *
     * Opens an SSE stream to the ai-proxy and returns a [Flow] of [SseEvent]s.
     * The flow completes when [SseEvent.Done] is emitted or the stream errors.
     *
     * [message]: user text, max 2000 chars.
     * [imageB64]: optional base64-encoded current reference JPEG (max 1MB encoded).
     * [sessionId]: client-generated UUID for this image session.
     *
     * The caller (ChatViewModel) is responsible for:
     *  - Appending [SseEvent.Delta.text] to the displayed assistant message.
     *  - Marking the message as complete on [SseEvent.Done].
     *  - Showing an error state on [SseEvent.StreamError].
     */
    fun streamChat(
        message: String,
        imageB64: String?,
        sessionId: String,
    ): Flow<SseEvent>
}
