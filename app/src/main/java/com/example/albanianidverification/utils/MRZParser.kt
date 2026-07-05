package com.example.albanianidverification.utils

import com.example.albanianidverification.models.MRZData

object MRZParser {
    
    /**
     * Extract MRZ data from OCR text.
     *
     * Supports both:
     *   - TD1 (Albanian ID card)  — 3 lines of 30 chars
     *   - TD3 (Albanian passport) — 2 lines of 44 chars
     *
     * Only three fields are needed for BAC (document number, date of birth,
     * expiry date). On TD3 all three live on line 2; on TD1 the number is on
     * line 1 and the dates on line 2.
     */
    fun extractMRZ(text: String): MRZData? {
        // Split into lines and clean
        val lines = text.split("\n")
            .map { it.trim().replace(" ", "").replace("O", "0") }
            .filter { it.isNotEmpty() }

        // TD3 (passport): anchor on line 2 (document number + dates all live there).
        // Line 1 is optional because OCR most often mangles the long '<<<<' filler
        // run on the name line — and the authoritative name comes from the chip anyway.
        val td3Line2 = lines.firstOrNull { it.length in 40..48 && looksLikeTd3Line2(it) }
        if (td3Line2 != null) {
            val td3Line1 = lines.firstOrNull { it.length in 40..48 && it.startsWith("P") }
            parseTD3(td3Line1, td3Line2)?.let { return it }
        }

        // TD1 (ID card) — 3 lines of ~30 chars
        val td1 = findLinesOfWidth(lines, 28..32, want = 2)
        if (td1.size >= 2) parseTD1(td1)?.let { return it }

        // Fallback: try to extract document number pattern
        return extractFromPattern(text)
    }
    
    /**
     * Find a block of consecutive lines whose length falls in [widths] and that
     * look like a machine-readable zone. Used to locate either the TD3 block
     * (2 lines of ~44) or the TD1 block (3 lines of ~30).
     */
    private fun findLinesOfWidth(lines: List<String>, widths: IntRange, want: Int): List<String> {
        for (i in lines.indices) {
            if (lines[i].length in widths && looksLikeMrz(lines[i])) {
                val block = mutableListOf<String>()
                var j = i
                while (j < lines.size && lines[j].length in widths &&
                       looksLikeMrz(lines[j]) && block.size < 3) {
                    block.add(lines[j])
                    j++
                }
                if (block.size >= want) return block
            }
        }
        return emptyList()
    }

    /** A MRZ line is uppercase alphanumeric plus the '<' filler. */
    private fun looksLikeMrz(line: String): Boolean =
        line.any { it == '<' } || line.matches(Regex("[A-Z0-9<]+"))
    
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
            val dateOfBirth = toDigits(line2.substring(0, 6))
            val gender = line2.substring(7, 8).replace("<", "")
            val expiryDate = toDigits(line2.substring(8, 14))
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
     * A line looks like TD3 line 2 when its date fields (offsets 13 and 21) are
     * numeric after OCR digit-normalisation. This lets us anchor on the data line
     * even when the name line (line 1) is mangled, and avoids mistaking the name
     * line (letters at those offsets) for the data line.
     */
    private fun looksLikeTd3Line2(line: String): Boolean {
        val p = line.padEnd(44, '<')
        val dob = toDigits(p.substring(13, 19))
        val exp = toDigits(p.substring(21, 27))
        return dob.all { it.isDigit() } && exp.all { it.isDigit() }
    }

    /**
     * Map the most common OCR letter-for-digit confusions back to digits.
     * Applied only to fields that MUST be numeric (dates), never to the whole line.
     */
    private fun toDigits(s: String): String = s.map { c ->
        when (c) {
            'O', 'Q', 'D' -> '0'
            'I', 'L' -> '1'
            'Z' -> '2'
            'S' -> '5'
            'B' -> '8'
            'G' -> '6'
            'T' -> '7'
            else -> c
        }
    }.joinToString("")

    /**
     * Parse TD3 format MRZ (passports — 2 lines of 44 chars).
     *
     * Only line 2 is required: it carries the document number, date of birth and
     * expiry — the three fields BAC needs. Line 1 (type/country/name) is optional
     * because its long '<<<<' filler run is what OCR misreads most; the
     * authoritative name is read from the chip (DG1) anyway.
     */
    private fun parseTD3(line1Raw: String?, line2Raw: String): MRZData? {
        try {
            val line2 = line2Raw.padEnd(44, '<')

            // Line 2: document number, dates, nationality, personal number
            val documentNumber = extractField(line2.substring(0, 9))
            val nationality    = line2.substring(10, 13).replace("<", "").ifEmpty { "ALB" }
            val dateOfBirth    = toDigits(line2.substring(13, 19))
            val gender         = line2.substring(20, 21).replace("<", "")
            val expiryDate     = toDigits(line2.substring(21, 27))
            // For Albanian passports the national ID lives in the personal-number field.
            val personalNumber = extractField(line2.substring(28, 42))

            // Line 1 (optional): document type, issuing country, name
            val line1          = line1Raw?.padEnd(44, '<')
            val documentType   = line1?.substring(0, 1)?.replace("<", "")?.ifEmpty { "P" } ?: "P"
            val issuingCountry = line1?.substring(2, 5)?.replace("<", "")?.ifEmpty { "ALB" } ?: "ALB"
            val nameField      = line1?.substring(5)?.replace("<", " ")?.trim()
            val nameParts      = nameField?.split("  ")?.filter { it.isNotBlank() }
            val surname        = nameParts?.getOrNull(0)
            val givenNames     = nameParts?.drop(1)?.joinToString(" ")?.ifBlank { null }

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
                gender = gender.ifEmpty { null },
                optionalData = personalNumber.ifBlank { null }
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
