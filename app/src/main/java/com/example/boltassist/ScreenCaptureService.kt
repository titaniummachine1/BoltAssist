package com.example.boltassist

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.InputStream

class ScreenCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var captureType = "demand" // "demand" or "supply"
    
    // --- Location support for geo-referencing ---
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var currentLocation: android.location.Location? = null
    
    companion object {
        const val EXTRA_CAPTURE_TYPE = "capture_type"
        const val ACTION_IMAGE_SELECTED = "com.example.boltassist.IMAGE_SELECTED"
        const val EXTRA_IMAGE_URI = "image_uri"
    }
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Start location updates
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()
        
        createOverlay()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        captureType = intent?.getStringExtra(EXTRA_CAPTURE_TYPE) ?: "demand"
        
        when (intent?.action) {
            ACTION_IMAGE_SELECTED -> {
                val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
                }
                if (imageUri != null) {
                    processSelectedImage(imageUri)
                } else {
                    android.util.Log.e("BoltAssist", "No image URI received")
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                android.util.Log.d("BoltAssist", "ScreenCapture started for type: $captureType")
                updateOverlayForCaptureType()
            }
        }
        
        return START_NOT_STICKY
    }
    
    private fun createOverlay() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.white))
            setPadding(20, 20, 20, 20)
        }
        
        // Title
        val titleText = TextView(this).apply {
            id = android.R.id.title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.black))
            setPadding(0, 0, 0, 8)
        }
        
        // Instructions
        val instructionsText = TextView(this).apply {
            id = android.R.id.text1
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.black))
            setPadding(0, 0, 0, 16)
        }
        
        // Pick from Gallery button
        val galleryButton = Button(this).apply {
            id = android.R.id.button1
            text = "🖼️ Select Image from Storage"
            setOnClickListener {
                openImagePicker()
            }
        }
        
        // Status text (initially hidden)
        val statusText = TextView(this).apply {
            id = android.R.id.text2
            text = "Processing..."
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.holo_blue_dark))
            visibility = View.GONE
            setPadding(0, 8, 0, 8)
        }
        
        // Close button
        val closeButton = Button(this).apply {
            id = android.R.id.button2
            text = "❌ Close"
            setOnClickListener {
                stopSelf()
            }
        }
        
        layout.addView(titleText)
        layout.addView(instructionsText)
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
    
    private fun updateOverlayForCaptureType() {
        overlayView?.findViewById<TextView>(android.R.id.title)?.text = 
            "Bolt ${captureType.replaceFirstChar { it.uppercase() }} Analysis"
        
        overlayView?.findViewById<TextView>(android.R.id.text1)?.text = 
            if (captureType == "demand") {
                "Select a screenshot of Bolt's demand heatmap showing orange/red hexagons indicating passenger demand areas."
            } else {
                "Select a screenshot of Bolt's driver view showing car icons representing other drivers in the area."
            }
    }
    
    private fun openImagePicker() {
        try {
            // Start MainActivity to handle the image picker
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                action = "PICK_IMAGE_FOR_ANALYSIS"
                putExtra(EXTRA_CAPTURE_TYPE, captureType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            
            startActivity(mainIntent)
            
            // Hide overlay while user picks image
            overlayView?.visibility = View.GONE
            
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to open image picker", e)
            showStatus("Failed to open image picker")
        }
    }
    
    private fun processSelectedImage(imageUri: Uri) {
        showStatus("Processing selected image...")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Load bitmap from URI
                val bitmap = loadBitmapFromUri(imageUri)
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        showStatus("Failed to load image")
                        delay(2000)
                        stopSelf()
                    }
                    return@launch
                }
                
                android.util.Log.d("BoltAssist", "Loaded image: ${bitmap.width}x${bitmap.height}")
                
                // Get current location
                val location = currentLocation
                if (location == null) {
                    android.util.Log.w("BoltAssist", "No location available for geo-referencing")
                    withContext(Dispatchers.Main) {
                        showStatus("No GPS location available")
                        delay(2000)
                        stopSelf()
                    }
                    return@launch
                }
                
                // Get operation range from settings
                val prefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
                val rangeKm = prefs.getFloat("operation_range", 15.0f)
                
                // Process the image based on capture type
                val hotspots = when (captureType) {
                    "demand" -> {
                        android.util.Log.d("BoltAssist", "Analyzing demand heatmap...")
                        DemandHeatmapAnalyzer.extractHotspots(bitmap, location, rangeKm, this@ScreenCaptureService)
                    }
                    "supply" -> {
                        android.util.Log.d("BoltAssist", "Analyzing driver supply...")
                        // TODO: Implement driver supply analysis
                        emptyList<Pair<Double, Double>>()
                    }
                    else -> emptyList()
                }
                
                // Store the extracted data
                storeHotspots(hotspots, captureType)
                
                withContext(Dispatchers.Main) {
                    showStatus("✅ Analysis complete: ${hotspots.size} points extracted")
                    delay(3000)
                    stopSelf()
                }
                
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to process image", e)
                withContext(Dispatchers.Main) {
                    showStatus("Error processing image: ${e.message}")
                    delay(3000)
                    stopSelf()
                }
            }
        }
    }
    
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to load bitmap from URI", e)
            null
        }
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
                        dataSource = "image_analysis_demand"
                    )
                }
                "supply" -> {
                    StreetDataManager.addStreetData(
                        lat = lat,
                        lng = lng,
                        passengerDemand = 0,
                        driverSupply = 1,
                        dataSource = "image_analysis_supply"
                    )
                }
            }
        }
        
        android.util.Log.d("BoltAssist", "Stored ${hotspots.size} $dataType hotspots from image analysis")
    }
    
    private fun showStatus(message: String) {
        overlayView?.visibility = View.VISIBLE
        overlayView?.findViewById<TextView>(android.R.id.text2)?.apply {
            text = message
            visibility = View.VISIBLE
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
    }
} 