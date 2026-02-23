package com.example.albanianidverification.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Manages request nonces and HMAC-SHA256 request signing.
 *
 * The HMAC key is generated once and stored in the AndroidKeyStore.
 *
 * ⚠️ Important: AndroidKeyStore does NOT expose HmacSHA256 via the JCA Mac
 * interface (no such algorithm for provider AndroidKeyStore). The correct
 * approach is:
 *   1. Generate/store the key in AndroidKeyStore (for hardware-backed protection).
 *   2. Load the key object from the Keystore.
 *   3. Pass the key to Mac.getInstance("HmacSHA256") WITHOUT specifying
 *      "AndroidKeyStore" as the provider — the default provider handles the
 *      actual computation.
 *
 * Call [initialize] once from Application.onCreate before any network call.
 */
object NonceManager {

    private const val KEY_ALIAS = "evoting_nonce_hmac_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    private var initialized = false

    // ── Public API ───────────────────────────────────────────────────────────

    fun initialize(context: Context) = initialise(context)

    fun initialise(context: Context) {
        if (initialized) return
        ensureKeyExists()
        initialized = true
    }

    /** Returns a fresh 16-byte nonce as a Base64 NO_WRAP string. */
    fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Returns HMAC-SHA256(nonce + timestamp + path) as a Base64 NO_WRAP string.
     *
     * The key is loaded from AndroidKeyStore, then the HMAC is computed by the
     * default JCA provider — NOT by specifying AndroidKeyStore as the Mac provider.
     *
     * ⚠️ IMPORTANT (NEW CLARIFICATION):
     * This signature CANNOT be verified by the backend because the key is private to AndroidKeyStore.
     * It is still useful for purely local integrity checks, but NOT for server-side verification.
     *
     * For server-verifiable request signing, use the overload that takes deviceSecretBase64:
     *   signRequest(nonce, timestamp, path, deviceSecretBase64)
     */
    fun signRequest(nonce: String, timestamp: String, path: String): String {
        val key = loadKey()
        // Use default provider — AndroidKeyStore doesn't support Mac.getInstance("HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)

        // NOTE: Keep your existing payload format and comments intact.
        // Your backend expects "nonce:timestamp:path" (with colons). If you need server verification,
        // call the overload below which matches backend message format.
        val payload = "$nonce:$timestamp:$path"
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /**
     * NEW: Server-verifiable request signature.
     *
     * This uses the shared per-device secret (X-Device-Secret), which the backend stores/knows.
     * Backend can recompute HMAC using the same secret and validate signature.
     *
     * Backend message format: "nonce:timestamp:path"
     *
     * @param deviceSecretBase64 Base64 NO_WRAP secret sent as X-Device-Secret
     */
    fun signRequest(nonce: String, timestamp: String, path: String, deviceSecretBase64: String): String {
        val secretBytes = try {
            Base64.decode(deviceSecretBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw IllegalStateException("Invalid device secret Base64", e)
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))

        val payload = "$nonce:$timestamp:$path"
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        keyGen.generateKey()
    }

    /**
     * Loads the SecretKey from AndroidKeyStore.
     *
     * Crucially, we do NOT call Mac.getInstance(..., "AndroidKeyStore").
     * We just retrieve the key object and pass it to the standard Mac impl.
     */
    private fun loadKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}