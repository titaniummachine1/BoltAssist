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
        // Use 30-minute buckets for now (can be changed to 15-minute later)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeBucket = hour * 2 + if (minute >= 30) 1 else 0
        
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
            
            // Calculate future time bucket when we'd arrive
            val futureTime = Calendar.getInstance().apply {
                add(Calendar.MINUTE, travelTimeMinutes)
            }
            val futureBucket = getCurrentTimeBucket(futureTime)
            val futureDay = getCurrentDayOfWeek(futureTime)
            
            // Find relevant historical data for that time
            val relevantData = dataList.filter { 
                it.timeBucket == futureBucket && it.dayOfWeek == futureDay 
            }
            
            if (relevantData.isNotEmpty()) {
                val avgDemand = relevantData.map { it.passengerDemand }.average()
                val avgSupply = relevantData.map { it.driverSupply }.average()
                
                // Simple earnings prediction based on demand/supply ratio
                val demandSupplyRatio = if (avgSupply > 0) avgDemand / avgSupply else avgDemand
                val baseEarnings = 8.0 // Base PLN per hour
                val predictedEarnings = baseEarnings * (1.0 + demandSupplyRatio * 0.5)
                
                val prediction = StreetPrediction(
                    streetHash = streetHash,
                    centerLat = representativeData.centerLat,
                    centerLng = representativeData.centerLng,
                    predictedEarnings = predictedEarnings,
                    confidenceLevel = minOf(1.0, relevantData.size / 10.0), // More data = higher confidence
                    travelTimeMinutes = travelTimeMinutes,
                    demandScore = avgDemand,
                    supplyScore = avgSupply,
                    optimalTimeMinutes = travelTimeMinutes
                )
                
                predictions.add(prediction)
            }
        }
        
        // Sort by predicted earnings (highest first)
        return predictions.sortedByDescending { it.predictedEarnings }
    }
    
    private fun getCurrentTimeBucket(calendar: Calendar = Calendar.getInstance()): Int {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return hour * 2 + if (minute >= 30) 1 else 0
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