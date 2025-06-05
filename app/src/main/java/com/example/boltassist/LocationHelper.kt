package com.example.boltassist

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

object LocationHelper {
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (prov in providers) {
            val l = lm.getLastKnownLocation(prov) ?: continue
            if (best == null || l.accuracy < best.accuracy) {
                best = l
            }
        }
        return best
    }
} 