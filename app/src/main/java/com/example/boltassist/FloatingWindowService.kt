package com.example.boltassist

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false
    private var earnings = 0
    private var isRecording = false
    private lateinit var tripManager: TripManager
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tripManager = TripManager(this)
        
        // Set up default storage
        val defaultDir = getExternalFilesDir(null)?.resolve("BoltAssist") 
            ?: filesDir.resolve("BoltAssist")
        tripManager.setStorageDirectory(defaultDir)
        
        createFloatingWindow()
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
        
        // START/STOP button (first row)
        val startStopButton = Button(this).apply {
            text = if (isRecording) "STOP" else "START"
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
                text = if (isRecording) "STOP" else "START"
                setBackgroundColor(
                    if (isRecording) 
                        ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_red_dark)
                    else 
                        ContextCompat.getColor(this@FloatingWindowService, android.R.color.holo_green_dark)
                )
            }
        }
        
        // Money display (second row)
        val moneyDisplay = TextView(this).apply {
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
            moneyDisplay.text = "${earnings / 10.0} PLN"
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
        layout.addView(moneyDisplay)
        layout.addView(moneyLayout)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        expandedView = layout
        
        // Add touch outside listener to close the expanded view
        layout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                toggleExpanded()
                true
            } else {
                false
            }
        }
        
        windowManager.addView(expandedView, params)
    }
    
    private fun toggleRecording() {
        if (isRecording) {
            // Stop recording
            tripManager.stopTrip(null, earnings / 10) // Convert back to PLN
            isRecording = false
        } else {
            // Start recording
            tripManager.startTrip(null)
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