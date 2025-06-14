package com.example.boltassist

import android.content.Context
import android.location.Location
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import com.example.boltassist.TimeBucketConfig

/**
 * Represents demand/supply data for a specific street segment at a specific time
 */
data class StreetDemandData(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String, // When this data was captured
    val streetHash: String, // Unique identifier for street segment
    val centerLat: Double,
    val centerLng: Double,
    val passengerDemand: Int = 0, // Number of passenger requests seen
    val driverSupply: Int = 0, // Number of drivers seen in area
    val timeBucket: Int, // 0-47 for 30-minute buckets, or 0-95 for 15-minute buckets
    val dayOfWeek: Int, // 0-6 (Monday to Sunday)
    val dayTag: String? = null, // Holiday tag if applicable
    val dataSource: String = "manual" // "manual", "auto", "predicted"
)

/**
 * Predictive model for street-level earnings potential
 */
data class StreetPrediction(
    val streetHash: String,
    val centerLat: Double,
    val centerLng: Double,
    val predictedEarnings: Double, // Expected PLN per hour
    val confidenceLevel: Double, // 0.0 - 1.0
    val travelTimeMinutes: Int, // Time to reach from current location
    val demandScore: Double, // Passenger demand rating
    val supplyScore: Double, // Driver competition rating
    val optimalTimeMinutes: Int // Minutes into future when this prediction applies
)

object StreetDataManager {
    private lateinit var context: Context
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var storageDirectory: File? = null
    private val _streetDataCache = mutableStateListOf<StreetDemandData>()
    val streetDataCache get() = _streetDataCache
    
    // Current location for distance calculations
    private var currentLocation: Location? = null
    
    // Street hash cache for performance
    private val streetHashCache = mutableMapOf<String, String>()
    
    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            
            // Use same storage as TripManager
            val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist")
                ?: context.filesDir.resolve("BoltAssist")
            
            setStorageDirectory(defaultDir)
            android.util.Log.d("BoltAssist", "StreetDataManager initialized")
        }
    }
    
    fun setStorageDirectory(directory: File) {
        storageDirectory = directory
        if (!directory.exists()) {
            directory.mkdirs()
        }
        loadStreetDataFromFile()
    }
    
    /**
     * Update current location for distance-based predictions
     */
    fun updateCurrentLocation(location: Location) {
        currentLocation = location
    }
    
    /**
     * Add manually captured demand/supply data from screen scanning
     */
    fun addStreetData(
        lat: Double,
        lng: Double,
        passengerDemand: Int = 0,
        driverSupply: Int = 0,
        dataSource: String = "manual"
    ) {
        val streetHash = getStreetHash(lat, lng)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val calendar = Calendar.getInstance()
        // Delegate to shared config – allows switching between 30- and 15-minute buckets
        val timeBucket = TimeBucketConfig.getTimeBucket(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        
        val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6; else -> 0
        }
        
        val dayTag = TripManager.getDayTag(calendar.time)
        
        val streetData = StreetDemandData(
            timestamp = timestamp,
            streetHash = streetHash,
            centerLat = lat,
            centerLng = lng,
            passengerDemand = passengerDemand,
            driverSupply = driverSupply,
            timeBucket = timeBucket,
            dayOfWeek = dayOfWeek,
            dayTag = dayTag,
            dataSource = dataSource
        )
        
        _streetDataCache.add(streetData)
        
        // Save periodically
        if (_streetDataCache.size % 10 == 0) {
            saveStreetDataToFile()
        }
        
        android.util.Log.d("BoltAssist", "Added street data: $streetHash demand=$passengerDemand supply=$driverSupply")
    }
    
    /**
     * Generate street hash for a location (groups nearby locations)
     */
    private fun getStreetHash(lat: Double, lng: Double): String {
        // Create 100m x 100m grid cells for grouping
        val gridSize = 0.001 // Approximately 100m
        val gridLat = (lat / gridSize).toInt()
        val gridLng = (lng / gridSize).toInt()
        return "street_${gridLat}_${gridLng}"
    }
    
    /**
     * Get street predictions based on current location and time
     */
    fun getStreetPredictions(maxDistanceKm: Double = 5.0): List<StreetPrediction> {
        val currentLoc = currentLocation ?: return emptyList()
        
        // Group data by street hash
        val streetGroups = _streetDataCache.groupBy { it.streetHash }
        val predictions = mutableListOf<StreetPrediction>()
        
        streetGroups.forEach { (streetHash, dataList) ->
            if (dataList.isEmpty()) return@forEach
            
            val representativeData = dataList.first()
            val streetLocation = Location("").apply {
                latitude = representativeData.centerLat
                longitude = representativeData.centerLng
            }
            
            val distanceKm = currentLoc.distanceTo(streetLocation) / 1000.0
            if (distanceKm > maxDistanceKm) return@forEach
            
            // Calculate travel time assuming 40 km/h average speed
            val travelTimeMinutes = (distanceKm / 40.0 * 60).toInt()
            
            // Calculate future time bucket when we'd arrive (using 15-minute buckets)
            val futureTime = Calendar.getInstance().apply {
                add(Calendar.MINUTE, travelTimeMinutes)
            }
            val futureBucket = TimeBucketConfig.getTimeBucket(
                futureTime.get(Calendar.HOUR_OF_DAY),
                futureTime.get(Calendar.MINUTE)
            )
            val futureDay = getCurrentDayOfWeek(futureTime)
            
            // Find relevant historical data for that future time
            val relevantData = dataList.filter { 
                it.timeBucket == futureBucket && it.dayOfWeek == futureDay 
            }
            
            // Also include recent data from the last 30 minutes for real-time adjustment
            val recentData = dataList.filter {
                try {
                    val dataTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(it.timestamp)?.time ?: 0L
                    val timeDiff = System.currentTimeMillis() - dataTime
                    timeDiff <= 30 * 60 * 1000 // Last 30 minutes
                } catch (e: Exception) { false }
            }
            
            if (relevantData.isNotEmpty() || recentData.isNotEmpty()) {
                // Combine historical and recent data with different weights
                val historicalDemand = if (relevantData.isNotEmpty()) {
                    relevantData.map { it.passengerDemand }.average()
                } else 0.0
                
                val historicalSupply = if (relevantData.isNotEmpty()) {
                    relevantData.map { it.driverSupply }.average()
                } else 0.0
                
                val recentDemand = if (recentData.isNotEmpty()) {
                    recentData.map { it.passengerDemand }.average()
                } else 0.0
                
                val recentSupply = if (recentData.isNotEmpty()) {
                    recentData.map { it.driverSupply }.average()
                } else 0.0
                
                // Weight recent data more heavily for short travel times
                val recentWeight = when {
                    travelTimeMinutes <= 5 -> 0.8 // Very recent data is most important
                    travelTimeMinutes <= 15 -> 0.6 // Recent data still important
                    travelTimeMinutes <= 30 -> 0.4 // Historical becomes more important
                    else -> 0.2 // Mostly historical for longer travel times
                }
                val historicalWeight = 1.0 - recentWeight
                
                val avgDemand = (recentDemand * recentWeight + historicalDemand * historicalWeight)
                val avgSupply = (recentSupply * recentWeight + historicalSupply * historicalWeight)
                
                // Enhanced earnings prediction with time-based adjustments
                val demandSupplyRatio = if (avgSupply > 0) avgDemand / avgSupply else avgDemand
                val baseEarnings = 8.0 // Base PLN per hour
                
                // Time-based multipliers for demand patterns
                val timeMultiplier = when (futureBucket) {
                    in 28..36 -> 1.3 // 7:00-9:00 AM - morning rush
                    in 68..76 -> 1.4 // 5:00-7:00 PM - evening rush
                    in 80..92 -> 1.2 // 8:00-11:00 PM - nightlife
                    in 4..12 -> 0.7  // 1:00-3:00 AM - low demand
                    else -> 1.0
                }
                
                val predictedEarnings = baseEarnings * (1.0 + demandSupplyRatio * 0.5) * timeMultiplier
                
                // Confidence based on data availability and recency
                val dataPoints = relevantData.size + recentData.size
                val baseConfidence = minOf(1.0, dataPoints / 15.0) // More data = higher confidence
                val recencyBonus = if (recentData.isNotEmpty()) 0.2 else 0.0
                val confidence = (baseConfidence + recencyBonus).coerceIn(0.0, 1.0)
                
                val prediction = StreetPrediction(
                    streetHash = streetHash,
                    centerLat = representativeData.centerLat,
                    centerLng = representativeData.centerLng,
                    predictedEarnings = predictedEarnings,
                    confidenceLevel = confidence,
                    travelTimeMinutes = travelTimeMinutes,
                    demandScore = avgDemand,
                    supplyScore = avgSupply,
                    optimalTimeMinutes = travelTimeMinutes
                )
                
                predictions.add(prediction)
            }
        }
        
        // Sort by predicted earnings adjusted for travel time (closer = better)
        return predictions.sortedByDescending { 
            it.predictedEarnings * (1.0 - it.travelTimeMinutes / 60.0 * 0.3) // Slight penalty for distance
        }
    }
    
    private fun getCurrentTimeBucket(calendar: Calendar = Calendar.getInstance()): Int {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return TimeBucketConfig.getTimeBucket(hour, minute)
    }
    
    private fun getCurrentDayOfWeek(calendar: Calendar = Calendar.getInstance()): Int {
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6; else -> 0
        }
    }
    
    private fun saveStreetDataToFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "street_data.json")
        
        try {
            val json = gson.toJson(_streetDataCache.sortedByDescending { it.timestamp })
            file.writeText(json)
            android.util.Log.d("BoltAssist", "Saved ${_streetDataCache.size} street data records")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save street data", e)
        }
    }
    
    private fun loadStreetDataFromFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "street_data.json")
        
        _streetDataCache.clear()
        
        if (!file.exists()) {
            android.util.Log.d("BoltAssist", "No street data file found")
            return
        }
        
        try {
            val json = file.readText()
            val dataArray = gson.fromJson(json, Array<StreetDemandData>::class.java)
            _streetDataCache.addAll(dataArray)
            
            android.util.Log.d("BoltAssist", "Loaded ${_streetDataCache.size} street data records")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to load street data", e)
            _streetDataCache.clear()
        }
    }
    
    fun getDataSummary(): String {
        val totalRecords = _streetDataCache.size
        val uniqueStreets = _streetDataCache.map { it.streetHash }.distinct().size
        val avgDemand = _streetDataCache.map { it.passengerDemand }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgSupply = _streetDataCache.map { it.driverSupply }.average().takeIf { !it.isNaN() } ?: 0.0
        
        return "Streets: $totalRecords records, $uniqueStreets areas, avg demand ${avgDemand.toInt()}, avg supply ${avgSupply.toInt()}"
    }
} 