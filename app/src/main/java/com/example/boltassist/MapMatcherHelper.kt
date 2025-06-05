package com.example.boltassist

import android.content.Context
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

object MapMatcherHelper {
    private const val TAG = "MapMatcherHelper"
    
    fun init(context: Context) {
        // TODO: Initialize GraphHopper offline routing
        // For now, just log that initialization was called
        Log.d(TAG, "MapMatcher initialization called - GraphHopper integration pending")
    }

    fun findClosestEdge(lat: Double, lon: Double): Int? {
        // TODO: Implement GraphHopper edge lookup
        // For now, return a mock edge ID
        return (lat * 1000 + lon * 1000).toInt()
    }
    
    fun getEdgeStartLatLng(edgeId: Int): Pair<Double, Double> {
        // TODO: Implement actual edge coordinate lookup
        // For now, return mock coordinates based on edge ID
        return Pair(53.7784 + (edgeId % 100) * 0.001, 20.4801 + (edgeId % 100) * 0.001)
    }
    
    fun computeEta(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        dayType: String, slot: Int
    ): Float {
        // TODO: Implement actual routing with edge_stats
        // For now, return simple distance-based ETA
        val distanceKm = sqrt(
            (toLat - fromLat).pow(2.0) + 
            (toLon - fromLon).pow(2.0)
        ) * 111.0  // rough km conversion
        return (distanceKm / 15.0).toFloat() * 60  // assume 15 km/h, return minutes
    }
} 