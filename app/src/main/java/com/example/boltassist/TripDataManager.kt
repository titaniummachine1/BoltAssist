package com.example.boltassist

import android.location.Location
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dedicated module for trip data management and real-time predictions
 * Handles adding trips and sophisticated prediction algorithms
 */
object TripDataManager {
    
    /**
     * Add a trip (either real or edit mode) and immediately update predictions
     */
    fun addTrip(
        location: Location? = null,
        earnings: Int,
        durationMinutes: Int = 5,
        isEditMode: Boolean = false
    ): TripData {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(Date())
        
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, durationMinutes)
        val endTime = dateFormat.format(calendar.time)
        
        val trip = TripData(
            id = "trip_${System.currentTimeMillis()}_${if (isEditMode) "edit" else "real"}",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes,
            earningsPLN = earnings,
            startLocation = location?.let { LocationData(it.latitude, it.longitude) },
            endLocation = location?.let { LocationData(it.latitude, it.longitude) },
            startStreet = if (isEditMode) "Edit Mode" else "Real Trip",
            endStreet = if (isEditMode) "Edit Mode" else "Real Trip"
        )
        
        // Add to cache immediately
        TripManager._tripsCache.add(trip)
        
        // Save to storage
        try {
            TripManager.forceSync()
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save trip", e)
        }
        
        android.util.Log.d("BoltAssist", "Added ${if (isEditMode) "edit" else "real"} trip: $earnings PLN")
        return trip
    }
    
    /**
     * Add trip for specific day/hour (edit mode)
     */
    fun addTripForDayHour(day: Int, hour: Int, earnings: Int = 5): TripData {
        val targetDate = getDateForDayHour(day, hour)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(targetDate)
        
        val calendar = Calendar.getInstance()
        calendar.time = targetDate
        calendar.add(Calendar.MINUTE, 5)
        val endTime = dateFormat.format(calendar.time)
        
        val trip = TripData(
            id = "edit_${System.currentTimeMillis()}_d${day}_h${hour}",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = 5,
            earningsPLN = earnings,
            startLocation = LocationData(52.2297, 21.0122),
            endLocation = LocationData(52.2297, 21.0122),
            startStreet = "Edit Mode",
            endStreet = "Edit Mode"
        )
        
        TripManager._tripsCache.add(trip)
        
        try {
            TripManager.forceSync()
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save edit trip", e)
        }
        
        android.util.Log.d("BoltAssist", "Added edit trip for day=$day hour=$hour: $earnings PLN")
        return trip
    }
    
    /**
     * Clear all trips for specific day/hour
     */
    fun clearTripsForDayHour(day: Int, hour: Int) {
        val targetDate = getDateForDayHour(day, hour)
        val calendar = Calendar.getInstance()
        calendar.time = targetDate
        val targetHour = calendar.get(Calendar.HOUR_OF_DAY)
        val targetDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6; else -> 0
        }
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val tripsToRemove = TripManager._tripsCache.filter { trip ->
            try {
                val tripDate = dateFormat.parse(trip.startTime)
                if (tripDate != null) {
                    calendar.time = tripDate
                    val tripDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
                        Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
                        Calendar.SUNDAY -> 6; else -> 0
                    }
                    val tripHour = calendar.get(Calendar.HOUR_OF_DAY)
                    tripDay == targetDay && tripHour == targetHour
                } else false
            } catch (e: Exception) { false }
        }
        
        TripManager._tripsCache.removeAll(tripsToRemove)
        
        try {
            TripManager.forceSync()
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save after clearing", e)
        }
        
        android.util.Log.d("BoltAssist", "Cleared ${tripsToRemove.size} trips for day=$day hour=$hour")
    }
    
    /**
     * Advanced prediction algorithm with 7-day fallback
     * Uses same day-of-week first, then falls back to nearby days if insufficient data
     */
    fun getAdvancedPredictionGrid(): Array<DoubleArray> {
        val grid = Array(7) { DoubleArray(24) { 0.0 } }
        val trips = TripManager._tripsCache.filter { it.endTime != null && it.earningsPLN > 0 }
        
        if (trips.isEmpty()) return grid
        
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        // Build earnings map: [day][hour] -> list of earnings
        val earningsMap = Array(7) { Array(24) { mutableListOf<Double>() } }
        
        trips.forEach { trip ->
            try {
                val tripDate = dateFormat.parse(trip.startTime) ?: return@forEach
                calendar.time = tripDate
                
                val tripDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
                    Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
                    Calendar.SUNDAY -> 6; else -> return@forEach
                }
                val tripHour = calendar.get(Calendar.HOUR_OF_DAY)
                val tripHourIndex = (tripHour - 1 + 24) % 24
                
                earningsMap[tripDay][tripHourIndex].add(trip.earningsPLN.toDouble())
            } catch (e: Exception) { 
                // Skip invalid trips
            }
        }
        
        // Generate predictions with fallback logic
        for (day in 0..6) {
            for (hour in 0..23) {
                val prediction = calculatePredictionWithFallback(earningsMap, day, hour)
                grid[day][hour] = if (prediction >= 1.0) prediction else 0.0
            }
        }
        
        return grid
    }
    
    /**
     * Calculate prediction with sophisticated fallback logic
     */
    private fun calculatePredictionWithFallback(
        earningsMap: Array<Array<MutableList<Double>>>, 
        targetDay: Int, 
        targetHour: Int
    ): Double {
        val MIN_SAMPLES = 2 // Need at least 2 samples for reliable prediction
        
        // Level 1: Exact same day-of-week and hour
        val exactMatch = earningsMap[targetDay][targetHour]
        if (exactMatch.size >= MIN_SAMPLES) {
            val avg = exactMatch.average()
            android.util.Log.v("BoltAssist", "Exact match day=$targetDay hour=$targetHour: $avg PLN from ${exactMatch.size} trips")
            return avg
        }
        
        // Level 2: Same hour, different days (prefer weekdays vs weekends)
        val isWeekend = targetDay >= 5
        val sameHourDifferentDays = mutableListOf<Double>()
        
        for (day in 0..6) {
            val dayIsWeekend = day >= 5
            // Prefer same type (weekday/weekend)
            val priority = if (dayIsWeekend == isWeekend) 1.0 else 0.5
            
            earningsMap[day][targetHour].forEach { earning ->
                // Add multiple times based on priority to weight the average
                repeat((priority * 2).toInt()) {
                    sameHourDifferentDays.add(earning)
                }
            }
        }
        
        if (sameHourDifferentDays.size >= MIN_SAMPLES) {
            val avg = sameHourDifferentDays.average()
            android.util.Log.v("BoltAssist", "Same hour fallback day=$targetDay hour=$targetHour: $avg PLN from ${sameHourDifferentDays.size} weighted samples")
            return avg
        }
        
        // Level 3: Nearby hours (±2 hours), same day preference
        val nearbyHours = mutableListOf<Double>()
        for (hourOffset in -2..2) {
            if (hourOffset == 0) continue // Already checked exact hour
            val nearbyHour = (targetHour + hourOffset + 24) % 24
            
            // First try same day
            earningsMap[targetDay][nearbyHour].forEach { earning ->
                nearbyHours.add(earning * 1.2) // Boost same-day nearby hours
            }
            
            // Then try other days of same type (weekday/weekend)
            for (day in 0..6) {
                if (day == targetDay) continue
                val dayIsWeekend = day >= 5
                if (dayIsWeekend == isWeekend) {
                    earningsMap[day][nearbyHour].forEach { earning ->
                        nearbyHours.add(earning)
                    }
                }
            }
        }
        
        if (nearbyHours.size >= MIN_SAMPLES) {
            val avg = nearbyHours.average()
            android.util.Log.v("BoltAssist", "Nearby hours fallback day=$targetDay hour=$targetHour: $avg PLN from ${nearbyHours.size} samples")
            return avg
        }
        
        // Level 4: General average for this day type, scaled by hour patterns
        val allEarningsThisDay = earningsMap[targetDay].flatMap { it }
        val allEarningsAllDays = earningsMap.flatMap { it.flatMap { it } }
        
        if (allEarningsThisDay.isNotEmpty()) {
            val avg = allEarningsThisDay.average() * 0.7 // Conservative scaling
            android.util.Log.v("BoltAssist", "Same day average fallback day=$targetDay hour=$targetHour: $avg PLN from ${allEarningsThisDay.size} day samples")
            return avg
        }
        
        if (allEarningsAllDays.isNotEmpty()) {
            val avg = allEarningsAllDays.average() * 0.3 // Very conservative fallback
            android.util.Log.v("BoltAssist", "Global average fallback day=$targetDay hour=$targetHour: $avg PLN from ${allEarningsAllDays.size} global samples")
            return avg
        }
        
        return 0.0
    }
    
    /**
     * Get date for specific day/hour in current week
     */
    private fun getDateForDayHour(day: Int, hour: Int): Date {
        val calendar = Calendar.getInstance()
        
        // Get to Monday of current week
        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = when (currentDayOfWeek) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6; else -> 0
        }
        calendar.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        
        // Go to target day
        calendar.add(Calendar.DAY_OF_YEAR, day)
        
        // Set target hour
        calendar.set(Calendar.HOUR_OF_DAY, hour + 1) // +1 because grid hours are 1-24, not 0-23
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        return calendar.time
    }
} 