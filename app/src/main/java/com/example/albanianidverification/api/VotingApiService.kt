package com.example.albanianidverification.api

import com.example.albanianidverification.api.models.CandidateResponse
import com.example.albanianidverification.api.models.ElectionResponse
import com.example.albanianidverification.api.models.PartyResponse
import com.example.albanianidverification.api.models.VoteRequest
import com.example.albanianidverification.api.models.VoteResponse
import com.example.albanianidverification.api.models.VoteStatusResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

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

    // ── Elections ─────────────────────────────────────────────────────────────
    /** GET /api/v1/elections/active — no auth required */
    @GET("api/v1/elections/active")
    suspend fun getActiveElections(): Response<List<ElectionResponse>>

    /** GET /api/v1/elections/{electionId} */
    @GET("api/v1/elections/{electionId}")
    suspend fun getElection(@Path("electionId") id: String): Response<ElectionResponse>

    /** GET /api/v1/elections/{electionId}/parties — returns parties with candidates embedded */
    @GET("api/v1/elections/{electionId}/parties")
    suspend fun getParties(@Path("electionId") id: String): Response<List<PartyResponse>>

    /**
     * GET /api/v1/vote/candidates/{electionId}
     * Candidates filtered to the JWT voter's county / municipality.
     * Used to know which candidates the voter is eligible to vote for.
     */
    @GET("api/v1/vote/candidates/{electionId}")
    suspend fun getCandidatesForVoter(@Path("electionId") id: String): Response<List<CandidateResponse>>

    // ── Voting ────────────────────────────────────────────────────────────────
    /** GET /api/v1/vote/status/{electionId} */
    @GET("api/v1/vote/status/{electionId}")
    suspend fun getVoteStatus(@Path("electionId") id: String): Response<VoteStatusResponse>

    /** POST /api/v1/vote */
    @POST("api/v1/vote")
    suspend fun castVote(@Body request: VoteRequest): Response<VoteResponse>
}