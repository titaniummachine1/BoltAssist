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
     * Add trip for specific day/hour (edit mode) - proper hour calculation
     */
    fun addTripForDayHour(day: Int, hour: Int, earnings: Int = 5): TripData {
        val targetDate = getDateForDayHour(day, hour)
        val calendar = Calendar.getInstance()
        calendar.time = targetDate
        
        // hour parameter is already 0-23 index from grid, so use directly
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(calendar.time)
        
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
        val trips = TripManager._tripsCache.filter { it.endTime != null && it.earningsPLN > 0 }
        
        if (trips.isEmpty()) {
            android.util.Log.d("BoltAssist", "No trips available for predictions")
            return grid
        }
        
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        android.util.Log.d("BoltAssist", "Calculating predictions from ${trips.size} trips")
        
        // Build earnings map with proper hour indexing (0-23)
        val earningsMap = Array(7) { Array(24) { mutableListOf<WeightedEarning>() } }
        
        trips.forEach { trip ->
            try {
                val tripDate = dateFormat.parse(trip.startTime) ?: return@forEach
                calendar.time = tripDate
                
                val tripDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
                    Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
                    Calendar.SUNDAY -> 6; else -> return@forEach
                }
                val tripHour = calendar.get(Calendar.HOUR_OF_DAY) // Keep 0-23 for internal calculations
                
                val isEditMode = trip.startStreet == "Edit Mode"
                earningsMap[tripDay][tripHour].add(
                    WeightedEarning(trip.earningsPLN.toDouble(), tripDate.time, isEditMode)
                )
                
                android.util.Log.v("BoltAssist", "Mapped trip: day=$tripDay hour=$tripHour amount=${trip.earningsPLN} editMode=$isEditMode")
            } catch (e: Exception) { 
                android.util.Log.w("BoltAssist", "Failed to parse trip date: ${trip.startTime}")
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
            // Base weight of 1.0, multiplied by earning value for value-based influence
            val valueMultiplier = kotlin.math.sqrt(earning.amount) // Square root dampens extreme values
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
                // Value-based cross-day weight calculation
                val valueMultiplier = kotlin.math.sqrt(earning.amount) * 0.1 // Lower base multiplier for cross-day
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
        if (totalWeight < 0.5) {
            for (hourOffset in listOf(-1, 1)) {
                val adjacentHour = (targetHour + hourOffset + 24) % 24
                val adjacentEarnings = earningsMap[targetDay][adjacentHour]
                
                adjacentEarnings.forEach { earning ->
                    val valueMultiplier = kotlin.math.sqrt(earning.amount) * 0.05 // Very low base for adjacent hours
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