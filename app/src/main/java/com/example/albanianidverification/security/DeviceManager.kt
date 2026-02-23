package com.example.albanianidverification.security

import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

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

    private const val PREFS_FILE = "evoting_device_identity"

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_SECRET = "device_secret_b64"
    private const val KEY_INSTALL_FALLBACK_ID = "install_fallback_uuid"

    // Change this when you publish a new app family (keep stable per app)
    private const val APP_SALT = "EVOTING_ALBANIA_APP_SALT_V1"

    private var prefs: EncryptedSharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return

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

        // Ensure secret exists
        if (requirePrefs().getString(KEY_DEVICE_SECRET, null) == null) {
            generateAndSaveSecret()
        }

        // Ensure deviceId exists (stable)
        if (requirePrefs().getString(KEY_DEVICE_ID, null) == null) {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            val stableSource = when {
                !androidId.isNullOrBlank() -> androidId
                else -> getOrCreateInstallFallbackId()
            }

            val hashedDeviceId = sha256Hex("$stableSource|$APP_SALT")
            requirePrefs().edit().putString(KEY_DEVICE_ID, hashedDeviceId).apply()
        }
    }

    /** Stable device identifier sent as X-Device-ID header. */
    fun getDeviceId(): String =
        requirePrefs().getString(KEY_DEVICE_ID, "unknown") ?: "unknown"

    /**
     * 32-byte device secret encoded as Base64 (NO_WRAP).
     * Sent as X-Device-Secret on every request.
     */
    fun getDeviceSecretBase64(): String =
        requirePrefs().getString(KEY_DEVICE_SECRET, "") ?: ""

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun getOrCreateInstallFallbackId(): String {
        val existing = requirePrefs().getString(KEY_INSTALL_FALLBACK_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val created = UUID.randomUUID().toString()
        requirePrefs().edit().putString(KEY_INSTALL_FALLBACK_ID, created).apply()
        return created
    }

    private fun generateAndSaveSecret() {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        val encoded = Base64.encodeToString(secret, Base64.NO_WRAP)
        requirePrefs().edit().putString(KEY_DEVICE_SECRET, encoded).apply()
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun requirePrefs(): EncryptedSharedPreferences =
        checkNotNull(prefs) {
            "DeviceManager not initialised — call DeviceManager.initialize(context) in Application.onCreate()"
        }
}