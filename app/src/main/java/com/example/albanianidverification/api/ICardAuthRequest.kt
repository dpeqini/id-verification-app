package com.example.albanianidverification.api

import com.google.gson.annotations.SerializedName
import java.time.LocalDate

/**
 * Request body for POST /api/v1/auth/id-card
 *
 * Matches danjel.votingbackend.dto.IdCardAuthRequest on the Spring Boot backend.
 */
data class IdCardAuthRequest(
    /** National ID / document number from DG1 */
    @SerializedName("nationalId")
    val nationalId: String,

    /** Full name as read from the chip */
    @SerializedName("name")
    val name: String,

    /** Date of birth in YYMMDD format */
    @SerializedName("dateOfBirth")
    val dateOfBirth: LocalDate,

    /** Card expiry date in YYMMDD format */
    @SerializedName("expiryDate")
    val expiryDate: LocalDate,

    /** Optional — county from chip (DG11 if present) */
    @SerializedName("county")
    val county: String? = null,

    /** Optional — municipality from chip (DG11 if present) */
    @SerializedName("municipality")
    val municipality: String? = null,

    /**
     * Base64-encoded (NO_WRAP) JPEG of the chip face photo extracted from DG2.
     * The backend forwards this to the Python DeepFace server.
     * The image is never stored on either server.
     */
    @SerializedName("chipFacePhoto")
    val chipFacePhoto: String,

    /**
     * Base64-encoded (NO_WRAP) JPEG of the live selfie captured after ML Kit
     * liveness detection passed.
     */
    @SerializedName("liveSelfie")
    val liveSelfie: String,

    /**
     * Must be true — confirms that ML Kit liveness check passed on the device
     * before this request was constructed. The backend rejects requests where
     * this is false.
     */
    @SerializedName("livenessConfirmed")
    val livenessConfirmed: Boolean = true
)