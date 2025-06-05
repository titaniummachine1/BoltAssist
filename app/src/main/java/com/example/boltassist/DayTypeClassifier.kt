package com.example.boltassist

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.*

object DayTypeClassifier {
    private val fixedHolidays = setOf(
        "01-01","01-06","05-01","05-03","08-15","11-01","11-11","12-25","12-26"
    )

    fun classify(cal: Calendar): String {
        val date = LocalDate.of(cal.get(Calendar.YEAR),
                              cal.get(Calendar.MONTH) + 1,
                              cal.get(Calendar.DAY_OF_MONTH))
        val dow = date.dayOfWeek
        val mmdd = "%02d-%02d".format(date.monthValue, date.dayOfMonth)

        return when {
            mmdd in fixedHolidays            -> "HOL"
            dow == DayOfWeek.SATURDAY        -> "WE"
            dow == DayOfWeek.SUNDAY          -> "WE"
            // Bridge day rules (Thursday after a Wed holiday etc.)
            fixedHolidays.contains(date.minusDays(1).formatMMDD()) && dow == DayOfWeek.FRIDAY ||
            fixedHolidays.contains(date.plusDays(1).formatMMDD()) && dow == DayOfWeek.MONDAY 
                -> "BRH"
            fixedHolidays.contains(date.minusDays(1).formatMMDD()) && dow in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
                -> "AFT"
            else -> "WD"
        }
    }

    private fun LocalDate.formatMMDD(): String =
        "%02d-%02d".format(this.monthValue, this.dayOfMonth)
} 