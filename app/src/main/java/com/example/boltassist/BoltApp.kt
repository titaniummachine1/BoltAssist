package com.example.boltassist

import android.app.Application
import android.content.Context

class BoltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
    
    companion object {
        var appContext: Context? = null
    }
} 