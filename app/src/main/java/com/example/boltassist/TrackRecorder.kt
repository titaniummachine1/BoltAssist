package com.example.boltassist

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TrackRecorder(private val context: Context) {
    private val TAG = "TrackRecorder"
    private val handlerThread = HandlerThread("TrackRecorderThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val trackBuffer = mutableListOf<TrackPoint>()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var lastLocation: Location? = null
    private var isRecording = false

    data class TrackPoint(
        val ts: Long,
        val lat: Double,
        val lon: Double,
        val speed: Float
    )

    init {
        try {
            scheduleNext()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TrackRecorder: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private val locationTask = object : Runnable {
        override fun run() {
            if (isRecording) {
                try {
                    val loc = LocationHelper.getLastKnownLocation(context)
                    if (loc != null && loc.speed > 0.5) {
                    val trackPoint = TrackPoint(loc.time, loc.latitude, loc.longitude, loc.speed)
                    trackBuffer.add(trackPoint)
                    
                    // Update edge stats if we have a previous location
                    lastLocation?.let { prevLoc ->
                        val distance = loc.distanceTo(prevLoc)
                        val timeS = (loc.time - prevLoc.time) / 1000f
                        if (timeS > 0 && distance > 10) { // minimum 10m movement
                            val edgeId = MapMatcherHelper.findClosestEdge(loc.latitude, loc.longitude)
                            edgeId?.let { 
                                StatEngine.updateEdgeStats(it, distance, timeS, loc.time)
                            }
                        }
                    }
                    
                    lastLocation = loc
                    
                    if (trackBuffer.size >= 60) {
                        flushBuffer()
                    }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in location task: ${e.message}")
                }
            }
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        handler.postDelayed(locationTask, 10_000L)  // every 10 seconds
    }

    private fun flushBuffer() {
        Log.d(TAG, "Flushing ${trackBuffer.size} track points")
        trackBuffer.clear()
    }

    fun startRecording() {
        isRecording = true
        Log.d(TAG, "Started recording GPS track")
    }

    fun stopRecording() {
        isRecording = false
        if (trackBuffer.isNotEmpty()) {
            flushBuffer()
        }
        Log.d(TAG, "Stopped recording GPS track")
    }

    fun cleanUp() {
        handler.removeCallbacks(locationTask)
        handlerThread.quitSafely()
    }
} 