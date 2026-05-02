/**
 * SseReader.kt
 * Responsibility : Consumes an OkHttp streaming response for the F-24 AI chatbot SSE endpoint
 *                  and emits parsed [SseEvent] objects on a Kotlin Flow.
 * API calls      : POST /api/v1/chat (SSE transport layer only — parsing only)
 * Injects        : none (pure function utility)
 *
 * SSE wire format per Prompt2output.md:
 *   data: {"delta": "...", "done": false}
 *   data: {"done": true, "finish_reason": "stop", "total_tokens": 412}
 *   data: {"error": "gemini_overloaded", "done": true}
 */
package com.artgrid.mobile.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Response

/** Parsed representation of a single SSE data line from the AI proxy. */
sealed class SseEvent {
    /** A partial token chunk — append to the displayed message. */
    data class Delta(val text: String) : SseEvent()

    /** Stream ended successfully. */
    data class Done(val finishReason: String?, val totalTokens: Int?) : SseEvent()

    /** Midstream error from the proxy or Gemini API. */
    data class StreamError(val errorCode: String) : SseEvent()
}

/**
 * Reads [response] body line-by-line and emits [SseEvent]s.
 * Must be collected on an IO dispatcher. The caller owns the [Response]
 * and must close it after collection completes or the flow is cancelled.
 *
 * Non-"data:" lines (comments, heartbeats) are silently skipped per SSE spec.
 */
fun parseSseStream(response: Response): Flow<SseEvent> = flow {
    val body = response.body ?: return@flow

    body.source().use { source ->
        val buffer = okio.Buffer()
        while (!source.exhausted()) {
            source.read(buffer, 8192)
            while (true) {
                val line = buffer.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue

                val json = line.removePrefix("data:").trim()
                if (json.isEmpty()) continue

                // Minimal hand-rolled parsing to avoid a full JSON dependency here;
                // the NetworkModule's Moshi instance is not available in this utility.
                val isDone = json.contains("\"done\":true")
                val hasError = json.contains("\"error\":")

                when {
                    hasError -> {
                        val errorCode = json
                            .substringAfter("\"error\":\"", missingDelimiterValue = "")
                            .substringBefore("\"")
                        emit(SseEvent.StreamError(errorCode))
                    }
                    isDone -> {
                        val finishReason = json
                            .substringAfter("\"finish_reason\":\"", missingDelimiterValue = "")
                            .substringBefore("\"")
                            .takeIf { it.isNotEmpty() }
                        val totalTokens = json
                            .substringAfter("\"total_tokens\":", missingDelimiterValue = "")
                            .substringBefore("}", missingDelimiterValue = "")
                            .trim()
                            .toIntOrNull()
                        emit(SseEvent.Done(finishReason, totalTokens))
                        return@flow   // stream is finished
                    }
                    else -> {
                        val delta = json
                            .substringAfter("\"delta\":\"", missingDelimiterValue = "")
                            .substringBefore("\"")
                        if (delta.isNotEmpty()) emit(SseEvent.Delta(delta))
                    }
                }
            }
        }
    }
}
