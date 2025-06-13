package com.example.boltassist

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import kotlin.math.abs

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false
    // Earnings stored in tenths of PLN; default 5.0 PLN = 50
    private var earnings = 50
    private var isRecording = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private var moneyDisplay: TextView? = null
    private val CHANNEL_ID = "resume_ride_channel"
    private val NOTIF_ID = 1001
    private val ACTION_RESUME_YES = "com.example.boltassist.RESUME_YES"
    private val ACTION_RESUME_NO = "com.example.boltassist.RESUME_NO"
    // --- In-memory resume-trip helper ---
    private var lastEndedTripId: String? = null       // id of the most recently finished trip
    private var lastEndedTimestamp: Long = 0L         // wall-clock millis when that trip ended
    private var resumeButton: Button? = null          // lazily created in expanded menu
    private val resumeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val resumeVisibilityRunnable = object : Runnable {
        override fun run() {
            // Update visibility every second while within 60-second window
            resumeButton?.visibility = if (shouldShowResume()) View.VISIBLE else View.GONE
            if (resumeButton?.visibility == View.VISIBLE) {
                resumeHandler.postDelayed(this, 1000)
            }
        }
    }
    // lightweight persistor
    private val prefs by lazy { getSharedPreferences("FloatingService", MODE_PRIVATE) }
    
    // ----- Close target (red X at bottom-centre) -----
    private var closeTargetView: View? = null
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Initialize TripManager and ensure data is loaded
        if (!TripManager.isInitialized()) {
            TripManager.initialize(this)
            android.util.Log.d("BoltAssist", "FLOATING: TripManager initialized with ${TripManager.tripsCache.size} trips")
        } else {
            // TripManager already initialized by MainActivity, no need to reload.
            // The singleton instance will have the most up-to-date data.
            android.util.Log.d("BoltAssist", "FLOATING: TripManager already initialized, using existing instance with ${TripManager.tripsCache.size} trips")
        }
        
        // Initialize traffic tracking
        TrafficDataManager.initialize(this)
        TrafficDataManager.startTracking()
        
        android.util.Log.d("BoltAssist", "FloatingWindowService started - TripManager has ${TripManager.tripsCache.size} trips")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        startLocationUpdates()
        createFloatingWindow()
        
        // Prepare hidden close target overlay
        createCloseTarget()
        
        // Restore persisted resume/active-trip information (crash-resilience)
        restorePersistedState()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BoltAssist", "FLOATING: onStartCommand - current cache size: ${TripManager.tripsCache.size}")
        
        // Check if TripManager needs to reload data
        if (TripManager.tripsCache.isEmpty()) {
            android.util.Log.w("BoltAssist", "FLOATING: Cache is empty, forcing reload from storage")
            TripManager.reloadFromFile()
            android.util.Log.d("BoltAssist", "FLOATING: After reload - cache size: ${TripManager.tripsCache.size}")
        }
        
        when (intent?.action) {
            ACTION_RESUME_YES -> {
                val tripId = intent.getStringExtra("trip_id") ?: return START_STICKY
                val reopened = TripManager.resumeTrip(tripId)
                reopened?.let {
                    isRecording = true
                    earnings = it.earningsPLN * 10 // convert to internal tenths representation
                    moneyDisplay?.text = "${earnings / 10.0} PLN"
                }
                // dismiss notification
                (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
                return START_STICKY
            }
            ACTION_RESUME_NO -> {
                // user rejected resume – start fresh without merge
                currentLocation?.let { TripManager.startTrip(it, allowMerge = false) }
                isRecording = true
                (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
                return START_STICKY
            }
        }
        
        return START_STICKY
    }
    
    private fun createFloatingWindow() {
        // Create the larger floating button with "Trip" text
        floatingView = TextView(this).apply {
            text = "Trip"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.white))
            setBackgroundColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_blue_dark))
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            
            // Make it draggable
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val params = layoutParams as WindowManager.LayoutParams
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        // Show close target when drag starts
                        closeTargetView?.visibility = View.VISIBLE
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val params = layoutParams as WindowManager.LayoutParams
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(this, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val params = layoutParams as WindowManager.LayoutParams
                        val display = resources.displayMetrics
                        val screenWidth = display.widthPixels
                        val screenHeight = display.heightPixels
                        val buttonSize = 100 // same as layout params size in px

                        val edgeGapPx = (resources.displayMetrics.density * 5).toInt() // 5dp visual gap

                        val deltaX = abs(event.rawX - initialTouchX)
                        val deltaY = abs(event.rawY - initialTouchY)

                        val wasTap = deltaX < 10 && deltaY < 10

                        var consumed = false

                        if (!wasTap) {
                            // Check proximity to close target
                            closeTargetView?.let { target ->
                                val targetParams = target.layoutParams as WindowManager.LayoutParams
                                val targetCenterX = screenWidth / 2
                                val targetCenterY = screenHeight - targetParams.y - (target.height / 2)

                                val bubbleCenterX = params.x + buttonSize / 2
                                val bubbleCenterY = params.y + buttonSize / 2

                                val dist = kotlin.math.hypot(
                                    (bubbleCenterX - targetCenterX).toDouble(),
                                    (bubbleCenterY - targetCenterY).toDouble()
                                )

                                if (dist < buttonSize * 2.5) { // larger tolerance (~5× radius)
                                    // Dropped on X – exit
                                    stopSelf()
                                    consumed = true
                                }
                            }
                        }

                        if (!consumed) {
                            if (wasTap) {
                            toggleExpanded()
                            } else {
                                // ----- Edge-snap with small gap -----
                                params.x = if (params.x + buttonSize / 2 < screenWidth / 2) {
                                    edgeGapPx
                                } else {
                                    screenWidth - buttonSize - edgeGapPx
                                }

                                // Clamp Y inside screen bounds
                                if (params.y < 0) params.y = 0
                                if (params.y > screenHeight - buttonSize) params.y = screenHeight - buttonSize

                                windowManager.updateViewLayout(this, params)
                            }
                        }

                        // Hide close target after gesture ends
                        closeTargetView?.visibility = View.GONE
                        true
                    }
                    else -> false
                }
            }
        }
        
        val params = WindowManager.LayoutParams(
            100, // 100px width (double the original 50px)
            100, // 100px height (double the original 50px)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
        
        windowManager.addView(floatingView, params)
    }
    
    private fun toggleExpanded() {
        if (isExpanded) {
            // Hide expanded view
            expandedView?.let {
                windowManager.removeView(it)
                expandedView = null
            }
            isExpanded = false
        } else {
            // Show expanded view
            createExpandedView()
            isExpanded = true
        }
    }
    
    private fun createExpandedView() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.white))
            setPadding(20, 20, 20, 20)
        }
        
        // START/END button (first row)
        val startStopButton = Button(this).apply {
            text = if (isRecording) "END" else "START"
            textSize = 18f
            setBackgroundColor(
                if (isRecording) 
                    ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_red_dark)
                else 
                    ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_green_dark)
            )
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.white))
            
            setOnClickListener {
                toggleRecording()
                text = if (isRecording) "END" else "START"
                setBackgroundColor(
                    if (isRecording) 
                        ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_red_dark)
                    else 
                        ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_green_dark)
                )
            }
        }
        
        // Money display (second row)
        moneyDisplay = TextView(this).apply {
            text = "${earnings / 10.0} PLN"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.black))
        }
        
        // Money controls (third row)
        val moneyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        fun updateMoneyDisplay() {
            moneyDisplay?.text = "${earnings / 10.0} PLN"
            android.util.Log.d("BoltAssist", "FLOATING: Money display updated to ${earnings / 10.0} PLN")
        }
        
        val minusButton = Button(this).apply {
            text = "-"
            textSize = 20f
            setOnClickListener {
                earnings = maxOf(0, earnings - 25) // -2.5 PLN (stored as 0.1 PLN units)
                updateMoneyDisplay()
            }
        }
        
        val plusButton = Button(this).apply {
            text = "+"
            textSize = 20f
            setOnClickListener {
                earnings += 50 // +5 PLN (stored as 0.1 PLN units)
                updateMoneyDisplay()
            }
        }
        
        // Make minus and plus share space equally
        val buttonParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        moneyLayout.addView(minusButton, buttonParams)
        moneyLayout.addView(plusButton, buttonParams)
        
        // ----- Optional RESUME button (appears for 1 minute after last trip ended) -----
        resumeButton = Button(this).apply {
            text = "RESUME"
            textSize = 16f
            visibility = View.GONE // default – will be toggled dynamically
            setBackgroundColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_orange_dark))
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.white))

            setOnClickListener {
                lastEndedTripId?.let { tripId ->
                    val reopened = TripManager.resumeTrip(tripId)
                    reopened?.let { trip ->
                        // Adopt resumed state with the trip's saved earnings value
                        isRecording = true
                        earnings = trip.earningsPLN * 10
                        updateMoneyDisplay()
                        // Update START/END button to reflect active recording
                        startStopButton.text = "END"
                        startStopButton.setBackgroundColor(
                            ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_red_dark)
                        )
                        // Hide resume once used
                        lastEndedTripId = null
                        resumeButton?.visibility = View.GONE
                        resumeHandler.removeCallbacks(resumeVisibilityRunnable)
                        // Persist that we're recording again
                        prefs.edit().apply {
                            remove("last_trip_id"); remove("last_trip_time")
                            putString("active_trip_id", trip.id)
                            apply()
                        }
                    }
                }
            }
        }
        
        layout.addView(startStopButton)
        layout.addView(resumeButton!!)
        layout.addView(moneyDisplay!!)
        // Ensure money controls span full width
        layout.addView(
            moneyLayout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        
        // Position the menu next to the floating button, closer to screen center
        val floatingParams = floatingView?.layoutParams as? WindowManager.LayoutParams
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            
            val menuWidth = 250 // centralised so available to all calculations
            val edgeDpGap = (resources.displayMetrics.density * 10).toInt() // 10dp universal side gap
            
            // Position menu next to floating button, on the side closer to screen center
            if (floatingParams != null) {
                val floatingX = floatingParams.x
                val floatingY = floatingParams.y
                val floatingWidth = 100 // floating button width
                
                val bubbleCenterX = floatingX + floatingWidth / 2

                if (bubbleCenterX < screenWidth / 2) {
                    // Bubble on left half – place menu to its right
                    x = floatingX + floatingWidth + 10
                } else {
                    // Bubble on right half – place menu to its left
                    x = floatingX - menuWidth - 10 // 10px gap between bubble and menu
                    // Ensure it never hugs the screen edge
                    if (x + menuWidth > screenWidth - edgeDpGap) {
                        x = screenWidth - menuWidth - edgeDpGap
                    }
                    if (x < edgeDpGap) x = edgeDpGap // extreme corner case
                }
                
                // Align vertically with floating button
                y = floatingY
                
                if (x < edgeDpGap) x = edgeDpGap
                if (x + menuWidth > screenWidth - edgeDpGap) x = screenWidth - menuWidth - edgeDpGap
                if (y < 0) y = 10
                if (y + 200 > screenHeight) y = screenHeight - 210
            } else {
                // Fallback positioning if floating button params not available
                x = screenWidth / 2 - menuWidth / 2
                y = screenHeight / 2 - 100
            }
        }
        
        expandedView = layout
        // Close when clicking outside
        layout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                toggleExpanded()
                true
            } else false
        }
        
        windowManager.addView(expandedView, params)
        
        // Kick off visibility updates for resume button, if needed
        updateResumeButtonVisibility()
    }
    
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000 // 10 seconds
        ).build()
        
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    // Feed location to traffic tracking system
                    TrafficDataManager.processLocation(location)
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )
    }
    
    private fun toggleRecording() {
        if (isRecording) {
            // Stop recording
            android.util.Log.d("BoltAssist", "FLOATING: Ending trip with earnings: ${earnings / 10} PLN (raw: $earnings)")
            android.util.Log.d("BoltAssist", "FLOATING: Current system time: ${TripManager.getCurrentTimeString()}")
            android.util.Log.d("BoltAssist", "FLOATING: Cache size before stopping trip: ${TripManager.tripsCache.size}")
            
            // Validate trip before stopping
            if (earnings <= 0) {
                android.util.Log.w("BoltAssist", "FLOATING: WARNING - Trip has 0 earnings!")
            }
            
            // Convert tenths-of-PLN to whole PLN with proper rounding (so 75→8 PLN instead of 7)
            val roundedPln = kotlin.math.round(earnings / 10.0).toInt()
            val completedTrip = TripManager.stopTrip(currentLocation, roundedPln)
            android.util.Log.d("BoltAssist", "FLOATING: Trip completed: $completedTrip")
            
            // Validate completed trip
            completedTrip?.let { trip ->
                android.util.Log.d("BoltAssist", "FLOATING: TRIP VALIDATION:")
                android.util.Log.d("BoltAssist", "  Trip ID: ${trip.id}")
                android.util.Log.d("BoltAssist", "  Start Time: ${trip.startTime}")
                android.util.Log.d("BoltAssist", "  End Time: ${trip.endTime}")
                android.util.Log.d("BoltAssist", "  Duration: ${trip.durationMinutes} minutes")
                android.util.Log.d("BoltAssist", "  Earnings: ${trip.earningsPLN} PLN")
                android.util.Log.d("BoltAssist", "  Start Street: ${trip.startStreet}")
                android.util.Log.d("BoltAssist", "  End Street: ${trip.endStreet}")
                
                if (trip.endTime == null || trip.durationMinutes <= 0) {
                    android.util.Log.e("BoltAssist", "FLOATING: INVALID TRIP - will be skipped in grid!")
                } else {
                    android.util.Log.d("BoltAssist", "FLOATING: VALID TRIP - should appear in grid")
                }
            } ?: android.util.Log.e("BoltAssist", "FLOATING: ERROR - stopTrip returned null!")
            
            android.util.Log.d("BoltAssist", "FLOATING: Cache size after trip: ${TripManager.tripsCache.size}")
            android.util.Log.d("BoltAssist", "FLOATING: Storage info: ${TripManager.getStorageInfo()}")
            
            isRecording = false
            // Reset entry field to the dominant (most common) earnings for this hour
            earnings = computeSuggestedEarnings()
            moneyDisplay?.text = "${earnings / 10.0} PLN"
            
            // Persist state for possible resume (survives process death)
            completedTrip?.let {
                lastEndedTripId = it.id
                lastEndedTimestamp = System.currentTimeMillis()
                prefs.edit().apply {
                    remove("active_trip_id") // finished
                    putString("last_trip_id", lastEndedTripId)
                    putLong("last_trip_time", lastEndedTimestamp)
                    apply()
                }
                updateResumeButtonVisibility()
            }
        } else {
            // Check if we should offer to resume previous trip instead of auto-merging
            currentLocation?.let { loc ->
                val candidate = TripManager.tripsCache
                    .filter { it.endTime != null && it.endLocation != null }
                    .maxByOrNull { it.endTime!! }

                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                val nowMillis = System.currentTimeMillis()
                val shouldPrompt = candidate?.let { trip ->
                    try {
                        val endMillis = dateFormat.parse(trip.endTime!!)?.time ?: return@let false
                        val timeDiff = nowMillis - endMillis
                        if (timeDiff > 60_000) return@let false
                        val results = FloatArray(1)
                        trip.endLocation?.let { endLoc ->
                            android.location.Location.distanceBetween(
                                endLoc.latitude,
                                endLoc.longitude,
                                loc.latitude,
                                loc.longitude,
                                results
                            )
                        }
                        results[0] <= 50f
                    } catch (_: Exception) { false }
                } ?: false

                if (shouldPrompt && candidate != null) {
                    showResumeNotification(candidate)
                    // Wait for user decision; do NOT start a new trip yet
                    return
                }
            }

            android.util.Log.d("BoltAssist", "FLOATING: Starting new trip with location: $currentLocation")
            android.util.Log.d("BoltAssist", "FLOATING: Current system time: ${TripManager.getCurrentTimeString()}")
            android.util.Log.d("BoltAssist", "FLOATING: Cache size before starting trip: ${TripManager.tripsCache.size}")
            
            val startedTrip = TripManager.startTrip(currentLocation, allowMerge = false)
            // Persist active-trip id for crash recovery
            prefs.edit().putString("active_trip_id", startedTrip.id).apply()
            android.util.Log.d("BoltAssist", "FLOATING: Trip started: $startedTrip")
            isRecording = true
        }
    }
    
    /**
     * Creates (if needed) the notification channel and shows a resume-ride notification.
     */
    private fun showResumeNotification(trip: TripData) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Resume Ride", NotificationManager.IMPORTANCE_HIGH)
            mgr.createNotificationChannel(channel)
        }

        val yesIntent = Intent(this, FloatingWindowService::class.java).apply {
            action = ACTION_RESUME_YES
            putExtra("trip_id", trip.id)
        }
        val noIntent = Intent(this, FloatingWindowService::class.java).apply { action = ACTION_RESUME_NO }

        val yesPending = PendingIntent.getService(
            this, 2001, yesIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val noPending = PendingIntent.getService(
            this, 2002, noIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Resume prior ride?")
            .setContentText("You stopped less than a minute ago. Continue the same trip?")
            .setAutoCancel(true)
            .addAction(android.R.drawable.checkbox_on_background, "Yes", yesPending)
            .addAction(android.R.drawable.ic_delete, "No", noPending)

        mgr.notify(NOTIF_ID, builder.build())
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        // Ensure any recording trip is properly ended before the service stops
        if (isRecording) {
            android.util.Log.d("BoltAssist", "FLOATING: Service destroying – auto-ending active trip.")
            TripManager.stopTrip(currentLocation, earnings / 10)
            isRecording = false
        }

        super.onDestroy()
        TrafficDataManager.stopTracking()
        floatingView?.let { windowManager.removeView(it) }
        expandedView?.let { windowManager.removeView(it) }
    }
    
    /** Determines if the RESUME button should be visible. */
    private fun shouldShowResume(): Boolean {
        val id = lastEndedTripId ?: return false
        // Must still exist & still be within 60-second window
        val withinWindow = (System.currentTimeMillis() - lastEndedTimestamp) < 60_000
        val stillCompleted = TripManager.tripsCache.any { it.id == id && it.endTime != null }
        return withinWindow && stillCompleted
    }

    private fun updateResumeButtonVisibility() {
        resumeButton?.visibility = if (shouldShowResume()) View.VISIBLE else View.GONE
        if (resumeButton?.visibility == View.VISIBLE) {
            resumeHandler.removeCallbacks(resumeVisibilityRunnable)
            resumeHandler.post(resumeVisibilityRunnable)
        } else {
            resumeHandler.removeCallbacks(resumeVisibilityRunnable)
        }
    }

    /**
     * Calculate a default earning suggestion for the next trip based on the **mode** (dominanta)
     * of the most recent 10 trips to reduce skew from outliers. If no data exists it falls back to
     * 10 PLN. Returned value is in
     * 0.1-PLN units (internal representation).
     */
    private fun computeSuggestedEarnings(): Int {
        // Consider the most recent 10 completed trips (with earnings > 0 PLN)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        val recent = TripManager.tripsCache
            .filter { it.endTime != null && it.earningsPLN > 0 }
            .sortedByDescending { it.endTime ?: it.startTime }
            .take(10)

        if (recent.isEmpty()) return 100 // 10 PLN default (0.1 units)

        val modePln = recent.groupingBy { it.earningsPLN }.eachCount()
            .entries
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })
            ?.key ?: 10

        return modePln * 10 // convert PLN -> tenths
    }

    /**
     * Reads SharedPreferences to restore any in-flight or recently-ended trips so the
     * service behaves as if it had never been killed.
     */
    private fun restorePersistedState() {
        // Unfinished active trip? -> auto-resume recording
        prefs.getString("active_trip_id", null)?.let { activeId ->
            TripManager.resumeTrip(activeId)?.let { trip ->
                isRecording = true
                earnings = trip.earningsPLN * 10
            } ?: prefs.edit().remove("active_trip_id").apply() // clean up stale id
        }

        // Recently ended trip – show RESUME if within 60 s
        val lastId = prefs.getString("last_trip_id", null)
        val lastTime = prefs.getLong("last_trip_time", 0L)
        if (lastId != null && System.currentTimeMillis() - lastTime < 60_000) {
            lastEndedTripId = lastId
            lastEndedTimestamp = lastTime
        } else {
            prefs.edit().remove("last_trip_id").remove("last_trip_time").apply()
        }
    }

    /** Creates the bottom-centre red X target (initially hidden). */
    private fun createCloseTarget() {
        val sizePx = (resources.displayMetrics.density * 80).toInt() // 80dp circle
        closeTargetView = TextView(this).apply {
            text = "✕"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, android.R.color.white))
            background = ContextCompat.getDrawable(this@FloatingWindowService, android.R.drawable.presence_busy)
            // make it look like a red circle
            background?.setTint(ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_red_dark))
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.density * 40).toInt() // 40dp margin from bottom
        }

        windowManager.addView(closeTargetView, params)
    }
} 