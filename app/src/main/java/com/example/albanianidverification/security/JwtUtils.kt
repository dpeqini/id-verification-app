package com.example.albanianidverification.security

import android.util.Base64
import org.json.JSONObject

object JwtUtils {

    /**
     * Decodes JWT payload without verifying signature (client-side check only).
     * Backend is still the source of truth.
     */
    fun getClaim(token: String, claimName: String): String? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null

            val payloadB64Url = parts[1]
            val payloadJsonBytes = Base64.decode(payloadB64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payloadJson = String(payloadJsonBytes, Charsets.UTF_8)

            val obj = JSONObject(payloadJson)
            if (!obj.has(claimName)) null else obj.optString(claimName, null)
        } catch (_: Exception) {
            null
        }
    }

    fun getDeviceId(token: String): String? = getClaim(token, "deviceId")
}