package com.example.boltassist

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Custom overlay for displaying heatmaps and predictions on the map
 */
class HeatmapOverlay(
    private val streetDataManager: StreetDataManager
) : Overlay() {
    
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    
    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        
        val projection = mapView.projection
        val predictions = streetDataManager.getStreetPredictions()
        
        predictions.forEach { prediction ->
            drawPredictionMarker(canvas, projection, prediction)
        }
    }
    
    private fun drawPredictionMarker(canvas: Canvas, projection: Projection, prediction: StreetPrediction) {
        val geoPoint = GeoPoint(prediction.centerLat, prediction.centerLng)
        val screenPoint = Point()
        projection.toPixels(geoPoint, screenPoint)
        
        // Color based on predicted earnings (green = high, red = low)
        val normalizedEarnings = (prediction.predictedEarnings / 20.0).coerceIn(0.0, 1.0) // Normalize to 0-20 PLN range
        val red = (255 * (1.0 - normalizedEarnings)).toInt()
        val green = (255 * normalizedEarnings).toInt()
        val blue = 0
        
        paint.color = Color.argb(150, red, green, blue) // Semi-transparent
        
        // Draw circle with size based on confidence
        val radius = (20 + prediction.confidenceLevel * 30).toFloat() // 20-50px radius
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, paint)
        
        // Draw earnings text
        val earningsText = "${prediction.predictedEarnings.toInt()}PLN"
        canvas.drawText(
            earningsText,
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat() + 8f, // Offset for text centering
            textPaint
        )
        
        // Draw travel time below
        val timeText = "${prediction.travelTimeMinutes}min"
        textPaint.textSize = 18f
        canvas.drawText(
            timeText,
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat() + 30f,
            textPaint
        )
        textPaint.textSize = 24f // Reset
    }
}

/**
 * Overlay for showing other drivers (future feature)
 */
class DriversOverlay : Overlay() {
    
    private val driverPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLUE
        style = Paint.Style.FILL
    }
    
    private val driverLocations = mutableListOf<GeoPoint>()
    
    fun updateDriverLocations(locations: List<GeoPoint>) {
        driverLocations.clear()
        driverLocations.addAll(locations)
    }
    
    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        
        val projection = mapView.projection
        
        driverLocations.forEach { location ->
            val screenPoint = Point()
            projection.toPixels(location, screenPoint)
            
            // Draw small blue circle for each driver
            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), 8f, driverPaint)
        }
    }
}

/**
 * Manager for all map overlays
 */
object MapOverlayManager {
    private var heatmapOverlay: HeatmapOverlay? = null
    private var driversOverlay: DriversOverlay? = null
    
    fun initializeOverlays(mapView: MapView) {
        // Clear existing overlays
        mapView.overlays.clear()
        
        // Initialize street data manager if needed
        if (!StreetDataManager::class.java.declaredFields.any { it.name == "context" && it.get(StreetDataManager) != null }) {
            android.util.Log.w("BoltAssist", "StreetDataManager not initialized, heatmap overlay disabled")
            return
        }
        
        // Create and add heatmap overlay
        heatmapOverlay = HeatmapOverlay(StreetDataManager)
        mapView.overlays.add(heatmapOverlay)
        
        // Create and add drivers overlay
        driversOverlay = DriversOverlay()
        mapView.overlays.add(driversOverlay)
        
        android.util.Log.d("BoltAssist", "Map overlays initialized")
    }
    
    fun updateHeatmap(mapView: MapView) {
        heatmapOverlay?.let {
            mapView.invalidate() // Trigger redraw
        }
    }
    
    fun updateDrivers(mapView: MapView, driverLocations: List<GeoPoint>) {
        driversOverlay?.updateDriverLocations(driverLocations)
        mapView.invalidate()
    }
    
    fun toggleHeatmapVisibility(mapView: MapView, visible: Boolean) {
        heatmapOverlay?.let { overlay ->
            if (visible && !mapView.overlays.contains(overlay)) {
                mapView.overlays.add(overlay)
            } else if (!visible && mapView.overlays.contains(overlay)) {
                mapView.overlays.remove(overlay)
            }
            mapView.invalidate()
        }
    }
    
    fun getHeatmapSummary(): String {
        val predictions = StreetDataManager.getStreetPredictions()
        return if (predictions.isNotEmpty()) {
            "Heatmap: ${predictions.size} hotspots, best ${predictions.first().predictedEarnings.toInt()}PLN"
        } else {
            "Heatmap: No data available"
        }
    }
} 