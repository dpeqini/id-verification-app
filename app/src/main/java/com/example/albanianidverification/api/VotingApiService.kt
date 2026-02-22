package com.example.albanianidverification.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit service interface for the Albanian e-Voting backend.
 *
 * The OkHttp client configured in [com.example.albanianidverification.security.ApiClient]
 * automatically injects all required security headers on every request:
 *   Authorization: Bearer <access_token>   (if a token exists)
 *   X-Request-Nonce, X-Request-Timestamp, X-Request-Signature  (HMAC replay prevention)
 *   X-Device-ID, X-Device-Secret           (device binding)
 */
interface VotingApiService {

    /**
     * Authenticate a voter using their biometric NFC ID card.
     *
     * The Android app must:
     *  1. Read NFC chip → extract identity fields + chip face photo (DG2)
     *  2. Complete ML Kit liveness (blink / smile / head-turn)
     *  3. Capture a live selfie
     *  4. Call this endpoint with all data
     *
     * HTTP 200 → tokens + voterId in [AuthResponse]
     * HTTP 400 → liveness false / card expired / invalid chip data
     * HTTP 401 → face does not match ID photo
     * HTTP 429 → rate limited after too many failures
     * HTTP 503 → DeepFace server unavailable
     */
    @POST("api/v1/auth/id-card")
    suspend fun authenticateWithIdCard(
        @Body request: IdCardAuthRequest
    ): Response<AuthResponse>
}