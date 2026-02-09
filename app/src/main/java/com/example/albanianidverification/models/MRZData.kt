package com.example.albanianidverification.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MRZData(
    val documentNumber: String,
    val dateOfBirth: String,      // Format: YYMMDD
    val expiryDate: String,        // Format: YYMMDD
    val nationality: String,
    val documentType: String = "ID",
    val issuingCountry: String = "ALB",
    val surname: String? = null,
    val givenNames: String? = null,
    val gender: String? = null,
    val optionalData: String? = null
) : Parcelable {
    
    /**
     * Returns the MRZ in TD1 format (3 lines, 30 characters each)
     * Used for ID cards
     */
    fun toTD1Format(): String {
        // This is a simplified version - actual MRZ includes check digits
        val line1 = String.format("%-30s", "$documentType$issuingCountry$documentNumber")
        val line2 = String.format("%-30s", "$dateOfBirth${gender ?: "F"}$expiryDate$nationality")
        val line3 = String.format("%-30s", "${surname ?: ""}<<${givenNames ?: ""}")
        return "$line1\n$line2\n$line3"
    }
    
    companion object {
        /**
         * Validates MRZ date format
         */
        fun isValidDate(date: String): Boolean {
            if (date.length != 6) return false
            return date.all { it.isDigit() }
        }
        
        /**
         * Validates document number format
         */
        fun isValidDocumentNumber(number: String): Boolean {
            return number.length in 6..12 && number.all { it.isLetterOrDigit() }
        }
    }
}
