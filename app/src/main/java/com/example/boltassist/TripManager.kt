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
        // Use EXACT same date format as test data
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(Date())
        
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
        
        // Use EXACT same date format as test data
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val endTime = dateFormat.format(Date())
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
        
        // Completed trip ready
        
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
        // Add to in-memory cache for UI (immediate real-time update)
        _tripsCache.add(trip)
        
        // Force immediate save to files for persistence
        try {
            if (storageTreeUri != null) {
                saveTripsToUri()
            } else if (storageDirectory != null) {
                saveAllTripsToFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save trip immediately", e)
        }
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
        // Clear any cached trips so removing the file resets the grid
        _tripsCache.clear()
        
        if (!file.exists()) {
            android.util.Log.d("BoltAssist", "No trips file found at: ${file.absolutePath}")
            return
        }
        
        try {
            val json = file.readText()
            val tripsArray = gson.fromJson(json, Array<TripData>::class.java)
            // Load into in-memory cache, filtering out any test trips (created with Test Street)
            _tripsCache.addAll(
                tripsArray.filterNot { it.startStreet == "Test Street" && it.endStreet == "Test Street" }
            )
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
        // Generate some test trips for today at different hours
        val testTrips = listOf(
            createTestTrip(8, 30, 25), // 8 AM, 30 minutes, 25 PLN
            createTestTrip(10, 45, 40), // 10 AM, 45 minutes, 40 PLN  
            createTestTrip(14, 60, 80), // 2 PM, 60 minutes, 80 PLN
            createTestTrip(18, 25, 15), // 6 PM, 25 minutes, 15 PLN
        )
        
        _tripsCache.addAll(testTrips)
        
        // Save test data to files
        if (storageTreeUri != null) {
            saveTripsToUri()
        } else if (storageDirectory != null) {
            saveAllTripsToFile()
        }
    }
    
    fun generateKalmanTestData() {
        // Clear existing cache first
        _tripsCache.clear()
        
        // Generate historical data for multiple weeks to test predictions
        val calendar = Calendar.getInstance()
        val today = calendar.time
        
        // Generate data for past 4 weeks with realistic patterns
        for (week in 1..4) {
            calendar.time = today
            calendar.add(Calendar.WEEK_OF_YEAR, -week)
            
            for (day in 0..6) {
                calendar.set(Calendar.DAY_OF_WEEK, when (day) {
                    0 -> Calendar.MONDAY
                    1 -> Calendar.TUESDAY
                    2 -> Calendar.WEDNESDAY
                    3 -> Calendar.THURSDAY
                    4 -> Calendar.FRIDAY
                    5 -> Calendar.SATURDAY
                    6 -> Calendar.SUNDAY
                    else -> Calendar.MONDAY
                })
                
                val isWeekend = day >= 5
                val hourPattern = if (isWeekend) {
                    // Weekend: more spread out, peak at noon
                    listOf(10 to 15, 11 to 20, 12 to 35, 13 to 30, 14 to 25, 18 to 20, 19 to 25, 20 to 30)
                } else {
                    // Weekday: morning and evening peaks
                    listOf(7 to 25, 8 to 40, 9 to 30, 17 to 35, 18 to 45, 19 to 25, 22 to 15)
                }
                
                hourPattern.forEach { (hour, baseEarnings) ->
                    val variance = (-5..15).random()
                    val earnings = maxOf(5, baseEarnings + variance)
                    val duration = (15..90).random()
                    
                    val testTrip = createTestTripForWeek(calendar, hour, duration, earnings)
                    _tripsCache.add(testTrip)
                }
            }
        }
        
        // Save data
        if (storageTreeUri != null) {
            saveTripsToUri()
        } else if (storageDirectory != null) {
            saveAllTripsToFile()
        }
    }
    
    private fun createTestTripForWeek(calendar: Calendar, hour: Int, durationMinutes: Int, earningsPLN: Int): TripData {
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, (0..59).random())
        calendar.set(Calendar.SECOND, 0)
        
        val startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(calendar.time)
        
        calendar.add(Calendar.MINUTE, durationMinutes)
        val endTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(calendar.time)
        
        return TripData(
            id = "${System.currentTimeMillis()}_${hour}_${calendar.timeInMillis}",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes,
            earningsPLN = earningsPLN,
            startLocation = LocationData(52.2297 + Math.random() * 0.1, 21.0122 + Math.random() * 0.1),
            endLocation = LocationData(52.2297 + Math.random() * 0.1, 21.0122 + Math.random() * 0.1),
            startStreet = "Test Street Historical",
            endStreet = "Test Street Historical"
        )
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
        
        // Save empty database to file
        try {
            if (storageTreeUri != null) {
                saveTripsToUri()
            } else if (storageDirectory != null) {
                saveAllTripsToFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to save empty database", e)
        }
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
                    // Determine raw hour (0-23) and map to 0-based index (header labels 1-24)
                    val rawHour = calendar.get(Calendar.HOUR_OF_DAY)
                    val hourIndex = (rawHour - 1 + 24) % 24
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
        // Clear any cached trips so removing stored URI file resets the grid
        _tripsCache.clear()
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
                    // Load into in-memory cache, filtering out test trips
                    _tripsCache.addAll(
                        tripsArray.filterNot { it.startStreet == "Test Street" && it.endStreet == "Test Street" }
                    )
                    android.util.Log.d("BoltAssist", "Loaded ${tripsCache.size} trips from URI: $fileUri")
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to load trips from URI: $fileUri", e)
                e.printStackTrace()
                _tripsCache.clear()
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
                        val hourIndex = (rawHour - 1 + 24) % 24
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
                        val tripHourIndex = (tripHour - 1 + 24) % 24
                        
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
}

 