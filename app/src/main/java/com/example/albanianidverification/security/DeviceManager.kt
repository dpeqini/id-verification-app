package com.example.albanianidverification.security

import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * ══════════════════════════════════════════════════════════════
 *  DeviceManager — Device Identity & Secret Storage
 *
 *  Provides two values that are sent as HTTP headers on every request:
 *    X-Device-ID     : stable identifier derived from ANDROID_ID
 *    X-Device-Secret : 32 random bytes generated once and persisted in
 *                      EncryptedSharedPreferences (AES-256-GCM key in Keystore)
 *
 *  The backend's DeviceSecretRegistry stores the secret after the first
 *  successful authentication and uses it to bind future requests to this
 *  specific device — preventing token theft from another device.
 *
 *  Call [initialize] once from Application.onCreate before any network call.
 * ══════════════════════════════════════════════════════════════
 */
object DeviceManager {

    private const val PREFS_FILE       = "evoting_device_identity"
    private const val KEY_DEVICE_ID    = "device_id"
    private const val KEY_DEVICE_SECRET = "device_secret_b64"

    private var prefs: EncryptedSharedPreferences? = null

    // ── Public API ───────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (prefs != null) return          // idempotent
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(false)
            .build()

        @Suppress("UNCHECKED_CAST")
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences

        // Ensure the device secret is generated on first launch
        if (requirePrefs().getString(KEY_DEVICE_SECRET, null) == null) {
            generateAndSaveSecret()
        }

        // Persist the stable device ID
        if (requirePrefs().getString(KEY_DEVICE_ID, null) == null) {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown-${System.currentTimeMillis()}"
            requirePrefs().edit().putString(KEY_DEVICE_ID, androidId).apply()
        }
    }

    /** Stable device identifier sent as X-Device-ID header. */
    fun getDeviceId(): String =
        requirePrefs().getString(KEY_DEVICE_ID, "unknown") ?: "unknown"

    /**
     * 32-byte device secret encoded as Base64 (NO_WRAP).
     * Sent as X-Device-Secret on every request.
     * The backend stores this after the first successful auth.
     */
    fun getDeviceSecretBase64(): String =
        requirePrefs().getString(KEY_DEVICE_SECRET, "") ?: ""

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun generateAndSaveSecret() {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        val encoded = Base64.encodeToString(secret, Base64.NO_WRAP)
        requirePrefs().edit().putString(KEY_DEVICE_SECRET, encoded).apply()
    }

    private fun requirePrefs(): EncryptedSharedPreferences =
        checkNotNull(prefs) {
            "DeviceManager not initialised — call DeviceManager.initialize(context) in Application.onCreate()"
        }
}