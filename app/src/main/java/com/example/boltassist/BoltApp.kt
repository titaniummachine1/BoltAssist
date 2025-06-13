package com.example.boltassist

import android.app.Application
import org.osmdroid.config.Configuration

class BoltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize osmdroid configuration
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
    }
} 