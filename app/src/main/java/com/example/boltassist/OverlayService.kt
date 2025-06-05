package com.example.boltassist

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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
import kotlin.math.max

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout

    private var amount = 0f
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onRideStart() {
        Toast.makeText(this, "Ride started", Toast.LENGTH_SHORT).show()
    }

    private fun onRideStop() {
        Toast.makeText(this, String.format("Ride stopped: %.2f zł", amount), Toast.LENGTH_SHORT).show()
        amount = 0f
    }

    private fun onKasaClick() {
        Toast.makeText(this, "KASA clicked", Toast.LENGTH_SHORT).show()
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

