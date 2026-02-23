package com.example.albanianidverification.api.models

import com.google.gson.annotations.SerializedName

data class PartyResponse(
    @SerializedName("id")             val id: String = "",
    @SerializedName("partyCode")      val partyCode: String? = null,
    @SerializedName("name")           val name: String = "",
    @SerializedName("description")    val description: String? = null,
    @SerializedName("logoUrl")        val logoUrl: String? = null,
    @SerializedName("color")          val color: String? = null,
    @SerializedName("leader")         val leader: String? = null,
    @SerializedName("listNumber")     val listNumber: Int? = null,
    @SerializedName("candidateCount") val candidateCount: Int? = null,
    @SerializedName("candidates")     val candidates: List<CandidateResponse> = emptyList()
)