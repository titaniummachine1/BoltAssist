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
import java.net.HttpURLConnection
import java.net.URL

data class TrafficData(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val distanceMeters: Double,
    val durationSeconds: Int,
    val speedKmh: Double,
    val streetName: String = "Unknown",
    val timeBucket: Int, // 0-47 (48 half-hour buckets per day)
    val dayOfWeek: Int, // 0-6 (Monday to Sunday)
    val dayTag: String? = null // Holiday tag if applicable
)

// Kalman filter for speed prediction per road segment and time bucket
data class SpeedKalmanState(
    var estimate: Double = 30.0,       // Start with 30 km/h baseline
    var errorCovariance: Double = 100.0, // Higher uncertainty for learning
    val processNoise: Double = 2.0,     // Speed can change gradually
    val measurementNoise: Double = 5.0  // GPS/speed measurement noise
)

object TrafficDataManager {
    private lateinit var context: Context
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var storageDirectory: File? = null
    private val _trafficCache = mutableStateListOf<TrafficData>()
    val trafficCache get() = _trafficCache
    
    // Kalman filter states: [streetHash][dayOfWeek][timeBucket] -> SpeedKalmanState
    private val speedKalmanStates = mutableMapOf<String, Array<Array<SpeedKalmanState>>>()
    
    // Location tracking state
    private var lastLocation: Location? = null
    private var lastLocationTime: Long = 0
    private var isTracking = false
    private var trackingJob: Job? = null
    
    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            
            // Use same storage as TripManager
            val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist")
                ?: context.filesDir.resolve("BoltAssist")
            
            setStorageDirectory(defaultDir)
            android.util.Log.d("BoltAssist", "TrafficDataManager initialized")
        }
    }
    
    fun setStorageDirectory(directory: File) {
        storageDirectory = directory
        if (!directory.exists()) {
            directory.mkdirs()
        }
        loadTrafficFromFile()
    }
    
    fun startTracking() {
        if (isTracking) return
        
        isTracking = true
        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isTracking) {
                try {
                    delay(10_000) // Every 10 seconds
                    // This will be called by FloatingWindowService with actual location
                } catch (e: Exception) {
                    android.util.Log.e("BoltAssist", "Tracking error", e)
                }
            }
        }
        android.util.Log.d("BoltAssist", "Traffic tracking started")
    }
    
    fun stopTracking() {
        isTracking = false
        trackingJob?.cancel()
        android.util.Log.d("BoltAssist", "Traffic tracking stopped")
    }
    
    /**
     * Process new location data (called every 10 seconds during tracking)
     */
    fun processLocation(location: Location) {
        val currentTime = System.currentTimeMillis()
        
        lastLocation?.let { prevLocation ->
            val timeDiff = (currentTime - lastLocationTime) / 1000.0 // seconds
            
            // Only process if we have reasonable time difference (8-15 seconds)
            if (timeDiff in 8.0..15.0) {
                val distance = prevLocation.distanceTo(location).toDouble() // meters
                
                // Only process if we moved significantly (avoid GPS noise)
                if (distance > 5.0) {
                    val speedKmh = (distance / timeDiff) * 3.6 // Convert m/s to km/h
                    
                    // Filter out unrealistic speeds (0.5 - 200 km/h)
                    if (speedKmh in 0.5..200.0) {
                        val trafficData = createTrafficData(
                            prevLocation, location, distance, timeDiff.toInt(), speedKmh
                        )
                        
                        _trafficCache.add(trafficData)
                        updateSpeedPrediction(trafficData)
                        
                        // Save periodically (every 10 data points)
                        if (_trafficCache.size % 10 == 0) {
                            saveTrafficToFile()
                        }
                        
                        android.util.Log.d("BoltAssist", 
                            "Traffic: ${trafficData.streetName} at ${speedKmh.toInt()} km/h")
                    }
                }
            }
        }
        
        lastLocation = location
        lastLocationTime = currentTime
    }
    
    private fun createTrafficData(
        startLoc: Location, 
        endLoc: Location, 
        distance: Double, 
        duration: Int, 
        speedKmh: Double
    ): TrafficData {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val calendar = Calendar.getInstance()
        
        // Calculate 30-minute time bucket (0-47)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeBucket = hour * 2 + if (minute >= 30) 1 else 0
        
        // Day of week (0=Monday, 6=Sunday)
        val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6; else -> 0
        }
        
        // Get street name (simplified for now)
        val streetName = getStreetName(startLoc, endLoc)
        
        // Get holiday tag if applicable
        val dayTag = TripManager.getDayTag(calendar.time)
        
        return TrafficData(
            timestamp = timestamp,
            startLat = startLoc.latitude,
            startLng = startLoc.longitude,
            endLat = endLoc.latitude,
            endLng = endLoc.longitude,
            distanceMeters = distance,
            durationSeconds = duration,
            speedKmh = speedKmh,
            streetName = streetName,
            timeBucket = timeBucket,
            dayOfWeek = dayOfWeek,
            dayTag = dayTag
        )
    }
    
    private fun getStreetName(startLoc: Location, endLoc: Location): String {
        // Simple street identification based on coordinate grid
        val lat = ((startLoc.latitude + endLoc.latitude) / 2 * 1000).toInt()
        val lng = ((startLoc.longitude + endLoc.longitude) / 2 * 1000).toInt()
        return "Street_${lat}_${lng}"
    }
    
    private fun updateSpeedPrediction(trafficData: TrafficData) {
        val streetHash = trafficData.streetName
        val dayOfWeek = trafficData.dayOfWeek
        val timeBucket = trafficData.timeBucket
        
        // Initialize Kalman states if needed
        if (!speedKalmanStates.containsKey(streetHash)) {
            speedKalmanStates[streetHash] = Array(7) { Array(48) { SpeedKalmanState() } }
        }
        
        val kalmanState = speedKalmanStates[streetHash]!![dayOfWeek][timeBucket]
        
        // Kalman filter update
        val measurement = trafficData.speedKmh
        
        // Prediction step
        kalmanState.errorCovariance += kalmanState.processNoise
        
        // Update step
        val kalmanGain = kalmanState.errorCovariance / 
            (kalmanState.errorCovariance + kalmanState.measurementNoise)
        
        kalmanState.estimate += kalmanGain * (measurement - kalmanState.estimate)
        kalmanState.errorCovariance *= (1 - kalmanGain)
        
        android.util.Log.v("BoltAssist", 
            "Speed prediction updated: ${streetHash} -> ${kalmanState.estimate.toInt()} km/h")
    }
    
    /**
     * Get predicted speed for a route segment
     */
    fun getPredictedSpeed(
        streetName: String, 
        dayOfWeek: Int = getCurrentDayOfWeek(),
        timeBucket: Int = getCurrentTimeBucket()
    ): Double {
        return speedKalmanStates[streetName]?.get(dayOfWeek)?.get(timeBucket)?.estimate ?: 30.0
    }
    
    /**
     * Calculate ETA for a route (distance in meters)
     */
    fun calculateETA(routeDistanceMeters: Double, streetNames: List<String> = emptyList()): Double {
        if (streetNames.isEmpty()) {
            // No specific route info, use general average
            val avgSpeed = _trafficCache.takeLastWhile { it.speedKmh > 0 }
                .takeLast(20)
                .map { it.speedKmh }
                .average()
                .takeIf { !it.isNaN() } ?: 30.0
            
            return (routeDistanceMeters / 1000.0) / avgSpeed * 60 // minutes
        }
        
        // Use route-specific predictions
        val currentDay = getCurrentDayOfWeek()
        val currentBucket = getCurrentTimeBucket()
        
        val avgSpeed = streetNames.map { streetName ->
            getPredictedSpeed(streetName, currentDay, currentBucket)
        }.average()
        
        return (routeDistanceMeters / 1000.0) / avgSpeed * 60 // minutes
    }
    
    private fun getCurrentTimeBucket(): Int {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return hour * 2 + if (minute >= 30) 1 else 0
    }
    
    private fun getCurrentDayOfWeek(): Int {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6; else -> 0
        }
    }
    
    private fun saveTrafficToFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "traffic_database.json")
        
        try {
            val json = gson.toJson(_trafficCache.sortedByDescending { it.timestamp })
            file.writeText(json)
            android.util.Log.d("BoltAssist", "Saved ${_trafficCache.size} traffic records")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save traffic data", e)
        }
    }
    
    private fun loadTrafficFromFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "traffic_database.json")
        
        _trafficCache.clear()
        
        if (!file.exists()) {
            android.util.Log.d("BoltAssist", "No traffic file found")
            return
        }
        
        try {
            val json = file.readText()
            val trafficArray = gson.fromJson(json, Array<TrafficData>::class.java)
            _trafficCache.addAll(trafficArray)
            
            // Rebuild Kalman filter states from historical data
            rebuildKalmanStates()
            
            android.util.Log.d("BoltAssist", "Loaded ${_trafficCache.size} traffic records")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to load traffic data", e)
            _trafficCache.clear()
        }
    }
    
    private fun rebuildKalmanStates() {
        speedKalmanStates.clear()
        
        // Process historical data to rebuild Kalman states
        _trafficCache.sortedBy { it.timestamp }.forEach { trafficData ->
            updateSpeedPrediction(trafficData)
        }
        
        android.util.Log.d("BoltAssist", "Rebuilt Kalman states for ${speedKalmanStates.size} streets")
    }
    
    fun getTrafficSummary(): String {
        val totalRecords = _trafficCache.size
        val uniqueStreets = _trafficCache.map { it.streetName }.distinct().size
        val avgSpeed = _trafficCache.map { it.speedKmh }.average().takeIf { !it.isNaN() } ?: 0.0
        
        return "Traffic: $totalRecords records, $uniqueStreets streets, avg ${avgSpeed.toInt()} km/h"
    }
} 