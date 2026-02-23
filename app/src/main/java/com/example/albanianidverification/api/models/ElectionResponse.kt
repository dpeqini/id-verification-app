package com.example.albanianidverification.api.models

import com.google.gson.annotations.SerializedName

data class ElectionResponse(
    @SerializedName("id")                        val id: String = "",
    @SerializedName("name")                      val name: String = "",
    @SerializedName("description")               val description: String? = null,
    @SerializedName("electionType")              val electionType: String = "", // PARLIAMENTARY | LOCAL_GOVERNMENT
    @SerializedName("status")                    val status: String = "",       // CREATED|CANDIDATES_IMPORTED|STARTED|CLOSED|RESULTS_PUBLISHED
    @SerializedName("electionDate")              val electionDate: String? = null,
    @SerializedName("startDate")                 val startDate: String? = null,
    @SerializedName("endDate")                   val endDate: String? = null,
    @SerializedName("registrationDeadline")      val registrationDeadline: String? = null,
    @SerializedName("totalEligibleVoters")       val totalEligibleVoters: Long? = null,
    @SerializedName("totalVotesCast")            val totalVotesCast: Long? = null,
    @SerializedName("turnoutPercentage")         val turnoutPercentage: Double? = null,
    @SerializedName("candidatesImported")        val candidatesImported: Boolean = false,
    @SerializedName("candidateCount")            val candidateCount: Int? = null,
    @SerializedName("partyCount")                val partyCount: Int? = null,
    @SerializedName("blockchainContractAddress") val blockchainContractAddress: String? = null,
    @SerializedName("createdAt")                 val createdAt: String? = null,
    @SerializedName("lastSyncedAt")              val lastSyncedAt: String? = null,
    @SerializedName("candidates")                val candidates: List<CandidateResponse> = emptyList(),
    @SerializedName("parties")                   val parties: List<PartyResponse> = emptyList()
)