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
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        TripManager.initialize(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        startLocationUpdates()
        createFloatingWindow()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val storagePathFromIntent = intent?.getStringExtra("storage_path")
        if (storagePathFromIntent != null) {
            // Use SAF tree URI for storage
            val uri = Uri.parse(storagePathFromIntent)
            TripManager.setStorageDirectoryUri(uri)
            android.util.Log.d("BoltAssist", "Using SAF storage URI: $uri")
        } else {
            // Fallback to default app-private directory
            val defaultDir = getExternalFilesDir(null)?.resolve("BoltAssist")
                ?: filesDir.resolve("BoltAssist")
            TripManager.setStorageDirectory(defaultDir)
        }
        return START_STICKY
    }
    
    private fun createFloatingWindow() {
        // Create the larger floating button with "Help" text
        floatingView = TextView(this).apply {
            text = "Help"
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
        
        moneyLayout.addView(minusButton)
        moneyLayout.addView(plusButton)
        
        layout.addView(startStopButton)
        layout.addView(moneyDisplay!!)
        layout.addView(moneyLayout)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        expandedView = layout
        
        windowManager.addView(expandedView, params)
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
            
            // Validate trip before stopping
            if (earnings <= 0) {
                android.util.Log.w("BoltAssist", "FLOATING: WARNING - Trip has 0 earnings!")
            }
            
            val completedTrip = TripManager.stopTrip(currentLocation, earnings / 10) // Convert back to PLN
            android.util.Log.d("BoltAssist", "FLOATING: Trip completed: $completedTrip")
            
            // Validate completed trip
            completedTrip?.let { trip ->
                android.util.Log.d("BoltAssist", "FLOATING: TRIP VALIDATION:")
                android.util.Log.d("BoltAssist", "  Has endTime: ${trip.endTime != null}")
                android.util.Log.d("BoltAssist", "  Duration > 0: ${trip.durationMinutes > 0}")
                android.util.Log.d("BoltAssist", "  Earnings > 0: ${trip.earningsPLN > 0}")
                
                if (trip.endTime == null || trip.durationMinutes <= 0) {
                    android.util.Log.e("BoltAssist", "FLOATING: INVALID TRIP - will be skipped in grid!")
                }
            }
            
            android.util.Log.d("BoltAssist", "FLOATING: Cache size after trip: ${TripManager.tripsCache.size}")
            isRecording = false
            // Reset earnings for next trip
            earnings = 50 // back to default 5 PLN for next trip
            // Update display if expanded view is open
            moneyDisplay?.text = "${earnings / 10.0} PLN"
        } else {
            // Start recording
            // Reset earnings to default 5 PLN
            earnings = 50
            moneyDisplay?.text = "${earnings / 10.0} PLN"
            android.util.Log.d("BoltAssist", "FLOATING: Starting new trip with location: $currentLocation and default earnings 5 PLN")
            val startedTrip = TripManager.startTrip(currentLocation)
            android.util.Log.d("BoltAssist", "FLOATING: Trip started: $startedTrip")
            isRecording = true
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager.removeView(it) }
        expandedView?.let { windowManager.removeView(it) }
    }
} 