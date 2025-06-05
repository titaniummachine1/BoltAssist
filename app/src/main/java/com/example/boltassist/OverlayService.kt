package com.example.boltassist

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.max

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout

    private var amount = 0f
    private var isRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var currentRide: Ride? = null
    private lateinit var trackRecorder: TrackRecorder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Initialize MapMatcher and TrackRecorder
        MapMatcherHelper.init(this)
        trackRecorder = TrackRecorder(this)

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 16, 16, 16)
        }

        val startStop = Button(this).apply {
            text = "START"
            setOnClickListener {
                if (!isRunning) {
                    isRunning = true
                    text = "STOP"
                    onRideStart()
                } else {
                    isRunning = false
                    text = "START"
                    onRideStop()
                }
            }
        }
        overlayView.addView(startStop)

        val amountTv = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            text = "0.00 zł"
        }
        val plus = Button(this).apply {
            text = "+5"
            setOnClickListener {
                if (isRunning) {
                    amount += 5f
                    amountTv.text = String.format("%.2f zł", amount)
                } else {
                    Toast.makeText(context, "Click START first", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val minus = Button(this).apply {
            text = "-2.5"
            setOnClickListener {
                if (isRunning) {
                    amount = max(0f, amount - 2.5f)
                    amountTv.text = String.format("%.2f zł", amount)
                } else {
                    Toast.makeText(context, "Click START first", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(amountTv)
            addView(plus)
            addView(minus)
        }
        overlayView.addView(row)

        val kasa = Button(this).apply {
            text = "KASA"
            setOnClickListener {
                if (isRunning) {
                    onKasaClick()
                } else {
                    Toast.makeText(context, "No active ride", Toast.LENGTH_SHORT).show()
                }
            }
        }
        overlayView.addView(kasa)

        overlayView.setOnTouchListener(OverlayTouchListener())

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        if (::trackRecorder.isInitialized) {
            trackRecorder.cleanUp()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onRideStart() {
        // Get current time
        val startTs = System.currentTimeMillis()

        // Get current location
        val loc: Location? = LocationHelper.getLastKnownLocation(this)
        val pickupLat = loc?.latitude ?: 0.0
        val pickupLon = loc?.longitude ?: 0.0

        // Snap to nearest edge
        val pickupEdge = MapMatcherHelper.findClosestEdge(pickupLat, pickupLon)

        // Create new Ride object with incomplete data
        currentRide = Ride(
            startTs = startTs,
            endTs = 0L,
            amount = 0f,
            pickupLat = pickupLat,
            pickupLon = pickupLon,
            pickupEdge = pickupEdge,
            dropLat = null,
            dropLon = null,
            dropEdge = null
        )

        // Start GPS tracking
        trackRecorder.startRecording()
        
        Toast.makeText(this, "Ride started", Toast.LENGTH_SHORT).show()
    }

    private fun onRideStop() {
        // Get current time
        val endTs = System.currentTimeMillis()

        // Get current location
        val loc: Location? = LocationHelper.getLastKnownLocation(this)
        val dropLat = loc?.latitude ?: 0.0
        val dropLon = loc?.longitude ?: 0.0

        // Snap to nearest edge
        val dropEdge = MapMatcherHelper.findClosestEdge(dropLat, dropLon)

        // Finalize currentRide
        val ride = currentRide?.copy(
            endTs = endTs,
            amount = this.amount,
            dropLat = dropLat,
            dropLon = dropLon,
            dropEdge = dropEdge
        )

        if (ride != null) {
            ioScope.launch {
                // Insert into local SQLite
                val db = AppDatabase.getDatabase(this@OverlayService)
                val rowId = db.rideDao().insertRide(ride)
                // Push to Firebase
                FirebaseHelper.pushRide(ride)
            }
        }
        
        // Stop GPS tracking
        trackRecorder.stopRecording()
        
        Toast.makeText(this, String.format("Ride stopped: %.2f zł", this.amount), Toast.LENGTH_SHORT).show()
        currentRide = null
        this.amount = 0f
    }

    private fun onKasaClick() {
        if (!isRunning) {
            Toast.makeText(this, "No active ride", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Get current location for best edge analysis
        val loc: Location? = LocationHelper.getLastKnownLocation(this)
        val curLat = loc?.latitude ?: 0.0
        val curLon = loc?.longitude ?: 0.0
        
        // Find best edge within 2km radius
        StatEngine.findBestEdge(curLat, curLon, 2.0f) { advice ->
            runOnUiThread {
                if (advice != null) {
                    Toast.makeText(
                        this,
                        "Best spot: edge ${advice.edgeId}\nETA ${String.format("%.1f", advice.etaMin)} min, est. ${String.format("%.1f", advice.eph)} zł/h",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "No good spots found nearby", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    inner class OverlayTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = overlayView.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(overlayView, params)
                    return true
                }
            }
            return false
        }
    }
}

