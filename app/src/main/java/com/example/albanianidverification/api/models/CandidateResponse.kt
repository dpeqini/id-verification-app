package com.example.albanianidverification.api.models

import com.google.gson.annotations.SerializedName

data class CandidateResponse(
    @SerializedName("id")               val id: String = "",
    @SerializedName("firstName")        val firstName: String? = null,
    @SerializedName("lastName")         val lastName: String? = null,
    @SerializedName("fullName")         val fullName: String? = null,
    @SerializedName("biography")        val biography: String? = null,
    @SerializedName("photoUrl")         val photoUrl: String? = null,
    @SerializedName("partyId")          val partyId: String? = null,
    @SerializedName("partyName")        val partyName: String? = null,
    @SerializedName("partyCode")        val partyCode: String? = null,
    @SerializedName("county")           val county: String? = null,
    @SerializedName("countyName")       val countyName: String? = null,
    @SerializedName("municipality")     val municipality: String? = null,
    @SerializedName("municipalityName") val municipalityName: String? = null,
    @SerializedName("positionInList")   val positionInList: Int? = null,
    @SerializedName("independent")      val independent: Boolean = false,
    @SerializedName("profession")       val profession: String? = null,
    @SerializedName("age")              val age: Int? = null,
    @SerializedName("education")        val education: String? = null,
    @SerializedName("platform")         val platform: String? = null
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(firstName, lastName).joinToString(" ").takeIf { it.isNotBlank() }
            ?: "Unknown"
}