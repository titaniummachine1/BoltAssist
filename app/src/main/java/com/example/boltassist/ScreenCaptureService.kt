package com.example.boltassist

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.example.boltassist.DemandHeatmapAnalyzer
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

class ScreenCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isCapturing = false
    private var cropRect = Rect()
    private var captureType = "demand" // "demand" or "supply"
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    
    // --- Location support for geo-referencing ---
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var currentLocation: android.location.Location? = null
    
    companion object {
        const val EXTRA_CAPTURE_TYPE = "capture_type"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        
        // Start location updates
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        captureType = intent?.getStringExtra(EXTRA_CAPTURE_TYPE) ?: "demand"
        
        android.util.Log.d("BoltAssist", "ScreenCapture started for type: $captureType")
        
        return START_NOT_STICKY
    }
    
    private fun createOverlay() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.white))
            setPadding(20, 20, 20, 20)
        }
        
        // Title and instructions
        val titleText = TextView(this).apply {
            text = "Bolt ${captureType.capitalize()} Capture"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.black))
            setPadding(0, 0, 0, 8)
        }
        
        val instructionsText = TextView(this).apply {
            text = if (captureType == "demand") {
                "Capture Bolt's demand heatmap (orange/red hexagons)\n• Take screenshot of Bolt app\n• Or pick existing image from gallery"
            } else {
                "Capture Bolt's driver supply (car icons)\n• Take screenshot of Bolt app\n• Or pick existing image from gallery"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.black))
            setPadding(0, 0, 0, 16)
        }
        
        // Take Screenshot button
        val screenshotButton = Button(this).apply {
            text = "📱 Take Screenshot"
            setOnClickListener {
                hideOverlayAndCapture()
            }
        }
        
        // Pick from Gallery button
        val galleryButton = Button(this).apply {
            text = "🖼️ Pick from Gallery"
            setOnClickListener {
                hideOverlayAndPickFromGallery()
            }
        }
        
        // Status text (initially hidden)
        val statusText = TextView(this).apply {
            text = "Processing..."
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.blue))
            visibility = View.GONE
            setPadding(0, 8, 0, 8)
        }
        
        // Close button
        val closeButton = Button(this).apply {
            text = "❌ Close"
            setOnClickListener {
                stopSelf()
            }
        }
        
        layout.addView(titleText)
        layout.addView(instructionsText)
        layout.addView(screenshotButton)
        layout.addView(galleryButton)
        layout.addView(statusText)
        layout.addView(closeButton)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        overlayView = layout
        windowManager.addView(overlayView, params)
    }
    
    private fun hideOverlayAndCapture() {
        // Hide overlay immediately for better UX
        overlayView?.visibility = View.GONE
        
        android.util.Log.d("BoltAssist", "Taking screenshot for $captureType analysis")
        
        // Use Android's built-in screenshot (requires user action)
        // For now, simulate with a delay and then process
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000) // Give user time to take screenshot manually
            
            // Show processing status
            overlayView?.visibility = View.VISIBLE
            overlayView?.findViewById<TextView>(android.R.id.text1)?.apply {
                text = "Processing screenshot..."
                visibility = View.VISIBLE
            }
            
            // Process the capture
            processCapture(null) // null means we'll simulate for now
            
            // Auto-close after processing
            delay(2000)
            stopSelf()
        }
    }
    
    private fun hideOverlayAndPickFromGallery() {
        // Hide overlay immediately
        overlayView?.visibility = View.GONE
        
        android.util.Log.d("BoltAssist", "Opening gallery picker for $captureType analysis")
        
        // For now, simulate gallery pick
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            
            // Show processing status
            overlayView?.visibility = View.VISIBLE
            overlayView?.findViewById<TextView>(android.R.id.text1)?.apply {
                text = "Processing selected image..."
                visibility = View.VISIBLE
            }
            
            // Process the selected image
            processCapture(null) // null means we'll simulate for now
            
            // Auto-close after processing
            delay(2000)
            stopSelf()
        }
    }
    
    private fun processCapture(bitmap: Bitmap?) {
        android.util.Log.d("BoltAssist", "Processing capture for $captureType analysis")
        
        // Get current GPS location
        val loc = currentLocation ?: run {
            android.util.Log.w("BoltAssist", "No location available - using simulated data")
            simulateDataExtraction()
            return
        }
        
        // Get operation range from Settings
        val prefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
        val rangeKm = prefs.getFloat("operation_range", 15.0f)
        
        if (bitmap != null) {
            // Real image processing
            if (captureType == "demand") {
                // Extract demand hotspots
                val hotspots = DemandHeatmapAnalyzer.extractHotspots(bitmap, loc, rangeKm, this)
                storeHotspots(hotspots, "demand")
            } else {
                // Extract driver supply (cars) - to be implemented
                val cars = extractDriverSupply(bitmap, loc, rangeKm)
                storeHotspots(cars, "supply")
            }
        } else {
            // Simulate data extraction for testing
            simulateDataExtraction()
        }
    }
    
    private fun extractDriverSupply(bitmap: Bitmap, location: android.location.Location, rangeKm: Float): List<Pair<Double, Double>> {
        // TODO: Implement car icon detection in Bolt app screenshots
        // For now, simulate some driver positions
        val drivers = mutableListOf<Pair<Double, Double>>()
        
        // Simulate 3-8 drivers around current location
        val driverCount = (3..8).random()
        repeat(driverCount) {
            val offsetLat = (Math.random() - 0.5) * 0.01 // ~500m radius
            val offsetLng = (Math.random() - 0.5) * 0.01
            drivers.add(
                (location.latitude + offsetLat) to (location.longitude + offsetLng)
            )
        }
        
        android.util.Log.d("BoltAssist", "Extracted $driverCount driver positions (simulated)")
        return drivers
    }
    
    private fun storeHotspots(hotspots: List<Pair<Double, Double>>, dataType: String) {
        if (hotspots.isEmpty()) {
            android.util.Log.d("BoltAssist", "No $dataType hotspots detected")
            return
        }
        
        hotspots.forEach { (lat, lng) ->
            when (dataType) {
                "demand" -> {
                    StreetDataManager.addStreetData(
                        lat = lat,
                        lng = lng,
                        passengerDemand = 1,
                        driverSupply = 0,
                        dataSource = "screen_capture_demand"
                    )
                }
                "supply" -> {
                    StreetDataManager.addStreetData(
                        lat = lat,
                        lng = lng,
                        passengerDemand = 0,
                        driverSupply = 1,
                        dataSource = "screen_capture_supply"
                    )
                }
            }
        }
        
        android.util.Log.d("BoltAssist", "Stored ${hotspots.size} $dataType hotspots from screen capture")
    }
    
    /**
     * Simulate data extraction for testing
     */
    private fun simulateDataExtraction() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Simulate processing time
                delay(1000)
                
                // Get current location or use default
                val lat = currentLocation?.latitude ?: (53.7784 + (Math.random() - 0.5) * 0.01)
                val lng = currentLocation?.longitude ?: (20.4801 + (Math.random() - 0.5) * 0.01)
                
                // Simulate extracted data based on capture type
                when (captureType) {
                    "demand" -> {
                        // Simulate 2-5 demand hotspots
                        val hotspotCount = (2..5).random()
                        repeat(hotspotCount) {
                            val offsetLat = (Math.random() - 0.5) * 0.005
                            val offsetLng = (Math.random() - 0.5) * 0.005
                            StreetDataManager.addStreetData(
                                lat = lat + offsetLat,
                                lng = lng + offsetLng,
                                passengerDemand = (1..3).random(),
                                driverSupply = 0,
                                dataSource = "screen_capture_demand_sim"
                            )
                        }
                        android.util.Log.d("BoltAssist", "Simulated $hotspotCount demand hotspots")
                    }
                    "supply" -> {
                        // Simulate 3-7 driver positions
                        val driverCount = (3..7).random()
                        repeat(driverCount) {
                            val offsetLat = (Math.random() - 0.5) * 0.008
                            val offsetLng = (Math.random() - 0.5) * 0.008
                            StreetDataManager.addStreetData(
                                lat = lat + offsetLat,
                                lng = lng + offsetLng,
                                passengerDemand = 0,
                                driverSupply = 1,
                                dataSource = "screen_capture_supply_sim"
                            )
                        }
                        android.util.Log.d("BoltAssist", "Simulated $driverCount driver positions")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    android.util.Log.d("BoltAssist", "✅ Data extraction completed for $captureType")
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to extract data", e)
            }
        }
    }
    
    private fun startLocationUpdates() {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, fine) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, coarse) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("BoltAssist", "ScreenCaptureService: no location permission")
            return
        }
        
        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            20_000L
        ).build()
        
        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(res: com.google.android.gms.location.LocationResult) {
                currentLocation = res.lastLocation ?: currentLocation
            }
        }
        
        fusedLocationClient.requestLocationUpdates(req, callback, mainLooper)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        mediaProjection?.stop()
        imageReader?.close()
    }
} 