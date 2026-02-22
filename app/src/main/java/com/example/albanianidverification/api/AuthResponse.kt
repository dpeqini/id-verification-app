package com.example.albanianidverification.api

import com.google.gson.annotations.SerializedName

/**
 * Response body from POST /api/v1/auth/id-card (and /auth/refresh).
 *
 * Matches danjel.votingbackend.dto.AuthResponse on the Spring Boot backend.
 *
 * HTTP 200 — authentication successful:
 *   success = true, accessToken & refreshToken & voterId are populated
 *
 * HTTP 400/401/429/503 — failure:
 *   success = false, message describes the reason
 */
data class AuthResponse(
    @SerializedName("success")
    val success: Boolean = false,

    @SerializedName("message")
    val message: String? = null,

    /** UUID of the voter record — only present on success */
    @SerializedName("voterId")
    val voterId: String? = null,

    /** Short-lived JWT (30 min) — only present on success */
    @SerializedName("accessToken")
    val accessToken: String? = null,

    /** Long-lived refresh token (7 days) — only present on success */
    @SerializedName("refreshToken")
    val refreshToken: String? = null,

    /** Seconds until the access token expires — only present on success */
    @SerializedName("expiresIn")
    val expiresIn: Long? = null,

    /** Albanian county the voter is registered in */
    val county: String?,

    /** Albanian municipality the voter is registered in */
    val municipality: String?
)