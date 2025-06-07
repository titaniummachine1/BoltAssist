package com.example.boltassist

import android.content.Context
import android.location.Location
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

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

object TripManager {
    private lateinit var context: Context
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var storageDirectory: File? = null
    private var currentTrip: TripData? = null
    private var tripStartTime: Long = 0
    // In-memory cache as Compose state list for immediate UI updates
    private val _tripsCache = mutableStateListOf<TripData>()
    val tripsCache: SnapshotStateList<TripData> get() = _tripsCache
    // Optional storage URI for SAF-based directory
    private var storageTreeUri: Uri? = null
    
    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            android.util.Log.d("BoltAssist", "TripManager singleton initialized")
            // Try to load existing trips immediately on first initialization
            tryLoadExistingTrips()
        }
    }
    
    private fun tryLoadExistingTrips() {
        // Try to load from existing storage if available
        val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist")
            ?: context.filesDir.resolve("BoltAssist")
        
        if (defaultDir.exists() && File(defaultDir, "trips_database.json").exists()) {
            setStorageDirectory(defaultDir)
            android.util.Log.d("BoltAssist", "Auto-loaded existing trips from default directory")
        }
    }
    
    fun setStorageDirectory(directory: File) {
        storageDirectory = directory
        if (!directory.exists()) {
            directory.mkdirs()
            android.util.Log.d("BoltAssist", "Created storage directory: ${directory.absolutePath}")
        }
        android.util.Log.d("BoltAssist", "Storage directory set to: ${directory.absolutePath}")
        // Load existing trips into cache
        loadTripsFromFile()
    }
    
    /**
     * Set storage directory using SAF tree URI.
     */
    fun setStorageDirectoryUri(uri: Uri) {
        storageTreeUri = uri
        android.util.Log.d("BoltAssist", "Storage tree URI set to: $uri")
        // Load existing trips from URI
        loadTripsFromUri()
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
        android.util.Log.d("BoltAssist", "Adding trip to cache. Before: ${_tripsCache.size}")
        // Add to in-memory cache for UI
        _tripsCache.add(trip)
        android.util.Log.d("BoltAssist", "Trip added to cache. After: ${_tripsCache.size}")
        // Save immediately after each trip (File or SAF)
        if (storageTreeUri != null) {
            saveTripsToUri()
        } else {
            saveAllTripsToFile()
        }
        android.util.Log.d("BoltAssist", "Trip saved to cache and file: $trip")
    }
    
    private fun saveAllTripsToFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "trips_database.json")
        
        try {
            // Use in-memory cache for export
            val json = gson.toJson(_tripsCache.sortedByDescending { it.startTime })
            file.writeText(json)
            android.util.Log.d("BoltAssist", "Saved ${tripsCache.size} trips to: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save trips", e)
            e.printStackTrace()
        }
    }
    
    private fun loadTripsFromFile() {
        val directory = storageDirectory ?: return
        val file = File(directory, "trips_database.json")
        
        if (!file.exists()) {
            android.util.Log.d("BoltAssist", "No trips file found at: ${file.absolutePath}")
            return
        }
        
        try {
            val json = file.readText()
            val tripsArray = gson.fromJson(json, Array<TripData>::class.java)
            // Load into in-memory cache
            _tripsCache.clear()
            _tripsCache.addAll(tripsArray)
            android.util.Log.d("BoltAssist", "Loaded ${tripsCache.size} trips from: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to load trips from file", e)
            e.printStackTrace()
            // If file is corrupted, start fresh
            _tripsCache.clear()
        }
    }
    
    fun reloadFromFile() {
        // Reload trips from File or SAF URI
        if (storageTreeUri != null) {
            loadTripsFromUri()
        } else {
            loadTripsFromFile()
        }
    }
    
    fun getAllTrips(): List<TripData> {
        // Provide snapshot sorted list of in-memory cache
        return _tripsCache.sortedByDescending { it.startTime }
    }
    
    fun generateTestData() {
        android.util.Log.d("BoltAssist", "Generating test data...")
        
        // Generate some test trips for today at different hours
        val testTrips = listOf(
            createTestTrip(8, 30, 25), // 8 AM, 30 minutes, 25 PLN
            createTestTrip(10, 45, 40), // 10 AM, 45 minutes, 40 PLN  
            createTestTrip(14, 60, 80), // 2 PM, 60 minutes, 80 PLN (LEGENDARY!)
            createTestTrip(18, 25, 15), // 6 PM, 25 minutes, 15 PLN
        )
        
        testTrips.forEach { trip ->
            _tripsCache.add(trip)
            android.util.Log.d("BoltAssist", "Added test trip: $trip")
        }
        
        saveAllTripsToFile()
        android.util.Log.d("BoltAssist", "Test data generated and saved!")
    }
    
    private fun createTestTrip(hour: Int, durationMinutes: Int, earningsPLN: Int): TripData {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        
        val startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(calendar.time)
        
        calendar.add(Calendar.MINUTE, durationMinutes)
        val endTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(calendar.time)
        
        return TripData(
            id = System.currentTimeMillis().toString() + hour,
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes,
            earningsPLN = earningsPLN,
            startLocation = LocationData(52.2297, 21.0122), // Warsaw center
            endLocation = LocationData(52.2297, 21.0122),
            startStreet = "Test Street",
            endStreet = "Test Street"
        )
    }
    
    fun forceSync() {
        // Persist in-memory cache to file for export
        saveAllTripsToFile()
    }
    
    fun getStorageInfo(): String {
        val directory = storageDirectory
        if (directory == null) return "No storage set"
        
        val file = File(directory, "trips_database.json")
        return "${directory.absolutePath} (exists: ${file.exists()}, size: ${if (file.exists()) file.length() else 0} bytes)"
    }
    
    fun isRecording(): Boolean = currentTrip != null
    
    // Get earnings data for the weekly grid
    fun getWeeklyEarningsGrid(): Array<Array<GridCellData>> {
        val grid = Array(7) { Array(24) { GridCellData() } }
        val trips = getAllTrips()
        android.util.Log.d("BoltAssist", "Building grid with ${trips.size} trips")
        
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
    
    /**
     * Save all trips to SAF URI directory as JSON.
     */
    private fun saveTripsToUri() {
        val uri = storageTreeUri ?: return
        val tree = DocumentFile.fromTreeUri(context, uri) ?: run {
            android.util.Log.e("BoltAssist", "Invalid document tree URI: $uri")
            return
        }
        var fileDoc = tree.findFile("trips_database.json")
        if (fileDoc == null) {
            fileDoc = tree.createFile("application/json", "trips_database.json")
        }
        fileDoc?.uri?.let { fileUri ->
            try {
                context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                    val json = gson.toJson(_tripsCache.sortedByDescending { it.startTime })
                    outputStream.write(json.toByteArray())
                    android.util.Log.d("BoltAssist", "Saved ${tripsCache.size} trips to URI: $fileUri")
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to save trips to URI: $fileUri", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Load all trips from SAF URI directory JSON file.
     */
    private fun loadTripsFromUri() {
        val uri = storageTreeUri ?: return
        val tree = DocumentFile.fromTreeUri(context, uri) ?: run {
            android.util.Log.e("BoltAssist", "Invalid document tree URI: $uri")
            return
        }
        val fileDoc = tree.findFile("trips_database.json") ?: run {
            android.util.Log.d("BoltAssist", "No trips file found at URI: $uri")
            return
        }
        fileDoc.uri.let { fileUri ->
            try {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { it.readText() }
                    val tripsArray = gson.fromJson(json, Array<TripData>::class.java)
                    // Load into in-memory cache
                    _tripsCache.clear()
                    _tripsCache.addAll(tripsArray)
                    android.util.Log.d("BoltAssist", "Loaded ${tripsCache.size} trips from URI: $fileUri")
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to load trips from URI: $fileUri", e)
                e.printStackTrace()
                _tripsCache.clear()
            }
        }
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