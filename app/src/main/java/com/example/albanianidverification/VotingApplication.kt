package com.example.albanianidverification
import android.app.Application
import com.example.albanianidverification.security.NonceManager
import com.example.albanianidverification.security.TokenManager

/**
 * Application class — runs once when the app process starts,
 * before any Activity, Service, or BroadcastReceiver.
 *
 * IMPORTANT: After creating this file you must register it in AndroidManifest.xml:
 *
 *   <application
 *       android:name=".VotingApplication"   ← ADD THIS LINE
 *       ... >
 *
 * Without that line, Android ignores this class entirely.
 */
class VotingApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ── 1. Encrypted token storage ───────────────────────────────────────
        // Must be first — ApiClient's auth interceptor calls TokenManager.getAccessToken()
        TokenManager.initialise(this)

        // ── 2. HMAC signing key in Android Keystore ──────────────────────────
        // Generates the key on first launch, reuses it on every subsequent launch.
        // The key never leaves the device hardware.
        NonceManager.initialise(this)

        // ── 3. HTTP client is a lazy val in ApiClient — no explicit init needed
        // It self-configures on first use. Nothing to call here.
        // ApiClient.retrofit is ready to use from any Activity or ViewModel.
    }
}