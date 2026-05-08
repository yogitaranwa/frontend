/**
 * ChatRepositoryImpl.kt
 * Responsibility : Implements ChatRepository — builds the X-Device-Token HMAC header,
 *                  issues a raw OkHttp streaming request, and emits SseEvent via Flow.
 * API calls      : POST /api/v1/chat (SSE stream)
 * Injects        : @Named("proxyRaw") OkHttpClient, Moshi, AuthDataStore
 */
package com.artgrid.mobile.data.chat

import com.artgrid.mobile.BuildConfig
import com.artgrid.mobile.core.auth.AuthDataStore
import com.artgrid.mobile.core.network.SseEvent
import com.artgrid.mobile.core.network.parseSseStream
import com.artgrid.mobile.data.chat.dto.ChatRequestDto
import com.artgrid.mobile.domain.chat.ChatRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @Named("proxyRaw") private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val authDataStore: AuthDataStore,
) : ChatRepository {

    /**
     * Artist AI chatbot SSE stream.
     *
     * Why raw OkHttp instead of Retrofit here:
     * Retrofit's converter factory buffers the response body before returning, which
     * defeats streaming. A raw OkHttp call gives direct access to the ResponseBody
     * source so [parseSseStream] can read it line-by-line as tokens arrive.
     *
     * The proxy uses a custom HMAC auth scheme (per Prompt2output.md):
     *   X-Device-Token: <device_uuid>:<HMAC-SHA256(device_uuid, PROXY_SHARED_SECRET, unix_hour)>
     * In DEMO_MODE the server also accepts an empty HMAC fragment — we still send
     * the proper structure so demo and deployed code paths share the same implementation.
     */
    override fun streamChat(
        message: String,
        imageB64: String?,
        sessionId: String,
    ): Flow<SseEvent> = flow {
        val chatUrl = "${BuildConfig.PROXY_BASE_URL}api/v1/chat"

        // Resolve device UUID — we reuse the stored "user_id" as device identifier.
        // In demo mode this is "demo-user-id" per API contract.
        val deviceUuid = runBlocking { authDataStore.getToken() }
            ?.let { "device-${it.take(8)}" }  // derive stable device ID from token prefix
            ?: "demo-device"

        val deviceToken = buildDeviceToken(deviceUuid)

        val requestDto = ChatRequestDto(
            message   = message.take(2000),  // enforce 2000-char limit client-side
            imageB64  = imageB64,
            sessionId = sessionId,
        )

        val adapter = moshi.adapter(ChatRequestDto::class.java)
        val jsonBody = adapter.toJson(requestDto)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(chatUrl)
            .post(jsonBody)
            .addHeader("X-Device-Token", deviceToken)
            .addHeader("Accept", "text/event-stream")
            .build()

        val response = okHttpClient.newCall(request).execute()

        when {
            response.code == 429 -> {
                response.close()
                emit(SseEvent.StreamError("daily_limit_reached"))
                return@flow
            }
            response.code == 401 -> {
                response.close()
                emit(SseEvent.StreamError("invalid_device_token"))
                return@flow
            }
            !response.isSuccessful -> {
                response.close()
                emit(SseEvent.StreamError("http_${response.code}"))
                return@flow
            }
        }

        // parseSseStream owns and closes the response body via use{}
        parseSseStream(response).collect { event -> emit(event) }
    }
        .catch { e -> emit(SseEvent.StreamError("network_error: ${e.message}")) }
        .flowOn(Dispatchers.IO)

    // ── Private: HMAC device token ────────────────────────────────────────────

    /**
     * Builds the per-hour rotating X-Device-Token.
     * Format: <device_uuid>:<HMAC-SHA256 hex of "device_uuid|hour_timestamp">
     *
     * In DEMO_MODE PROXY_SHARED_SECRET is a build-time placeholder; the server
     * in demo mode accepts any structurally-valid token.
     */
    private fun buildDeviceToken(deviceUuid: String): String {
        // Unix hour (seconds ÷ 3600) — rotates the token every hour.
        val hourTimestamp = System.currentTimeMillis() / 1000L / 3600L
        val payload = "$deviceUuid|$hourTimestamp"

        // Secret from demo.properties (PROXY_SHARED_SECRET or PROXY_DEVICE_SECRET) via BuildConfig at build time.
        val secret = BuildConfig.PROXY_SHARED_SECRET

        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }.doFinal(payload.toByteArray(Charsets.UTF_8))

        val hexHmac = hmac.joinToString("") { "%02x".format(it) }
        return "$deviceUuid:$hexHmac"
    }
}
