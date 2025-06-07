package com.example.boltassist

import android.content.Context
import android.location.Location
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class TripData(
    val id: String = UUID.randomUUID().toString(),
    val startTime: String,
    val endTime: String? = null,
    val durationMinutes: Int = 0,
    val startStreet: String = "Unknown",
    val endStreet: String = "Unknown",
    val earningsPLN: Int = 0,
    val startLocation: LocationData? = null,
    val endLocation: LocationData? = null
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
)

class TripManager(private val context: Context) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var storageDirectory: File? = null
    private var currentTrip: TripData? = null
    private var tripStartTime: Long = 0
    private val tripsCache = mutableListOf<TripData>()
    
    fun setStorageDirectory(directory: File) {
        storageDirectory = directory
        if (!directory.exists()) {
            directory.mkdirs()
        }
        // Load existing trips into cache
        loadTripsFromFile()
    }
    
    fun startTrip(location: Location?): TripData {
        tripStartTime = System.currentTimeMillis()
        val startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        currentTrip = TripData(
            startTime = startTime,
            startLocation = location?.let { 
                LocationData(it.latitude, it.longitude) 
            },
            startStreet = getStreetFromLocation(location) // TODO: Implement OSM reverse geocoding
        )
        
        return currentTrip!!
    }
    
    fun stopTrip(location: Location?, earnings: Int): TripData? {
        val trip = currentTrip ?: return null
        
        val endTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val durationMinutes = ((System.currentTimeMillis() - tripStartTime) / 60000).toInt()
        
        val completedTrip = trip.copy(
            endTime = endTime,
            durationMinutes = durationMinutes,
            earningsPLN = earnings,
            endLocation = location?.let { 
                LocationData(it.latitude, it.longitude) 
            },
            endStreet = getStreetFromLocation(location) // TODO: Implement OSM reverse geocoding
        )
        
        saveTripToFile(completedTrip)
        currentTrip = null
        
        return completedTrip
    }
    
    private fun getStreetFromLocation(location: Location?): String {
        // Simple OSM Nominatim reverse geocoding
        return location?.let { 
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=${it.latitude}&lon=${it.longitude}&zoom=18&addressdetails=1"
                // For now, return coordinates - OSM integration will be added later
                "Lat: %.4f, Lng: %.4f".format(it.latitude, it.longitude)
            } catch (e: Exception) {
                "Lat: %.4f, Lng: %.4f".format(it.latitude, it.longitude)
            }
        } ?: "Unknown"
    }
    
    private fun saveTripToFile(trip: TripData) {
        // Add to cache immediately
        tripsCache.add(trip)
        
        // Save immediately after each trip
        saveAllTripsToFile()
    }
    
    private fun saveAllTripsToFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "trips_database.json")
        
        try {
            val json = gson.toJson(tripsCache.sortedByDescending { it.startTime })
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadTripsFromFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "trips_database.json")
        
        if (!file.exists()) return
        
        try {
            val json = file.readText()
            val tripsArray = gson.fromJson(json, Array<TripData>::class.java)
            tripsCache.clear()
            tripsCache.addAll(tripsArray)
        } catch (e: Exception) {
            e.printStackTrace()
            // If file is corrupted, start fresh
            tripsCache.clear()
        }
    }
    
    fun getAllTrips(): List<TripData> {
        return tripsCache.sortedByDescending { it.startTime }
    }
    
    fun forceSync() {
        saveAllTripsToFile()
    }
    
    fun isRecording(): Boolean = currentTrip != null
    
    // Get earnings data for the weekly grid
    fun getWeeklyEarningsGrid(): Array<Array<GridCellData>> {
        val grid = Array(7) { Array(24) { GridCellData() } }
        val trips = getAllTrips()
        
        trips.forEach { trip ->
            if (trip.endTime != null && trip.durationMinutes > 0) {
                val calendar = Calendar.getInstance()
                try {
                    val startDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(trip.startTime)
                    calendar.time = startDate ?: return@forEach
                    
                    val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> 0
                        Calendar.TUESDAY -> 1
                        Calendar.WEDNESDAY -> 2
                        Calendar.THURSDAY -> 3
                        Calendar.FRIDAY -> 4
                        Calendar.SATURDAY -> 5
                        Calendar.SUNDAY -> 6
                        else -> 0
                    }
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)
                    
                    if (dayOfWeek in 0..6 && hour in 0..23) {
                        val cellData = grid[dayOfWeek][hour]
                        cellData.tripCount++
                        cellData.totalEarnings += trip.earningsPLN
                        cellData.totalMinutes += trip.durationMinutes
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return grid
    }
    
    // Get current time slot for highlighting
    fun getCurrentTimeSlot(): Pair<Int, Int> {
        val calendar = Calendar.getInstance()
        val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return Pair(dayOfWeek, hour)
    }
}

data class GridCellData(
    var tripCount: Int = 0,
    var totalEarnings: Int = 0, // In PLN
    var totalMinutes: Int = 0
) {
    fun getHourlyEarnings(): Double {
        return if (totalMinutes > 0) {
            (totalEarnings.toDouble() / totalMinutes) * 60.0
        } else 0.0
    }
    
    fun hasEnoughData(): Boolean = tripCount >= 2
    
    fun getPerformanceColor(): android.graphics.Color {
        if (!hasEnoughData()) return android.graphics.Color.valueOf(0f, 0f, 0f) // Black
        
        val hourlyEarnings = getHourlyEarnings()
        // Define performance thresholds (PLN per hour) - adjusted for Bolt driver reality
        val poorEarnings = 8.0    // Red - Poor
        val decentEarnings = 25.0 // Yellow - Decent
        val goodEarnings = 45.0   // Green - Good
        val legendaryEarnings = 80.0 // Gold - Legendary (events/surge)
        
        return when {
            hourlyEarnings >= legendaryEarnings -> {
                // LEGENDARY - Bright Gold for exceptional event earnings
                android.graphics.Color.valueOf(1f, 0.84f, 0f) // Pure Gold
            }
            hourlyEarnings >= goodEarnings -> {
                // Good to Legendary gradient (Green to Gold)
                val ratio = ((hourlyEarnings - goodEarnings) / (legendaryEarnings - goodEarnings)).coerceIn(0.0, 1.0).toFloat()
                android.graphics.Color.valueOf(ratio, 1f, 0f) // Green to Gold
            }
            hourlyEarnings >= decentEarnings -> {
                // Decent to Good (Yellow to Green)
                val ratio = ((hourlyEarnings - decentEarnings) / (goodEarnings - decentEarnings)).coerceIn(0.0, 1.0).toFloat()
                android.graphics.Color.valueOf(1f - ratio, 1f, 0f)
            }
            hourlyEarnings >= poorEarnings -> {
                // Poor to Decent (Red to Yellow)
                val ratio = ((hourlyEarnings - poorEarnings) / (decentEarnings - poorEarnings)).coerceIn(0.0, 1.0).toFloat()
                android.graphics.Color.valueOf(1f, ratio, 0f)
            }
            else -> {
                // Very Poor - Dark Red
                android.graphics.Color.valueOf(0.5f, 0f, 0f)
            }
        }
    }
    
    fun getPerformanceLevel(): String {
        if (!hasEnoughData()) return "No Data"
        
        val hourlyEarnings = getHourlyEarnings()
        return when {
            hourlyEarnings >= 80.0 -> "LEGENDARY"
            hourlyEarnings >= 45.0 -> "Good"
            hourlyEarnings >= 25.0 -> "Decent"
            hourlyEarnings >= 8.0 -> "Poor"
            else -> "Very Poor"
        }
    }
    
    fun isLegendary(): Boolean = hasEnoughData() && getHourlyEarnings() >= 80.0
} 