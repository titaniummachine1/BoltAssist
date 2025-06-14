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

class ScreenCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isCapturing = false
    private var cropRect = Rect()
    private var captureType = "demand" // "demand" or "supply"
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    
    companion object {
        const val EXTRA_CAPTURE_TYPE = "capture_type"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        captureType = intent?.getStringExtra(EXTRA_CAPTURE_TYPE) ?: "demand"
        
        // TODO: Initialize MediaProjection with result from permission request
        // For now, we'll skip the unused variables since we're simulating data extraction
        
        android.util.Log.d("BoltAssist", "ScreenCapture started for type: $captureType")
        
        return START_NOT_STICKY
    }
    
    private fun createOverlay() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.white))
            setPadding(20, 20, 20, 20)
        }
        
        // Instructions text
        val instructionsText = TextView(this).apply {
            text = "Screen Capture Mode\n• Position crop area over Bolt app\n• Tap 'Capture' when ready\n• Data will be extracted automatically"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ScreenCaptureService, android.R.color.black))
            setPadding(0, 0, 0, 16)
        }
        
        // Capture button
        val captureButton = Button(this).apply {
            text = "Capture Screen"
            setOnClickListener {
                captureScreen()
            }
        }
        
        // Close button
        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener {
                stopSelf()
            }
        }
        
        layout.addView(instructionsText)
        layout.addView(captureButton)
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
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }
        
        overlayView = layout
        windowManager.addView(overlayView, params)
    }
    
    private fun captureScreen() {
        android.util.Log.d("BoltAssist", "Capturing screen for $captureType analysis")
        
        // TODO: Implement actual screen capture using MediaProjection
        // For now, simulate data extraction
        simulateDataExtraction()
    }
    
    /**
     * Simulate data extraction from Bolt app screenshots
     * In the future, this would use OCR or image analysis
     */
    private fun simulateDataExtraction() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Simulate processing time
                delay(1000)
                
                // Get current location (would be from actual GPS)
                val lat = 53.7784 + (Math.random() - 0.5) * 0.01 // Random location near Olsztyn
                val lng = 20.4801 + (Math.random() - 0.5) * 0.01
                
                // Simulate extracted data
                when (captureType) {
                    "demand" -> {
                        // Simulate passenger demand count
                        val passengerCount = (1..8).random()
                        StreetDataManager.addStreetData(
                            lat = lat,
                            lng = lng,
                            passengerDemand = passengerCount,
                            driverSupply = 0,
                            dataSource = "screen_capture"
                        )
                        android.util.Log.d("BoltAssist", "Extracted passenger demand: $passengerCount")
                    }
                    "supply" -> {
                        // Simulate driver supply count
                        val driverCount = (1..5).random()
                        StreetDataManager.addStreetData(
                            lat = lat,
                            lng = lng,
                            passengerDemand = 0,
                            driverSupply = driverCount,
                            dataSource = "screen_capture"
                        )
                        android.util.Log.d("BoltAssist", "Extracted driver supply: $driverCount")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    android.util.Log.d("BoltAssist", "Data extraction completed for $captureType")
                    // Could show a toast or update UI here
                }
            } catch (e: Exception) {
                android.util.Log.e("BoltAssist", "Failed to extract data", e)
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        mediaProjection?.stop()
        imageReader?.close()
    }
} 