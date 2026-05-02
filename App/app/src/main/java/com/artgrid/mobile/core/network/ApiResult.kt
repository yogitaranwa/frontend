/**
 * ApiResult.kt
 * Responsibility : Sealed wrapper for all network responses — ensures no raw exceptions
 *                  escape the data layer into ViewModels.
 * API calls      : none (infrastructure)
 * Injects        : none
 */
package com.artgrid.mobile.core.network

/**
 * Universal result type returned by every Repository function.
 * ViewModels pattern-match on this; raw exceptions never reach the UI layer.
 */
sealed class ApiResult<out T> {

    /** The call succeeded and [data] holds the parsed response. */
    data class Success<T>(val data: T) : ApiResult<T>()

    /** The server returned a non-2xx HTTP status with a parseable body. */
    data class Error(
        val code: Int,
        val message: String,
        val errors: Map<String, List<String>>? = null,
    ) : ApiResult<Nothing>()

    /** A transport-level failure: timeout, no connectivity, SSL error, etc. */
    data class NetworkError(val throwable: Throwable) : ApiResult<Nothing>()
}

/**
 * Convenience wrapper that calls [block] inside a try/catch and converts
 * [retrofit2.HttpException] to [ApiResult.Error] and any other exception
 * to [ApiResult.NetworkError].
 */
suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: retrofit2.HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: e.message()
    ApiResult.Error(code = e.code(), message = body ?: "HTTP ${e.code()}")
} catch (e: java.io.IOException) {
    ApiResult.NetworkError(e)
} catch (e: Exception) {
    ApiResult.NetworkError(e)
}
