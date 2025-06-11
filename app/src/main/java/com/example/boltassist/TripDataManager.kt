package com.example.boltassist

import android.location.Location
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

/**
 * Weighted earning data for EMA calculations
 */
data class WeightedEarning(val amount: Double, val timestamp: Long, val isEditMode: Boolean)

/**
 * Dedicated module for trip data management and real-time predictions
 * Handles adding trips and sophisticated prediction algorithms with proper earning-based weighting
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
     * Add trip for specific day/hour (edit mode) - generates plausible random data
     */
    fun addTripForDayHour(day: Int, hour: Int): TripData {
        android.util.Log.d("BoltAssist", "DEBUG EDIT: addTripForDayHour called with day=$day, hour=$hour")
        val targetDate = getDateForDayHour(day, hour)
        val calendar = Calendar.getInstance()
        calendar.time = targetDate
        
        // hour parameter is already 0-23 index from grid, so use directly
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        // Use predictable start time for consistent manual testing
        calendar.set(Calendar.MINUTE, 15) 
        calendar.set(Calendar.SECOND, 0)
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(calendar.time)
        
        // Use predictable data for manual testing as requested.
        val durationMinutes = 15 // Predictable 15-minute duration
        val earningsPLN = 10     // Predictable 10 PLN earnings
        
        calendar.add(Calendar.MINUTE, durationMinutes)
        val endTime = dateFormat.format(calendar.time)
        
        val trip = TripData(
            id = "edit_${System.currentTimeMillis()}_d${day}_h${hour}",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes,
            earningsPLN = earningsPLN,
            startLocation = LocationData(52.2297 + (Math.random() - 0.5) * 0.2, 21.0122 + (Math.random() - 0.5) * 0.2),
            endLocation = LocationData(52.2297 + (Math.random() - 0.5) * 0.2, 21.0122 + (Math.random() - 0.5) * 0.2),
            startStreet = "Edit Mode",
            endStreet = "Edit Mode"
        )
        
        TripManager._tripsCache.add(trip)
        
        try {
            // Use forceSync to ensure data is written to disk immediately
            TripManager.forceSync()
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save edit trip", e)
        }
        
        android.util.Log.d("BoltAssist", "Added edit trip for day=$day hour=$hour: $earningsPLN PLN")
        return trip
    }
    
    /**
     * Clear all trips for specific day/hour
     */
    fun clearTripsForDayHour(day: Int, hour: Int) {
        android.util.Log.d("BoltAssist", "DEBUG EDIT: clearTripsForDayHour called with day=$day, hour=$hour")
        val targetDate = getDateForDayHour(day, hour)
        val calendar = Calendar.getInstance()
        calendar.time = targetDate
        val targetHour = hour // hour is already 0-23 index
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
            // Use forceSync to ensure data is written to disk immediately
            TripManager.forceSync()
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save after clearing", e)
        }
        
        android.util.Log.d("BoltAssist", "Cleared ${tripsToRemove.size} trips for day=$day hour=$hour")
    }
    
    /**
     * EARNINGS-WEIGHTED prediction algorithm with granular value-based cross-day propagation
     */
    fun getAdvancedPredictionGrid(): Array<DoubleArray> {
        val grid = Array(7) { DoubleArray(24) { 0.0 } }
        
        // Implement trip retention/decay: only use the most recent N trips for each category
        val allTrips = TripManager._tripsCache
            .filter { it.endTime != null && it.earningsPLN > 0 }
            .sortedByDescending { it.startTime }

        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val weekdayTrips = mutableListOf<TripData>()
        val weekendTrips = mutableListOf<TripData>()
        val holidayTrips = mutableListOf<TripData>()

        allTrips.forEach { trip ->
            try {
                val date = dateFormat.parse(trip.startTime)
                if (date != null) {
                    val dayTag = TripManager.getDayTag(date)
                    calendar.time = date
                    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                    val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

                    if (dayTag != null && holidayTrips.size < 200) {
                        holidayTrips.add(trip)
                    } else if (isWeekend && weekendTrips.size < 300) {
                        weekendTrips.add(trip)
                    } else if (!isWeekend && dayTag == null && weekdayTrips.size < 500) {
                        weekdayTrips.add(trip)
                    }
                }
            } catch (e: Exception) { /* ignore parse error */ }
        }
    
        val trips = (weekdayTrips + weekendTrips + holidayTrips).distinctBy { it.id }

        if (trips.isEmpty()) {
            android.util.Log.d("BoltAssist", "No trips available for predictions after filtering")
            return grid
        }
        
        android.util.Log.d("BoltAssist", "Calculating predictions from ${trips.size} trips after decay/retention")
        
        // Build earnings map by first calculating daily totals for each hour slot
        val dailyTotalsMap = Array(7) { Array(24) { mutableMapOf<String, Pair<Double, Boolean>>() } }
        val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

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
                val dateString = dateOnlyFormat.format(tripDate)
                val isEditMode = trip.startStreet == "Edit Mode"

                val dailyMap = dailyTotalsMap[tripDay][tripHour]
                val current = dailyMap[dateString] ?: (0.0 to false)
                dailyMap[dateString] = (current.first + trip.earningsPLN.toDouble()) to (current.second || isEditMode)
            } catch (e: Exception) {
                android.util.Log.w("BoltAssist", "Failed to parse trip date for daily total: ${trip.startTime}")
            }
        }

        val earningsMap = Array(7) { Array(24) { mutableListOf<WeightedEarning>() } }
        for (day in 0..6) {
            for (hour in 0..23) {
                val dateMap = dailyTotalsMap[day][hour]
                dateMap.forEach { (dateStr, pair) ->
                    val (total, isEdit) = pair
                    try {
                        val date = dateOnlyFormat.parse(dateStr)
                        if (date != null) {
                            earningsMap[day][hour].add(
                                WeightedEarning(total, date.time, isEdit)
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("BoltAssist", "Failed to parse date string for weighted earning: $dateStr")
                    }
                }
            }
        }
        
        // Generate value-weighted predictions with granular propagation
        for (day in 0..6) {
            for (hour in 0..23) {
                val prediction = calculateValueWeightedPrediction(earningsMap, day, hour)
                grid[day][hour] = prediction
                
                if (prediction > 0) {
                    android.util.Log.v("BoltAssist", "Prediction: day=$day hour=$hour value=$prediction PLN")
                }
            }
        }
        
        return grid
    }
    
    /**
     * VALUE-WEIGHTED prediction with granular earning amount influence
     * Higher earnings have exponentially more influence on cross-day propagation
     */
    private fun calculateValueWeightedPrediction(
        earningsMap: Array<Array<MutableList<WeightedEarning>>>, 
        targetDay: Int, 
        targetHour: Int
    ): Double {
        val isWeekend = targetDay >= 5
        var totalWeightedValue = 0.0
        var totalWeight = 0.0
        
        // Step 1: Direct same-slot data (highest priority)
        val directEarnings = earningsMap[targetDay][targetHour]
        directEarnings.forEach { earning ->
            // Weight is directly proportional to earnings, making high-value trips very influential.
            val valueMultiplier = earning.amount / 10.0 // Scaled earning value
            val editModeBoost = if (earning.isEditMode) 3.0 else 1.0
            val weight = 1.0 * valueMultiplier * editModeBoost
            
            totalWeightedValue += earning.amount * weight
            totalWeight += weight
            
            android.util.Log.v("BoltAssist", "DIRECT: day=$targetDay hour=$targetHour amount=${earning.amount} weight=$weight")
        }
        
        // Step 2: Cross-day same-hour propagation (earnings-weighted influence)
        for (sourceDay in 0..6) {
            if (sourceDay == targetDay) continue // Skip same day (already processed)
            
            val sourceDayIsWeekend = sourceDay >= 5
            val crossDayEarnings = earningsMap[sourceDay][targetHour]
            
            crossDayEarnings.forEach { earning ->
                // Value-based cross-day weight calculation, stronger propagation
                val valueMultiplier = (earning.amount / 10.0) * 0.2 // More aggressive cross-day influence
                val dayTypeMultiplier = when {
                    sourceDayIsWeekend == isWeekend -> 0.8 // Same day type (weekday->weekday, weekend->weekend)
                    else -> 0.3 // Different day type (weekday->weekend, weekend->weekday)
                }
                val editModeBoost = if (earning.isEditMode) 2.0 else 1.0
                val weight = valueMultiplier * dayTypeMultiplier * editModeBoost
                
                totalWeightedValue += earning.amount * weight
            totalWeight += weight
            
                android.util.Log.v("BoltAssist", "CROSS-DAY: source=$sourceDay->target=$targetDay hour=$targetHour amount=${earning.amount} weight=$weight")
            }
        }
        
        // Step 3: Adjacent hour spillover (only from same day, weaker influence)
        if (totalWeight < 1.0) { // Increased threshold to allow more spillover when data is sparse
            for (hourOffset in listOf(-1, 1)) {
                val adjacentHour = (targetHour + hourOffset + 24) % 24
                val adjacentEarnings = earningsMap[targetDay][adjacentHour]
                
                adjacentEarnings.forEach { earning ->
                    val valueMultiplier = (earning.amount / 10.0) * 0.1 // Adjacent influence also based on value
                    val editModeBoost = if (earning.isEditMode) 1.5 else 1.0
                    val weight = valueMultiplier * editModeBoost
                    
                    totalWeightedValue += earning.amount * weight
                    totalWeight += weight
                    
                    android.util.Log.v("BoltAssist", "ADJACENT: day=$targetDay hour=$adjacentHour->$targetHour amount=${earning.amount} weight=$weight")
                }
            }
        }
        
        val result = if (totalWeight > 0.0) totalWeightedValue / totalWeight else 0.0
        
        // Apply minimum threshold and smoothing (lowered threshold for better visibility)
        val finalResult = if (result >= 0.1) result else 0.0
        
        // Always log predictions for debugging, especially when they're 0
        if (finalResult > 0) {
            android.util.Log.d("BoltAssist", "PREDICTION: day=$targetDay hour=$targetHour result=$finalResult totalWeight=$totalWeight")
        } else if (totalWeight > 0) {
            android.util.Log.d("BoltAssist", "PREDICTION BELOW THRESHOLD: day=$targetDay hour=$targetHour rawResult=$result totalWeight=$totalWeight")
        }
        
        return finalResult
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
        
        // hour is already 0-23, so use directly
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        return calendar.time
    }
} 