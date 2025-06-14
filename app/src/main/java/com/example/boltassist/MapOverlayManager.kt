package com.example.boltassist

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.*

/**
 * Enhanced overlay for displaying demand predictions as hex grid with time-based forecasting
 */
class HeatmapOverlay(
    private val streetDataManager: StreetDataManager
) : Overlay() {
    
    private val hexPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val hexStrokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }
    
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    private val timePaint = Paint().apply {
        isAntiAlias = true
        color = Color.YELLOW
        textSize = 14f
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        
        val projection = mapView.projection
        val predictions = streetDataManager.getStreetPredictions()
        
        predictions.forEach { prediction ->
            drawHexPrediction(canvas, projection, prediction)
        }
    }
    
    private fun drawHexPrediction(canvas: Canvas, projection: Projection, prediction: StreetPrediction) {
        val geoPoint = GeoPoint(prediction.centerLat, prediction.centerLng)
        val screenPoint = Point()
        projection.toPixels(geoPoint, screenPoint)
        
        // Calculate hex size based on confidence and zoom level
        val baseSize = 30f + (prediction.confidenceLevel * 20f).toFloat() // 30-50px radius
        
        // Color based on predicted earnings with time adjustment
        val timeMultiplier = when {
            prediction.travelTimeMinutes <= 5 -> 1.0 // Immediate
            prediction.travelTimeMinutes <= 15 -> 0.9 // Soon
            prediction.travelTimeMinutes <= 30 -> 0.8 // Later
            else -> 0.7 // Future
        }
        
        val adjustedEarnings = prediction.predictedEarnings * timeMultiplier
        val normalizedEarnings = (adjustedEarnings / 25.0).coerceIn(0.0, 1.0) // Normalize to 0-25 PLN range
        
        // Color gradient: red (low) -> yellow (medium) -> green (high)
        val red = (255 * (1.0 - normalizedEarnings)).toInt()
        val green = (255 * normalizedEarnings).toInt()
        val blue = 0
        val alpha = (120 + normalizedEarnings * 80).toInt() // 120-200 alpha for visibility
        
        hexPaint.color = Color.argb(alpha, red, green, blue)
        
        // Draw hexagon
        val hexPath = createHexagonPath(screenPoint.x.toFloat(), screenPoint.y.toFloat(), baseSize)
        canvas.drawPath(hexPath, hexPaint)
        canvas.drawPath(hexPath, hexStrokePaint)
        
        // Draw earnings text
        val earningsText = "${adjustedEarnings.toInt()}PLN"
        canvas.drawText(
            earningsText,
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat() + 6f,
            textPaint
        )
        
        // Draw travel time below
        val timeText = "${prediction.travelTimeMinutes}min"
        canvas.drawText(
            timeText,
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat() + 25f,
            timePaint
        )
    }
    
    private fun createHexagonPath(centerX: Float, centerY: Float, radius: Float): Path {
        val path = Path()
        for (i in 0..5) {
            val angle = (i * 60.0 - 30.0) * PI / 180.0 // Start from top
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()
            
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        return path
    }
}

/**
 * Enhanced overlay for showing driver competition as black circles with white outline
 */
class DriversOverlay : Overlay() {
    
    private val driverFillPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    
    private val driverStrokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val driverCountPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 12f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    
    private val driverLocations = mutableListOf<DriverPosition>()
    
    data class DriverPosition(
        val location: GeoPoint,
        val count: Int = 1, // Number of drivers at this position
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun updateDriverLocations(locations: List<GeoPoint>) {
        driverLocations.clear()
        
        // Group nearby drivers and count them
        val grouped = mutableMapOf<String, MutableList<GeoPoint>>()
        locations.forEach { location ->
            // Create grid key for grouping nearby drivers (100m grid)
            val gridKey = "${(location.latitude * 1000).toInt()}_${(location.longitude * 1000).toInt()}"
            grouped.getOrPut(gridKey) { mutableListOf() }.add(location)
        }
        
        // Convert groups to driver positions
        grouped.forEach { (_, group) ->
            if (group.isNotEmpty()) {
                // Calculate center of group
                val avgLat = group.map { it.latitude }.average()
                val avgLng = group.map { it.longitude }.average()
                driverLocations.add(
                    DriverPosition(
                        location = GeoPoint(avgLat, avgLng),
                        count = group.size
                    )
                )
            }
        }
    }
    
    fun addDriversFromStreetData() {
        // Get driver supply data from StreetDataManager
        val driverData = StreetDataManager.streetDataCache
            .filter { it.driverSupply > 0 }
            .filter { 
                val timestampMillis = try {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).parse(it.timestamp)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
                System.currentTimeMillis() - timestampMillis < 300_000 // Last 5 minutes
            }
        
        val locations = driverData.map { data ->
            GeoPoint(data.centerLat, data.centerLng)
        }
        
        updateDriverLocations(locations)
    }
    
    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        
        // Auto-update from street data
        addDriversFromStreetData()
        
        val projection = mapView.projection
        
        driverLocations.forEach { driverPos ->
            val screenPoint = Point()
            projection.toPixels(driverPos.location, screenPoint)
            
            // Size based on driver count
            val radius = 8f + (driverPos.count * 2f) // 8-20px radius
            
            // Draw black circle with white outline
            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, driverFillPaint)
            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, driverStrokePaint)
            
            // Draw count if more than 1 driver
            if (driverPos.count > 1) {
                canvas.drawText(
                    driverPos.count.toString(),
                    screenPoint.x.toFloat(),
                    screenPoint.y.toFloat() + 4f,
                    driverCountPaint
                )
            }
        }
    }
}

/**
 * Enhanced manager for all map overlays with better data integration
 */
object MapOverlayManager {
    private var heatmapOverlay: HeatmapOverlay? = null
    private var driversOverlay: DriversOverlay? = null
    private var overlaysVisible = true
    
    fun initializeOverlays(mapView: MapView) {
        try {
            // Clear existing overlays
            mapView.overlays.clear()
            
            // Create and add heatmap overlay (hex grid for demand predictions)
            heatmapOverlay = HeatmapOverlay(StreetDataManager)
            mapView.overlays.add(heatmapOverlay)
            
            // Create and add drivers overlay (black circles for competition)
            driversOverlay = DriversOverlay()
            mapView.overlays.add(driversOverlay)
            
            android.util.Log.d("BoltAssist", "Map overlays initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("BoltAssist", "Failed to initialize map overlays", e)
        }
    }
    
    fun updateHeatmap(mapView: MapView) {
        if (overlaysVisible) {
            heatmapOverlay?.let {
                mapView.invalidate() // Trigger redraw
            }
        }
    }
    
    fun updateDrivers(mapView: MapView, driverLocations: List<GeoPoint> = emptyList()) {
        if (overlaysVisible) {
            driversOverlay?.let { overlay ->
                if (driverLocations.isNotEmpty()) {
                    overlay.updateDriverLocations(driverLocations)
                } else {
                    // Auto-update from street data
                    overlay.addDriversFromStreetData()
                }
                mapView.invalidate()
            }
        }
    }
    
    fun toggleOverlayVisibility(mapView: MapView, visible: Boolean) {
        overlaysVisible = visible
        
        heatmapOverlay?.let { overlay ->
            when {
                visible && !mapView.overlays.contains(overlay) -> {
                    mapView.overlays.add(overlay)
                }
                !visible && mapView.overlays.contains(overlay) -> {
                    mapView.overlays.remove(overlay)
                }
                else -> {
                    // No action needed
                }
            }
        }
        
        driversOverlay?.let { overlay ->
            when {
                visible && !mapView.overlays.contains(overlay) -> {
                    mapView.overlays.add(overlay)
                }
                !visible && mapView.overlays.contains(overlay) -> {
                    mapView.overlays.remove(overlay)
                }
                else -> {
                    // No action needed
                }
            }
        }
        
        mapView.invalidate()
    }
    
    fun getOverlaySummary(): String {
        val predictions = StreetDataManager.getStreetPredictions()
        val driverCount = StreetDataManager.streetDataCache
            .filter { it.driverSupply > 0 }
            .sumOf { it.driverSupply }
        
        return if (predictions.isNotEmpty() || driverCount > 0) {
            "Overlays: ${predictions.size} demand zones, $driverCount drivers, best ${predictions.firstOrNull()?.predictedEarnings?.toInt() ?: 0}PLN"
        } else {
            "Overlays: No data available - use screen capture to add data"
        }
    }
    
    /**
     * Force refresh all overlays with latest data
     */
    fun refreshAllOverlays(mapView: MapView) {
        updateHeatmap(mapView)
        updateDrivers(mapView)
        android.util.Log.d("BoltAssist", "All overlays refreshed")
    }
    
    /**
     * Get current overlay statistics for debugging
     */
    fun getOverlayStats(): String {
        val demandPoints = StreetDataManager.streetDataCache.filter { it.passengerDemand > 0 }.size
        val supplyPoints = StreetDataManager.streetDataCache.filter { it.driverSupply > 0 }.size
        val predictions = StreetDataManager.getStreetPredictions().size
        
        return "Stats: $demandPoints demand, $supplyPoints supply, $predictions predictions"
    }
} 