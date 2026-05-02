/**
 * NetworkModule.kt
 * Responsibility : Provides Moshi, three OkHttpClient/Retrofit pairs (auth / ml / proxy)
 *                  and all ApiService singletons via Hilt.
 * API calls      : none (DI configuration)
 * Injects        : AuthInterceptor, BuildConfig URLs
 */
package com.artgrid.mobile.core.di

import com.artgrid.mobile.BuildConfig
import com.artgrid.mobile.core.network.AuthInterceptor
import com.artgrid.mobile.data.auth.AuthApiService
import com.artgrid.mobile.data.chat.ChatApiService
import com.artgrid.mobile.data.ml.MlApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Three independent Retrofit instances because each backend service runs on a
 * different port with different auth requirements:
 *
 *  "auth"  → artgrid-auth-service  (port 8080) — mutual JWT verification
 *  "ml"    → artgrid-ml-service    (port 8001) — Bearer JWT, multipart images
 *  "proxy" → artgrid-ai-proxy      (port 8082) — custom X-Device-Token header
 *
 * The AuthInterceptor is attached to "ml" and "auth" clients only.
 * The "proxy" client uses a separate device-token header (set per-request in the repo).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Moshi instance with KotlinJsonAdapterFactory for automatic Kotlin data class support. */
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // ──────────────────────────────────────────────────────────────────────
    // Shared logging interceptor (DEBUG builds only)
    // ──────────────────────────────────────────────────────────────────────
    private fun buildLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // "auth" — artgrid-auth-service (Bearer JWT injected by AuthInterceptor)
    // ──────────────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthOkHttp(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(buildLoggingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthRetrofit(@Named("auth") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.AUTH_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAuthApiService(@Named("auth") retrofit: Retrofit): AuthApiService =
        retrofit.create(AuthApiService::class.java)

    // ──────────────────────────────────────────────────────────────────────
    // "ml" — artgrid-ml-service (Bearer JWT, multipart images)
    // Long timeouts: congested Wi‑Fi / slow links; image uploads need headroom beyond 1s RTT.
    // ──────────────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    @Named("ml")
    fun provideMlOkHttp(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(buildLoggingInterceptor())
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @Named("ml")
    fun provideMlRetrofit(@Named("ml") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.ML_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideMlApiService(@Named("ml") retrofit: Retrofit): MlApiService =
        retrofit.create(MlApiService::class.java)

    // ──────────────────────────────────────────────────────────────────────
    // "proxy" — artgrid-ai-proxy (custom X-Device-Token, SSE streaming, 60s)
    // No AuthInterceptor — auth header added manually per request in ChatRepositoryImpl.
    // ──────────────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    @Named("proxy")
    fun provideProxyOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(buildLoggingInterceptor())
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @Named("proxy")
    fun provideProxyRetrofit(@Named("proxy") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PROXY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideChatApiService(@Named("proxy") retrofit: Retrofit): ChatApiService =
        retrofit.create(ChatApiService::class.java)

    /** The raw proxy OkHttpClient is also exposed for SSE raw streaming calls in ChatRepositoryImpl. */
    @Provides
    @Singleton
    @Named("proxyRaw")
    fun provideProxyRawOkHttp(@Named("proxy") client: OkHttpClient): OkHttpClient = client
}
