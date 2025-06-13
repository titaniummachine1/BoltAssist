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
                        // Only toggle if it was a tap, not a drag
                        val deltaX = Math.abs(event.rawX - initialTouchX)
                        val deltaY = Math.abs(event.rawY - initialTouchY)
                        if (deltaX < 10 && deltaY < 10) {
                            toggleExpanded()
                        }
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
                        // Reset entry field to a sensible default (dominant value for this hour)
                        earnings = computeSuggestedEarnings()
                        moneyDisplay?.text = "${earnings / 10.0} PLN"
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
            
            // Position menu next to floating button, on the side closer to screen center
            if (floatingParams != null) {
                val floatingX = floatingParams.x
                val floatingY = floatingParams.y
                val floatingWidth = 100 // floating button width
                
                // Determine which side is closer to center
                val distanceFromLeft = floatingX
                val distanceFromRight = screenWidth - (floatingX + floatingWidth)
                
                if (distanceFromLeft < distanceFromRight) {
                    // Floating button is on left side, show menu to the right
                    x = floatingX + floatingWidth + 10
                } else {
                    // Floating button is on right side, show menu to the left
                    x = floatingX - 250 // approximate menu width
                }
                
                // Align vertically with floating button
                y = floatingY
                
                // Ensure menu doesn't go off screen
                if (x < 0) x = 10
                if (x + 250 > screenWidth) x = screenWidth - 260
                if (y < 0) y = 10
                if (y + 200 > screenHeight) y = screenHeight - 210
            } else {
                // Fallback positioning if floating button params not available
                x = screenWidth / 2 - 125
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
        // Exit button to stop the floating service
        val exitButton = Button(this).apply {
            text = "Exit"
            textSize = 16f
            setOnClickListener { stopSelf() }
        }
        layout.addView(
            exitButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 16 }
        )
        
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
            // Preserve the just-ended trip earnings so user can tweak before resuming
            earnings = roundedPln * 10
            moneyDisplay?.text = "${earnings / 10.0} PLN"
            
            // Store reference for possible resume and start countdown
            completedTrip?.let {
                lastEndedTripId = it.id
                lastEndedTimestamp = System.currentTimeMillis()
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
                        android.location.Location.distanceBetween(
                            trip.endLocation!!.latitude,
                            trip.endLocation!!.longitude,
                            loc.latitude,
                            loc.longitude,
                            results
                        )
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
     * Calculate a default earning suggestion for the next trip. Uses the arithmetic
     * mean of all completed trips whose *start* time falls within the current hour.
     * If no data, returns 10 PLN. Value returned is in 0.1-PLN units.
     */
    private fun computeSuggestedEarnings(): Int {
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        val matching = TripManager.tripsCache.filter { it.endTime != null && it.earningsPLN > 0 }.mapNotNull { trip ->
            try {
                val date = dateFormat.parse(trip.startTime)
                calendar.time = date
                val h = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                if (h == currentHour) trip.earningsPLN else null
            } catch (_: Exception) { null }
        }

        val avg = matching.takeIf { it.isNotEmpty() }?.average() ?: 10.0
        val floored = kotlin.math.floor(avg).toInt().coerceAtLeast(1)
        return floored * 10 // convert PLN -> tenths
    }
} 