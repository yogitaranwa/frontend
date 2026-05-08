/**
 * ChatApiService.kt
 * Responsibility : Retrofit interface stub for the chat endpoint.
 *                  The actual SSE streaming uses a raw OkHttp call in ChatRepositoryImpl
 *                  because Retrofit does not natively support SSE streaming bodies.
 *                  This interface is kept for structural consistency and potential
 *                  non-streaming fallback use.
 * API calls      : POST /api/v1/chat (declaration only — raw OkHttp used for SSE)
 * Injects        : none (Retrofit creates the implementation)
 */
package com.artgrid.mobile.data.chat

import com.artgrid.mobile.data.chat.dto.ChatRequestDto
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

interface ChatApiService {

    /**
     * Artist AI chatbot — raw streaming endpoint.
     * Returns ResponseBody so the caller can read the SSE stream directly.
     * In practice, ChatRepositoryImpl bypasses Retrofit and issues a raw OkHttp
     * request to avoid Retrofit's buffered response handling. This declaration
     * remains for completeness and future non-streaming use.
     */
    @POST("api/v1/chat")
    suspend fun chat(
        @Body request: ChatRequestDto,
    ): ResponseBody
}
