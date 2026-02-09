package com.example.albanianidverification.utils

import com.example.albanianidverification.models.MRZData

object MRZParser {
    
    /**
     * Extract MRZ data from OCR text
     * Supports TD1 format (ID cards - 3 lines, 30 chars each)
     */
    fun extractMRZ(text: String): MRZData? {
        // Split into lines and clean
        val lines = text.split("\n")
            .map { it.trim().replace(" ", "").replace("O", "0") }
            .filter { it.isNotEmpty() }
        
        // Try to find MRZ pattern (TD1 - 3 lines of ~30 chars)
        val mrzLines = findMRZLines(lines)
        
        if (mrzLines.size >= 2) {
            return parseTD1(mrzLines)
        }
        
        // Fallback: try to extract document number pattern
        return extractFromPattern(text)
    }
    
    /**
     * Find consecutive lines that look like MRZ
     */
    private fun findMRZLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        
        for (i in lines.indices) {
            val line = lines[i]
            
            // MRZ lines are typically 28-30 characters
            if (line.length in 28..32) {
                // Check if it looks like MRZ (alphanumeric with < symbols)
                if (line.any { it == '<' } || line.matches(Regex("[A-Z0-9<]{28,32}"))) {
                    result.add(line)
                    
                    // Try to get next lines
                    if (i + 1 < lines.size && lines[i + 1].length in 28..32) {
                        result.add(lines[i + 1])
                    }
                    if (i + 2 < lines.size && lines[i + 2].length in 28..32) {
                        result.add(lines[i + 2])
                    }
                    
                    if (result.size >= 2) {
                        break
                    }
                }
            }
        }
        
        return result
    }
    
    /**
     * Parse TD1 format MRZ (ID cards)
     * Line 1: Document type (2) + Issuing country (3) + Document number (9) + Check digit (1) + Optional (15)
     * Line 2: Date of birth (6) + Check digit (1) + Sex (1) + Expiry date (6) + Check digit (1) + Nationality (3) + Optional (11) + Check digit (1)
     * Line 3: Name
     */
    private fun parseTD1(lines: List<String>): MRZData? {
        try {
            val line1 = lines.getOrNull(0)?.padEnd(30, '<') ?: return null
            val line2 = lines.getOrNull(1)?.padEnd(30, '<') ?: return null
            
            // Line 1: Extract document info
            val documentType = line1.substring(0, 2).replace("<", "").ifEmpty { "ID" }
            val issuingCountry = line1.substring(2, 5).replace("<", "").ifEmpty { "ALB" }
            val documentNumber = extractField(line1.substring(5, 14))
            
            // Line 2: Extract dates and nationality
            val dateOfBirth = line2.substring(0, 6)
            val gender = line2.substring(7, 8).replace("<", "")
            val expiryDate = line2.substring(8, 14)
            val nationality = line2.substring(15, 18).replace("<", "").ifEmpty { "ALB" }
            
            // Line 3: Extract name (if available)
            val nameLine = lines.getOrNull(2)?.replace("<", " ")?.trim()
            val nameParts = nameLine?.split("  ")?.filter { it.isNotBlank() }
            val surname = nameParts?.getOrNull(0)
            val givenNames = nameParts?.drop(1)?.joinToString(" ")
            
            // Validate critical fields
            if (!isValidDocumentNumber(documentNumber)) {
                return null
            }
            
            if (!isValidDate(dateOfBirth) || !isValidDate(expiryDate)) {
                return null
            }
            
            return MRZData(
                documentNumber = documentNumber,
                dateOfBirth = dateOfBirth,
                expiryDate = expiryDate,
                nationality = nationality,
                documentType = documentType,
                issuingCountry = issuingCountry,
                surname = surname,
                givenNames = givenNames,
                gender = gender.ifEmpty { null }
            )
            
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Fallback method to extract from pattern matching
     */
    private fun extractFromPattern(text: String): MRZData? {
        // Look for Albanian document number pattern (letters + numbers)
        val docPattern = Regex("[A-Z]{1,2}[0-9]{7,9}")
        val docMatch = docPattern.find(text)
        
        // Look for date patterns (YYMMDD)
        val datePattern = Regex("[0-9]{6}")
        val dates = datePattern.findAll(text).map { it.value }.toList()
        
        if (docMatch != null && dates.size >= 2) {
            val documentNumber = docMatch.value
            val dateOfBirth = dates[0]
            val expiryDate = dates[1]
            
            if (isValidDate(dateOfBirth) && isValidDate(expiryDate)) {
                return MRZData(
                    documentNumber = documentNumber,
                    dateOfBirth = dateOfBirth,
                    expiryDate = expiryDate,
                    nationality = "ALB",
                    issuingCountry = "ALB"
                )
            }
        }
        
        return null
    }
    
    /**
     * Extract field and remove filler characters
     */
    private fun extractField(field: String): String {
        return field.replace("<", "").trim()
    }
    
    /**
     * Validate document number
     */
    private fun isValidDocumentNumber(number: String): Boolean {
        return number.length in 6..12 && number.isNotEmpty()
    }
    
    /**
     * Validate date format (YYMMDD)
     */
    private fun isValidDate(date: String): Boolean {
        if (date.length != 6 || !date.all { it.isDigit() }) {
            return false
        }
        
        val year = date.substring(0, 2).toIntOrNull() ?: return false
        val month = date.substring(2, 4).toIntOrNull() ?: return false
        val day = date.substring(4, 6).toIntOrNull() ?: return false
        
        return month in 1..12 && day in 1..31
    }
    
    /**
     * Calculate MRZ check digit
     */
    private fun calculateCheckDigit(input: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        
        input.forEachIndexed { index, char ->
            val value = when {
                char.isDigit() -> char.toString().toInt()
                char.isLetter() -> char.code - 'A'.code + 10
                char == '<' -> 0
                else -> 0
            }
            sum += value * weights[index % 3]
        }
        
        return sum % 10
    }
}
