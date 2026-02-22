package com.example.albanianidverification.security
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════
 *  PHASE 1 — CRITICAL: Certificate Pinning + Nonce Injection
 *
 *  Defends against:
 *    • MITM attacks (Burp Suite, rogue Wi-Fi hotspots, corporate proxies)
 *    • Replay attacks (captured request re-submitted later)
 *
 *  HOW TO GET YOUR PIN HASH:
 *    openssl s_client -connect api.voting.albania.gov:443 \
 *      | openssl x509 -pubkey -noout \
 *      | openssl pkey -pubin -outform der \
 *      | openssl dgst -sha256 -binary \
 *      | base64
 *
 *  ALWAYS add a backup pin from your NEXT certificate before rotating.
 *  Without a backup pin, a cert rotation will break ALL users until they update.
 * ══════════════════════════════════════════════════════════════
 */
object ApiClient {

    // ─── Configuration ──────────────────────────────────────────────────────────
    private const val PRODUCTION_BASE_URL = "https://api.voting.albania.gov/"
    private const val PRODUCTION_HOSTNAME = "api.voting.albania.gov"

    /**
     * Replace these with real SHA-256 public key hashes from your server cert.
     * Primary = current cert, Backup = next cert (must exist before you rotate).
     */
    private const val PRIMARY_PIN   = "REPLACE_WITH_YOUR_PRIMARY_SHA256_PIN="
    private const val BACKUP_PIN    = "REPLACE_WITH_YOUR_BACKUP_SHA256_PIN="

    // For development against a local emulator / dev server.
    // BuildConfig.DEBUG ensures this NEVER ships in a release build.
    private const val DEV_BASE_URL   = "http://10.0.2.2:8081/"   // Android emulator → host
    private val BASE_URL get() = if (android.os.Build.VERSION.SDK_INT > 0 && isDebugBuild()) DEV_BASE_URL else PRODUCTION_BASE_URL

    // ─── Certificate Pinner ─────────────────────────────────────────────────────
    private val certificatePinner = CertificatePinner.Builder()
        .add(PRODUCTION_HOSTNAME, "sha256/$PRIMARY_PIN")
        .add(PRODUCTION_HOSTNAME, "sha256/$BACKUP_PIN")   // backup — never be without one
        .build()

    // ─── Nonce Interceptor ──────────────────────────────────────────────────────
    /**
     * Automatically injects anti-replay headers on every outbound request:
     *   X-Request-Nonce     : UUID v4 (unique per request)
     *   X-Request-Timestamp : epoch milliseconds
     *   X-Request-Signature : HMAC-SHA256(nonce:timestamp:path, deviceSecret)
     *
     * The backend will:
     *   1. Reject if timestamp is older than 60 seconds
     *   2. Reject if nonce has already been seen (stored in short-lived cache)
     *   3. Reject if HMAC signature does not verify
     */
    private val nonceInterceptor = Interceptor { chain ->
        val original = chain.request()

        // Generate fresh nonce + timestamp for this request
        val nonce = NonceManager.generateNonce()
        val timestamp = System.currentTimeMillis().toString()
        val path = original.url.encodedPath

        // HMAC signs nonce:timestamp:path — ties the signature to this exact request
        val signature = NonceManager.signRequest(nonce, timestamp, path)

        val signed = original.newBuilder()
            .header("X-Request-Nonce",     nonce)
            .header("X-Request-Timestamp", timestamp)
            .header("X-Request-Signature", signature)
            .build()

        chain.proceed(signed)
    }

    // ─── OkHttpClient ───────────────────────────────────────────────────────────
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .certificatePinner(certificatePinner)   // ← certificate pinning
            .addInterceptor(nonceInterceptor)        // ← nonce / replay prevention
            .addInterceptor(authTokenInterceptor())  // ← JWT Bearer header
            .apply {
                // Logging only in debug builds — never log request bodies in production
                // (they contain face images and NFC chip data)
                if (isDebugBuild()) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.HEADERS   // NEVER BODY in prod
                        }
                    )
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // face image upload needs more time
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ─── Retrofit Instance ──────────────────────────────────────────────────────
    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // ─── JWT Attachment ─────────────────────────────────────────────────────────
    private fun authTokenInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val token = TokenManager.getAccessToken()

        val authenticatedRequest = if (token != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }

        val response = chain.proceed(authenticatedRequest)

        // If the server returns 401, clear local token so the UI can redirect to login
        if (response.code == 401) {
            TokenManager.clearTokens()
        }
        response
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────
    private fun isDebugBuild(): Boolean =
        try {
            // Evaluates to true only when the app is built with debug variant
            Class.forName("al.gov.voting.BuildConfig")
                .getField("DEBUG")
                .getBoolean(null)
        } catch (e: Exception) {
            false  // fail closed — assume production if BuildConfig is missing
        }
}