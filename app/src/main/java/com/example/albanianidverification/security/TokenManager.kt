package com.example.albanianidverification.security


import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ══════════════════════════════════════════════════════════════
 *  TokenManager — Secure JWT Storage
 *
 *  Stores access and refresh tokens in EncryptedSharedPreferences backed by
 *  an AES-256-GCM key in Android Keystore. The tokens are encrypted at rest
 *  and inaccessible to other apps.
 *
 *  Usage:
 *    TokenManager.initialise(applicationContext)   // once in Application.onCreate
 *    TokenManager.saveTokens(accessToken, refreshToken)
 *    val token = TokenManager.getAccessToken()
 *    TokenManager.clearTokens()   // on logout or 401
 * ══════════════════════════════════════════════════════════════
 */
object TokenManager {

    private const val PREFS_FILE     = "evoting_secure_tokens"
    private const val KEY_ACCESS     = "access_token"
    private const val KEY_REFRESH    = "refresh_token"
    private const val KEY_EXPIRES_AT = "access_token_expires_at_ms"
    private const val KEY_BOUND_DEVICE_ID = "bound_device_id"

    private var prefs: androidx.security.crypto.EncryptedSharedPreferences? = null

    fun initialise(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(false)   // set true on devices with StrongBox HAL
            .build()

        @Suppress("UNCHECKED_CAST")
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as androidx.security.crypto.EncryptedSharedPreferences
    }
    /**
     * Save tokens after successful authentication.
     * Enforces: accessToken.deviceId must equal DeviceManager.getDeviceId()
     */
    fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long = 300) {
        val expectedDeviceId = DeviceManager.getDeviceId()
        val tokenDeviceId = JwtUtils.getDeviceId(accessToken)

        if (tokenDeviceId.isNullOrBlank()) {
            clearTokens()
            throw IllegalStateException("Server token missing deviceId claim")
        }

        if (tokenDeviceId != expectedDeviceId) {
            clearTokens()
            throw IllegalStateException("Token is not meant for this device")
        }

        requirePrefs().edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            .putString(KEY_BOUND_DEVICE_ID, expectedDeviceId)
            .apply()
    }
    /**
     * Returns the access token if not expired AND still bound to this device.
     */
    fun getAccessToken(): String? {
        val token = requirePrefs().getString(KEY_ACCESS, null) ?: return null
        val expires = requirePrefs().getLong(KEY_EXPIRES_AT, 0L)

        // device binding check every time
        val boundDeviceId = requirePrefs().getString(KEY_BOUND_DEVICE_ID, null)
        val currentDeviceId = DeviceManager.getDeviceId()
        if (boundDeviceId != null && boundDeviceId != currentDeviceId) {
            clearTokens()
            return null
        }

        // Reject 30 seconds before actual expiry to account for clock skew
        return if (System.currentTimeMillis() < expires - 30_000L) token else null
    }

    fun getRefreshToken(): String? =
        requirePrefs().getString(KEY_REFRESH, null)

    /** Returns true if the access token is present and not yet expired. */
    fun isAuthenticated(): Boolean = getAccessToken() != null

    /** Call on logout or when receiving a 401 to clear all stored tokens. */
    fun clearTokens() {
        requirePrefs().edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_BOUND_DEVICE_ID)
            .apply()
    }

    private fun requirePrefs(): android.content.SharedPreferences =
        checkNotNull(prefs) {
            "TokenManager not initialised. Call TokenManager.initialise(context) in Application.onCreate()"
        }
}