package com.example.albanianidverification

import android.app.Application
import com.example.albanianidverification.security.DeviceManager
import com.example.albanianidverification.security.NonceManager
import com.example.albanianidverification.security.TokenManager

/**
 * Application class — runs once when the app process starts.
 *
 * Initialisation order matters:
 *  1. TokenManager   — ApiClient's auth interceptor reads it immediately
 *  2. NonceManager   — ApiClient's nonce interceptor reads it immediately
 *  3. DeviceManager  — ApiClient's device interceptor reads it immediately
 */
class VotingApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Encrypted JWT storage (must be first — auth interceptor uses it)
        TokenManager.initialise(this)

        // 2. HMAC signing key in Android Keystore (nonce/replay prevention)
        NonceManager.initialise(this)

        // 3. Device ID + device secret (device binding after first auth)
        DeviceManager.initialize(this)

        // 4. HTTP client / Retrofit are lazy vals in ApiClient — no explicit init needed.
        //    They self-configure on first use with all three managers above already ready.
    }
}