package com.example.albanianidverification.security

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.albanianidverification.BuildConfig
import com.example.albanianidverification.api.VotingApiService
import com.example.albanianidverification.utils.LocalDateAdapter
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.google.gson.GsonBuilder
import java.time.LocalDate
/**
 * ══════════════════════════════════════════════════════════════
 *  ApiClient — Certificate Pinning + Nonce + Device binding
 * ══════════════════════════════════════════════════════════════
 */
object ApiClient {

    // ── Configuration ────────────────────────────────────────────────────────
    private const val PRODUCTION_BASE_URL = "https://api.voting.albania.gov/"
    private const val PRODUCTION_HOSTNAME = "api.voting.albania.gov"
    private const val PRIMARY_PIN         = "REPLACE_WITH_YOUR_PRIMARY_SHA256_PIN="
    private const val BACKUP_PIN          = "REPLACE_WITH_YOUR_BACKUP_SHA256_PIN="

    private val isDebug: Boolean get() = BuildConfig.DEBUG
    @RequiresApi(Build.VERSION_CODES.O)
    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
        .create()
    // BASE_URL comes directly from build.gradle.kts buildConfigField —
    // "http://10.0.2.2:8081/" in debug, "https://api.voting.albania.gov/" in release.
    private val BASE_URL get() = BuildConfig.API_URL

    // ── Certificate Pinner ───────────────────────────────────────────────────
    // BUG FIX 2: In debug builds, skip cert pinning entirely.
    // The pinner was configured for "api.voting.albania.gov" but requests go to
    // "10.0.2.2:8081" — OkHttp silently cancels any request where the host
    // doesn't match a pinned entry.
    private val certificatePinner: CertificatePinner? get() =
        if (isDebug) null
        else CertificatePinner.Builder()
            .add(PRODUCTION_HOSTNAME, "sha256/$PRIMARY_PIN")
            .add(PRODUCTION_HOSTNAME, "sha256/$BACKUP_PIN")
            .build()

    // ── Nonce Interceptor ────────────────────────────────────────────────────
    private val nonceInterceptor = Interceptor { chain ->
        val original  = chain.request()
        val nonce     = NonceManager.generateNonce()
        val timestamp = System.currentTimeMillis().toString()
        val path      = original.url.encodedPath
        val signature = NonceManager.signRequest(nonce, timestamp, path)

        chain.proceed(
            original.newBuilder()
                .header("X-Request-Nonce",     nonce)
                .header("X-Request-Timestamp", timestamp)
                .header("X-Request-Signature", signature)
                .build()
        )
    }

    // ── Device Interceptor ───────────────────────────────────────────────────
    private val deviceInterceptor = Interceptor { chain ->
        val deviceId     = DeviceManager.getDeviceId()
        val deviceSecret = DeviceManager.getDeviceSecretBase64()

        chain.proceed(
            chain.request().newBuilder()
                .header("X-Device-ID",     deviceId)
                .header("X-Device-Secret", deviceSecret)
                .build()
        )
    }

    // ── Auth Token Interceptor ───────────────────────────────────────────────
    private fun authTokenInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val token   = TokenManager.getAccessToken()

        val authedRequest = if (token != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }

        val response = chain.proceed(authedRequest)
        if (response.code == 401) TokenManager.clearTokens()
        response
    }

    // ── OkHttpClient ─────────────────────────────────────────────────────────
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                // BUG FIX 3: Only attach the cert pinner in production.
                // certificatePinner is null in debug builds (see above).
                certificatePinner?.let { certificatePinner(it) }
            }
            .addInterceptor(nonceInterceptor)        // replay prevention
            .addInterceptor(deviceInterceptor)       // device binding
            .addInterceptor(authTokenInterceptor())  // JWT Bearer
            .apply {
                if (isDebug) {
                    // Full header logging so you can see exactly what leaves the device
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        }
                    )
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // face image upload needs headroom
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ── Retrofit Instance ────────────────────────────────────────────────────
    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    // ── Service ──────────────────────────────────────────────────────────────
    val votingService: VotingApiService by lazy {
        retrofit.create(VotingApiService::class.java)
    }
}