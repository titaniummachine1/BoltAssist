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
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser

data class TripData(
    val id: String = UUID.randomUUID().toString(),
    val startTime: String,
    val endTime: String? = null,
    val durationMinutes: Int = 0,
    val startStreet: String = "Unknown",
    val endStreet: String = "Unknown",
    val earningsPLN: Int = 0,
    val startLocation: LocationData? = null,
    val endLocation: LocationData? = null,
    val isQuick: Boolean = false // Flag for trips started & ended inside same minute
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
)

// Kalman filter state for each hour of each day
data class KalmanState(
    var estimate: Double = 5.0,        // Start with 5 PLN baseline expectation
    var errorCovariance: Double = 25.0, // Higher uncertainty for faster learning from sparse data
    val processNoise: Double = 1.0,    // Allow gradual change over time
    val measurementNoise: Double = 4.0 // Account for variability in trip earnings
)

object TripManager {
    private lateinit var context: Context
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var storageDirectory: File? = null
    private var currentTrip: TripData? = null
    private var tripStartTime: Long = 0
    // In-memory cache as Compose state list for immediate UI updates
    val _tripsCache = mutableStateListOf<TripData>()
    val tripsCache: SnapshotStateList<TripData> get() = _tripsCache
    // Data version for triggering recomposition
    var dataVersion by mutableStateOf(0)
    // Optional storage URI for SAF-based directory
    private var storageTreeUri: Uri? = null
    // Map of month-day (MM-dd) -> holiday name
    private val holidayMap = mutableMapOf<String, String>()
    // Kalman filter states for each day/hour combination - persistent across calls
    private val kalmanStates = Array(7) { Array(24) { KalmanState() } }
    private var kalmanInitialized = false
    
    fun isInitialized(): Boolean = ::context.isInitialized
    
    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            android.util.Log.d("BoltAssist", "TripManager singleton initialized")
            
            // Initialize Sentry for error tracking
            try {
                // Note: In real implementation, add Sentry SDK dependency and configure properly
                android.util.Log.d("BoltAssist", "Sentry integration ready for error tracking")
                android.util.Log.d("BoltAssist", "SENTRY_DSN: https://f63ec440edb9b18f05c79f654b2fb1fe@o4509459734724608.ingest.de.sentry.io/4509459787743312")
            } catch (e: Exception) {
                android.util.Log.w("BoltAssist", "Sentry initialization skipped: $e")
            }
            
            // Ensure default storage is always available
            val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist")
                ?: context.filesDir.resolve("BoltAssist")
            if (storageDirectory == null) {
                setStorageDirectory(defaultDir)
                android.util.Log.d("BoltAssist", "Set default storage directory: ${defaultDir.absolutePath}")
            }
            
            // Load existing trips from files
            tryLoadExistingTrips()
            // Fetch public holidays (Poland) asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                fetchHolidays(year)
            }
        } else {
            android.util.Log.d("BoltAssist", "TripManager already initialized with ${_tripsCache.size} trips")
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
        
        // Also check for SAF saved path
        val prefs = context.getSharedPreferences("BoltAssist", Context.MODE_PRIVATE)
        val savedPath = prefs.getString("storage_path", null)
        if (savedPath != null) {
            try {
                val uri = Uri.parse(savedPath)
                setStorageDirectoryUri(uri)
                android.util.Log.d("BoltAssist", "Auto-loaded existing trips from SAF: $uri")
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to auto-load from SAF", e)
            }
        }
    }
    
    fun setStorageDirectory(directory: File) {
        // Check if we're setting the same directory and already have data
        val isSameDirectory = storageDirectory?.absolutePath == directory.absolutePath
        val hasExistingData = _tripsCache.isNotEmpty()
        
        storageDirectory = directory
        if (!directory.exists()) {
            directory.mkdirs()
            android.util.Log.d("BoltAssist", "Created storage directory: ${directory.absolutePath}")
        }
        android.util.Log.d("BoltAssist", "Storage directory set to: ${directory.absolutePath}")
        
        // Only reload if we don't have the same directory or if we have no data
        if (!isSameDirectory || !hasExistingData) {
            android.util.Log.d("BoltAssist", "Loading trips from file (same dir: $isSameDirectory, has data: $hasExistingData)")
        loadTripsFromFile()
        } else {
            android.util.Log.d("BoltAssist", "Skipping reload - same directory and existing data preserved (${_tripsCache.size} trips)")
        }
    }
    
    /**
     * Set storage directory using SAF tree URI.
     */
    fun setStorageDirectoryUri(uri: Uri) {
        // Check if we're setting the same URI and already have data
        val isSameUri = storageTreeUri?.toString() == uri.toString()
        val hasExistingData = _tripsCache.isNotEmpty()
        
        storageTreeUri = uri
        android.util.Log.d("BoltAssist", "Storage tree URI set to: $uri")
        
        // Only reload if we don't have the same URI or if we have no data
        if (!isSameUri || !hasExistingData) {
            android.util.Log.d("BoltAssist", "Loading trips from URI (same URI: $isSameUri, has data: $hasExistingData)")
        loadTripsFromUri()
        } else {
            android.util.Log.d("BoltAssist", "Skipping URI reload - same URI and existing data preserved (${_tripsCache.size} trips)")
        }
    }
    
    fun startTrip(location: Location?, allowMerge: Boolean = true): TripData {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = Date()
        val startTime = dateFormat.format(now)

        if (allowMerge) {
        // Check if we should merge with the most recent completed trip
        val lastCompletedTrip = _tripsCache
            .filter { it.endTime != null }
            .maxByOrNull { it.endTime!! } // latest by endTime string compare works because same format

        if (lastCompletedTrip != null && location != null && lastCompletedTrip.endLocation != null) {
            try {
                val lastEndMillis = dateFormat.parse(lastCompletedTrip.endTime!!)?.time ?: 0L
                val timeDiff = now.time - lastEndMillis // milliseconds since last trip ended

                val results = FloatArray(1)
                Location.distanceBetween(
                    lastCompletedTrip.endLocation.latitude,
                    lastCompletedTrip.endLocation.longitude,
                    location.latitude,
                    location.longitude,
                    results
                )
                val distance = results[0]

                if (timeDiff <= 60_000 && distance <= 100f) {
                    // Merge: reopen the last trip instead of creating a new one
                    android.util.Log.d("BoltAssist", "Merging new START with previous trip ${lastCompletedTrip.id} (timeDiff=${timeDiff}ms, dist=${distance}m)")

                    val index = _tripsCache.indexOfFirst { it.id == lastCompletedTrip.id }
                    if (index != -1) {
                        // Remove end information so it becomes an active trip again
                        val reopened = lastCompletedTrip.copy(endTime = null, endLocation = null)
                        _tripsCache[index] = reopened
                        currentTrip = reopened
                        tripStartTime = dateFormat.parse(reopened.startTime)?.time ?: now.time
                        // No need to add to cache – already replaced
                        return reopened
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed merging with previous trip", e)
                }
            }
        }

        // Normal path – create a brand-new trip
        tripStartTime = now.time
        currentTrip = TripData(
            startTime = startTime,
            startLocation = location?.let {
                LocationData(it.latitude, it.longitude)
            },
            startStreet = getStreetFromLocation(location)
        )

        _tripsCache.add(currentTrip!!)
        saveCacheAndNotify()

        android.util.Log.d("BoltAssist", "Started and saved new trip: ${currentTrip!!.id}")
        return currentTrip!!
    }
    
    fun stopTrip(location: Location?, earnings: Int): TripData? {
        val tripInProgress = currentTrip ?: return null
        
        // Use EXACT same date format as test data
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val endTime = dateFormat.format(Date())
        val durationMinutes = ((System.currentTimeMillis() - tripStartTime) / 60000).toInt()
        
        var adjustedStartTime = tripInProgress.startTime
        var adjustedDuration = durationMinutes
        var quickFlag = false

        if (durationMinutes == 0) {
            // Treat as forgotten start: backdate start by 5 minutes and mark quick
            val endDate = dateFormat.parse(endTime)
            endDate?.let {
                val cal = Calendar.getInstance()
                cal.time = it
                cal.add(Calendar.MINUTE, -5)
                adjustedStartTime = dateFormat.format(cal.time)
                adjustedDuration = 5
                quickFlag = true
            }
        }
        
        val completedTrip = tripInProgress.copy(
            startTime = adjustedStartTime,
            endTime = endTime,
            durationMinutes = adjustedDuration,
            earningsPLN = earnings,
            endLocation = location?.let { 
                LocationData(it.latitude, it.longitude) 
            },
            endStreet = getStreetFromLocation(location),
            isQuick = quickFlag
        )
        
        // Find the original trip in the cache and update it
        val index = _tripsCache.indexOfFirst { it.id == completedTrip.id }
        if (index != -1) {
            _tripsCache[index] = completedTrip
        }
        
        saveCacheAndNotify()
        currentTrip = null
        
        return completedTrip
    }
    
    private fun getStreetFromLocation(location: Location?): String {
        // Simple OSM Nominatim reverse geocoding
        return location?.let { 
            try {
                // For now, return coordinates - OSM integration will be added later
                "Lat: %.4f, Lng: %.4f".format(it.latitude, it.longitude)
            } catch (e: Exception) {
                "Lat: %.4f, Lng: %.4f".format(it.latitude, it.longitude)
            }
        } ?: "Unknown"
    }
    
    /**
     * Centralized function to save the current cache and notify UI for recomposition.
     */
    fun saveCacheAndNotify() {
        try {
            if (storageTreeUri != null) {
                android.util.Log.d("BoltAssist", "SAVE: Saving to SAF URI")
                saveTripsToUri()
            } else if (storageDirectory != null) {
                android.util.Log.d("BoltAssist", "SAVE: Saving to file directory")
                saveAllTripsToFile()
            } else {
                android.util.Log.e("BoltAssist", "SAVE: ERROR - No storage directory or URI configured!")
            }
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save trip immediately", e)
            e.printStackTrace()
        }
        dataVersion++
        android.util.Log.d("BoltAssist", "SAVE & NOTIFY: Data version is now $dataVersion")
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
        val directory = storageDirectory ?: run {
            android.util.Log.w("BoltAssist", "LOAD: No storage directory set, skipping load")
            return
        }
        val file = File(directory, "trips_database.json")
        
        android.util.Log.d("BoltAssist", "LOAD: Starting load from ${file.absolutePath}")
        
        if (!file.exists()) {
            android.util.Log.d("BoltAssist", "LOAD: No trips file found at: ${file.absolutePath}")
            if (_tripsCache.isNotEmpty()) {
                _tripsCache.clear()
                dataVersion++
            }
            return
        }
        
        android.util.Log.d("BoltAssist", "LOAD: Loading from file: ${file.absolutePath} (size: ${file.length()} bytes)")
        
        try {
            val json = file.readText()
            if (json.isBlank()) {
                if (_tripsCache.isNotEmpty()) {
                    _tripsCache.clear()
                    dataVersion++
                }
                android.util.Log.w("BoltAssist", "LOAD: Trips file is blank, cache cleared.")
                return
            }
            val tripsArray = parseTripsLenient(json)
            
            android.util.Log.d("BoltAssist", "LOAD: Parsed ${tripsArray.size} trips from JSON (lenient mode)")
            
            // Atomically update cache only after successful parse
            _tripsCache.clear()
            _tripsCache.addAll(tripsArray)
            dataVersion++
            
            android.util.Log.d("BoltAssist", "LOAD: Added ${tripsArray.size} trips to cache.")
            android.util.Log.d("BoltAssist", "LOAD: Final cache size: ${_tripsCache.size}")
            
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to load trips from file in lenient mode, cache not cleared to prevent data loss.", e)
            e.printStackTrace()
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
    
    fun forceSync() {
        // Persist in-memory cache to file for export and notify
        saveCacheAndNotify()
    }
    
    fun getStorageInfo(): String {
        val directory = storageDirectory
        if (directory == null) return "No storage set"
        
        val file = File(directory, "trips_database.json")
        return "${directory.absolutePath} (exists: ${file.exists()}, size: ${if (file.exists()) file.length() else 0} bytes)"
    }
    
    fun isRecording(): Boolean = currentTrip != null
    
    /**
     * Reset database for debugging - clears all trips and saves empty file
     */
    fun resetDatabase() {
        // Clear in-memory cache
        _tripsCache.clear()
        
        // Reset Kalman filter states
        kalmanInitialized = false
        for (day in 0..6) {
            for (hour in 0..23) {
                kalmanStates[day][hour] = KalmanState()
            }
        }
        
        // Explicitly delete the database file from storage to ensure a clean reset
        try {
            if (storageTreeUri != null) {
                // Handle SAF storage
                val tree = DocumentFile.fromTreeUri(context, storageTreeUri!!)
                val fileDoc = tree?.findFile("trips_database.json")
                if (fileDoc?.exists() == true) {
                    if (fileDoc.delete()) {
                        android.util.Log.d("BoltAssist", "RESET: Successfully deleted database file from SAF URI.")
                    } else {
                        android.util.Log.w("BoltAssist", "RESET: Failed to delete database file from SAF URI.")
                    }
                }
            } else if (storageDirectory != null) {
                // Handle direct file storage
                val file = File(storageDirectory, "trips_database.json")
                if (file.exists()) {
                    if (file.delete()) {
                        android.util.Log.d("BoltAssist", "RESET: Successfully deleted database file from directory.")
                    } else {
                        android.util.Log.w("BoltAssist", "RESET: Failed to delete database file from directory.")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "RESET: Failed to delete database file", e)
        }
        
        // Notify UI to refresh.
        // This ensures the grid clears immediately.
        dataVersion++
    }
    

    
    /**
     * Get the date for a specific day/hour in current week
     */
    private fun getDateForDayHour(day: Int, hour: Int): Date {
        val calendar = Calendar.getInstance()
        
        // Get to Monday of current week
        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = when (currentDayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
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
    
    // Simple grid data - total revenue per hour for the week
    fun getWeeklyGrid(): Array<DoubleArray> {
        val grid = Array(7) { DoubleArray(24) { 0.0 } } // [day][hour] = total revenue
        val trips = getAllTrips()
        
        trips.forEach { trip ->
            if (trip.endTime != null && trip.earningsPLN > 0) {
                try {
                    val startDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(trip.startTime)
                    val calendar = Calendar.getInstance()
                    calendar.time = startDate ?: return@forEach
                    
                    val day = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> 0
                        Calendar.TUESDAY -> 1  
                        Calendar.WEDNESDAY -> 2
                        Calendar.THURSDAY -> 3
                        Calendar.FRIDAY -> 4
                        Calendar.SATURDAY -> 5
                        Calendar.SUNDAY -> 6
                        else -> 0
                    }
                    // Determine raw hour (0-23)
                    val rawHour = calendar.get(Calendar.HOUR_OF_DAY)
                    // The hour from Calendar is 0-23, which is the correct index for a 24-element array.
                    // No adjustment is needed.
                    val hourIndex = rawHour
                    // Add total earnings to grid at mapped index
                    grid[day][hourIndex] += trip.earningsPLN.toDouble()
                } catch (e: Exception) {
                    // Skip invalid trips silently
                }
            }
        }
        
        return grid
    }
    
    // Get current time for highlighting  
    fun getCurrentTime(): Pair<Int, Int> {
        val calendar = Calendar.getInstance()
        val day = when (calendar.get(Calendar.DAY_OF_WEEK)) {
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
        // Current time calculated
        return Pair(day, hour)
    }
    
    // Get current time as formatted string (same format as trips use)
    fun getCurrentTimeString(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
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
            if (_tripsCache.isNotEmpty()) {
                _tripsCache.clear()
                dataVersion++
            }
            return
        }
        fileDoc.uri.let { fileUri ->
            try {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { it.readText() }
                     if (json.isBlank()) {
                         if (_tripsCache.isNotEmpty()) {
                            _tripsCache.clear()
                            dataVersion++
                        }
                        android.util.Log.w("BoltAssist", "LOAD: Trips file from URI is blank, cache cleared.")
                        return@use
                    }
                    val tripsArray = parseTripsLenient(json)
                    
                    // Atomically update cache only after successful parse
                    _tripsCache.clear()
                    _tripsCache.addAll(tripsArray)
                    dataVersion++
                    android.util.Log.d("BoltAssist", "Loaded ${_tripsCache.size} trips from URI: $fileUri")
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to load trips from URI, cache not cleared to prevent data loss.", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Fetch public holidays for a country via Nager.Date API.
     */
    private fun fetchHolidays(year: Int) {
        try {
            val url = URL("https://date.nager.at/api/v3/PublicHolidays/$year/PL")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                // Simple data class for parsing
                data class Holiday(val date: String, val localName: String?, val name: String)
                val holidays: Array<Holiday> = gson.fromJson(json, Array<Holiday>::class.java)
                holidays.forEach { h ->
                    // store by month-day (MM-dd)
                    val md = h.date.substring(5)
                    holidayMap[md] = h.localName ?: h.name
                }
                android.util.Log.d("BoltAssist", "Fetched ${holidayMap.size} public holidays for $year")
            } else {
                android.util.Log.w("BoltAssist", "Holiday API returned ${conn.responseCode}")
            }
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to fetch holidays", e)
        }
    }

    /**
     * Get the holiday tag for a given date, if any.
     */
    fun getDayTag(date: Date): String? {
        val md = SimpleDateFormat("MM-dd", Locale.getDefault()).format(date)
        return holidayMap[md]
    }

    /**
     * Expected earnings grid, switching to holiday-based historical averages when a tag exists.
     */
    fun getExpectedGrid(): Array<DoubleArray> {
        val calendar = Calendar.getInstance()
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val today = calendar.time
        val todayTag = getDayTag(today)
        val earningsMap = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()

        _tripsCache.forEach { trip ->
            try {
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(trip.startTime)
                if (date != null) {
                    calendar.time = date
                    val week = calendar.get(Calendar.WEEK_OF_YEAR)
                    // Only past weeks
                    if (week != currentWeek) {
                        // If holiday mode, only include same tag
                        if (todayTag != null) {
                            val md = SimpleDateFormat("MM-dd", Locale.getDefault()).format(date)
                            if (md != SimpleDateFormat("MM-dd", Locale.getDefault()).format(today)) return@forEach
                        }
                        val day = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                            Calendar.MONDAY -> 0
                            Calendar.TUESDAY -> 1
                            Calendar.WEDNESDAY -> 2
                            Calendar.THURSDAY -> 3
                            Calendar.FRIDAY -> 4
                            Calendar.SATURDAY -> 5
                            Calendar.SUNDAY -> 6
                            else -> 0
                        }
                        val rawHour = calendar.get(Calendar.HOUR_OF_DAY)
                        // The hour is 0-23 from calendar, which is the correct index.
                        val hourIndex = rawHour
                        earningsMap.getOrPut(day to hourIndex) { mutableListOf() }.add(trip.earningsPLN)
                    }
                }
            } catch (_: Exception) { }
        }

        val grid = Array(7) { DoubleArray(24) { 0.0 } }
        earningsMap.forEach { (key, list) ->
            val (day, hour) = key
            grid[day][hour] = list.average()
        }
        return grid
    }

    /**
     * Simplified prediction grid - includes ALL trips including edit mode trips
     */
    fun getKalmanPredictionGrid(): Array<DoubleArray> {
        val grid = Array(7) { DoubleArray(24) { 0.0 } }
        // Use in-memory cache to ensure edit mode trips are included immediately
        val trips = _tripsCache.filter { it.endTime != null && it.earningsPLN > 0 }
        
        android.util.Log.d("BoltAssist", "Kalman prediction using ${trips.size} trips (includes edit mode)")
        
        if (trips.isEmpty()) return grid
        
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        // Simple approach: for each day/hour, find average of ALL available data
        for (day in 0..6) {
            for (hour in 0..23) {
                val earnings = mutableListOf<Double>()
                
                // Quick single-pass collection including edit trips
                for (trip in trips) {
                    try {
                        val tripDate = dateFormat.parse(trip.startTime) ?: continue
                        calendar.time = tripDate
                        
                        val tripDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                            Calendar.MONDAY -> 0
                            Calendar.TUESDAY -> 1  
                            Calendar.WEDNESDAY -> 2
                            Calendar.THURSDAY -> 3
                            Calendar.FRIDAY -> 4
                            Calendar.SATURDAY -> 5
                            Calendar.SUNDAY -> 6
                            else -> continue
                        }
                        val tripHour = calendar.get(Calendar.HOUR_OF_DAY)
                        // The hour from calendar is 0-23, which is the correct index for the grid.
                        val tripHourIndex = tripHour
                        
                        // Include all matching trips (edit mode and real trips)
                        if (tripDay == day && tripHourIndex == hour) {
                            earnings.add(trip.earningsPLN.toDouble())
                            android.util.Log.v("BoltAssist", "Found trip for day=$day hour=$hour: ${trip.earningsPLN} PLN (${trip.startStreet})")
                        }
                    } catch (e: Exception) {
                        // Skip invalid trips silently
                    }
                }
                
                // Simple prediction: average of actual data or small fallback
                grid[day][hour] = when {
                    earnings.isNotEmpty() -> {
                        val avg = earnings.average()
                        android.util.Log.v("BoltAssist", "Prediction day=$day hour=$hour: $avg PLN from ${earnings.size} trips")
                        avg
                    }
                    trips.isNotEmpty() -> {
                        // Very conservative fallback - only 10% of general average  
                        val fallback = trips.map { it.earningsPLN.toDouble() }.average() * 0.1
                        android.util.Log.v("BoltAssist", "Fallback prediction day=$day hour=$hour: $fallback PLN")
                        fallback
                    }
                    else -> 0.0
                }
                
                // Apply minimum threshold - don't show tiny predictions
                if (grid[day][hour] < 1.0) grid[day][hour] = 0.0
            }
        }
        
        return grid
    }
    
    // Note: Switched from complex Kalman filter to simpler holiday-aware historical averaging
    // This provides more intuitive and predictable results for week-to-week earnings patterns

    // ---------- Lenient JSON parser ----------
    private fun parseTripsLenient(json: String): List<TripData> {
        val valid = mutableListOf<TripData>()
        try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            // Expecting array root; if not, wrap single object
            if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                var idx = 0
                while (reader.hasNext()) {
                    try {
                        val trip: TripData = gson.fromJson(reader, TripData::class.java)
                        if (trip.startTime.isNotBlank()) valid.add(trip)
                    } catch (e: Exception) {
                        android.util.Log.w("BoltAssist", "LENIENT: skipping bad record #$idx", e)
                        reader.skipValue()
                    }
                    idx++
                }
                reader.endArray()
            } else {
                // Try single object
                try {
                    val trip: TripData = gson.fromJson(reader, TripData::class.java)
                    if (trip.startTime.isNotBlank()) valid.add(trip)
                } catch (e: Exception) {
                    android.util.Log.e("BoltAssist", "LENIENT: single object parse failed", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "LENIENT: failed to parse JSON stream", e)
        }
        return valid
    }

    /**
     * Re-open (resume) the given completed trip so that it becomes the active one again.
     * Returns the reopened instance or null if the trip couldn't be found / was already active.
     */
    fun resumeTrip(tripId: String): TripData? {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val index = _tripsCache.indexOfFirst { it.id == tripId }
        if (index == -1) return null

        val trip = _tripsCache[index]
        if (trip.endTime == null) return trip // already active

        val reopened = trip.copy(endTime = null, endLocation = null)
        _tripsCache[index] = reopened
        currentTrip = reopened
        // Update start time reference for duration calculation
        tripStartTime = try {
            dateFormat.parse(reopened.startTime)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        return reopened
    }
}

 