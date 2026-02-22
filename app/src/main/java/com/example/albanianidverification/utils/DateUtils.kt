package com.example.albanianidverification.utils

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField


object DateUtils {
    /**
     * Converts YYMMDD string to LocalDate.
     * For birthdates, we assume a year in the past.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun parseMrzDate(dateStr: String, isBirthDate: Boolean): LocalDate {
        require(!(dateStr == null || dateStr.length != 6)) { "Invalid date format. Expected YYMMDD." }

        // Create a formatter that handles 2-digit years
        val formatter = DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 2, if (isBirthDate) 1930 else 2020)
            .appendPattern("MMdd")
            .toFormatter()

        return LocalDate.parse(dateStr, formatter)
    }
}