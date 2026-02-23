package com.example.albanianidverification.api.models

import com.google.gson.annotations.SerializedName

/**
 * POST /api/v1/vote
 * Required fields per swagger: electionId, encryptedVoteData, digitalSignature
 */
data class VoteRequest(
    @SerializedName("electionId")          val electionId: String,
    @SerializedName("candidateId")         val candidateId: String? = null,
    @SerializedName("partyId")             val partyId: String? = null,
    @SerializedName("encryptedVoteData")   val encryptedVoteData: String,
    @SerializedName("digitalSignature")    val digitalSignature: String,
    @SerializedName("nonce")               val nonce: String? = null,
    @SerializedName("faceVerificationToken") val faceVerificationToken: String? = null,
    @SerializedName("votingStationId")     val votingStationId: String? = null
)

/** Response from POST /api/v1/vote */
data class VoteResponse(
    @SerializedName("success")                 val success: Boolean = false,
    @SerializedName("message")                 val message: String? = null,
    @SerializedName("voteId")                  val voteId: String? = null,
    @SerializedName("voteHash")                val voteHash: String? = null,
    @SerializedName("receiptToken")            val receiptToken: String? = null,
    @SerializedName("verificationCode")        val verificationCode: String? = null,
    @SerializedName("timestamp")               val timestamp: String? = null,
    @SerializedName("blockchainTransactionId") val blockchainTransactionId: String? = null,
    @SerializedName("blockNumber")             val blockNumber: Long? = null,
    @SerializedName("candidateName")           val candidateName: String? = null,
    @SerializedName("partyName")               val partyName: String? = null,
    @SerializedName("electionName")            val electionName: String? = null,
    @SerializedName("county")                  val county: String? = null,
    @SerializedName("municipality")            val municipality: String? = null
)

/** Response from GET /api/v1/vote/status/{electionId} */
data class VoteStatusResponse(
    @SerializedName("electionId") val electionId: String? = null,
    @SerializedName("voterId")    val voterId: String? = null,
    @SerializedName("hasVoted")   val hasVoted: Boolean = false
)