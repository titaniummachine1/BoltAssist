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
    private var tripsOverlay: TripsOverlay? = null
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
            
            // Create and add trips overlay (arrow lines showing historical rides)
            tripsOverlay = TripsOverlay()
            mapView.overlays.add(tripsOverlay)
            
            // Immediately populate overlays so user sees data without delay
            refreshAllOverlays(mapView)
            
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
    
    fun updateTrips(mapView: MapView) {
        if (!overlaysVisible) return
        val overlay = tripsOverlay ?: return

        // Pick up to 17 trips closest in hour-of-day to current time
        val allTrips = TripManager.tripsCache.filter { it.startLocation != null && it.endLocation != null }
        if (allTrips.isEmpty()) return

        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        fun hourOfTrip(trip: com.example.boltassist.TripData): Int {
            return try {
                val date = dateFormat.parse(trip.startTime)
                val cal = java.util.Calendar.getInstance().apply { time = date }
                cal.get(java.util.Calendar.HOUR_OF_DAY)
            } catch (e: Exception) { 0 }
        }

        fun hourDiff(h1: Int, h2: Int): Int {
            val diff = kotlin.math.abs(h1 - h2)
            return kotlin.math.min(diff, 24 - diff)
        }

        // Cluster trips into dominant routes using 300 m grid cells
        val grid = 0.003  // ~300 m in degrees

        data class Key(val slat: Int, val slng: Int, val elat: Int, val elng: Int)

        // Use simpler approach - always include all trips but weight them by recency
        val groups = mutableMapOf<Key, MutableList<TripData>>()
        val currentTime = System.currentTimeMillis()
        
        for (trip in allTrips) {
            val sLoc = trip.startLocation ?: continue
            val eLoc = trip.endLocation ?: continue
            
            val key = Key(
                (sLoc.latitude / grid).toInt(),
                (sLoc.longitude / grid).toInt(),
                (eLoc.latitude / grid).toInt(),
                (eLoc.longitude / grid).toInt()
            )
            groups.getOrPut(key) { mutableListOf() }.add(trip)
        }

        android.util.Log.d("BoltAssist", "Trip arrows: Found ${groups.size} route groups from ${allTrips.size} total trips")

        // Current driver position (if available) to filter reachable starts
        val curLoc = StreetDataManager.streetDataCache.lastOrNull { it.passengerDemand >= 0 }?.let {
            GeoPoint(it.centerLat, it.centerLng)
        }

        // Build ArrowInfo list from grouped data with recency weighting
        val rawArrows = groups.values.mapNotNull { list ->
            if (list.isEmpty()) return@mapNotNull null
            
            // Weight by recency - newer trips are more relevant
            val weightedCount = list.sumOf { trip ->
                try {
                    val tripTime = dateFormat.parse(trip.startTime)?.time ?: 0L
                    val ageHours = (currentTime - tripTime) / (1000 * 60 * 60).toDouble()
                    // Recent trips get higher weight: full weight for <24h, declining to 0.1 at 7 days
                    val recencyWeight = kotlin.math.max(0.1, 1.0 - (ageHours / (7 * 24)))
                    recencyWeight
                } catch (e: Exception) { 0.1 }
            }
            
            val sLat = list.map { it.startLocation!!.latitude }.average()
            val sLng = list.map { it.startLocation!!.longitude }.average()
            val eLat = list.map { it.endLocation!!.latitude }.average()
            val eLng = list.map { it.endLocation!!.longitude }.average()
            ArrowInfo(GeoPoint(sLat, sLng), GeoPoint(eLat, eLng), weightedCount.toInt().coerceAtLeast(1))
        }

        // Filter by reachability (ETA <=15 min) if we have current position
        val filtered = if (curLoc != null) {
            rawArrows.filter {
                val dist = distanceMeters(curLoc, it.start)
                val etaMin = TrafficDataManager.calculateETA(dist) // uses recent speed
                etaMin <= 15.0
            }
        } else rawArrows

        val arrows = filtered.sortedByDescending { it.count }.take(17)

        overlay.updateArrows(arrows)
        mapView.invalidate()
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
        
        tripsOverlay?.let { overlay ->
            when {
                visible && !mapView.overlays.contains(overlay) -> {
                    mapView.overlays.add(overlay)
                }
                !visible && mapView.overlays.contains(overlay) -> {
                    mapView.overlays.remove(overlay)
                }
                else -> {}
            }
        }
        
        mapView.invalidate()
    }
    
    fun toggleTripsVisibility(mapView: MapView, visible: Boolean) {
        tripsOverlay?.let { overlay ->
            when {
                visible && !mapView.overlays.contains(overlay) -> {
                    mapView.overlays.add(overlay)
                    updateTrips(mapView) // Refresh data when showing
                }
                !visible && mapView.overlays.contains(overlay) -> {
                    mapView.overlays.remove(overlay)
                }
                else -> {
                    // Overlay already in correct state, just refresh if visible
                    if (visible) updateTrips(mapView)
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
        updateTrips(mapView)
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

data class ArrowInfo(val start: GeoPoint, val end: GeoPoint, val count: Int)

private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val res = FloatArray(1)
    android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
    return res[0].toDouble()
}

/**
 * Overlay that draws historical trip trajectories as arrows.
 */
class TripsOverlay : Overlay() {
    private val arrows = mutableListOf<ArrowInfo>()
    private var maxCount = 1

    private val linePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        alpha = 220
    }

    fun updateArrows(list: List<ArrowInfo>) {
        arrows.clear()
        arrows.addAll(list)
        maxCount = arrows.maxOfOrNull { it.count } ?: 1
    }

    override fun draw(canvas: android.graphics.Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return

        val projection = mapView.projection
        for (arrow in arrows) {
            val pStart = android.graphics.Point()
            val pEnd = android.graphics.Point()
            projection.toPixels(arrow.start, pStart)
            projection.toPixels(arrow.end, pEnd)

            // Line thickness & color reflect popularity
            val popularity = arrow.count.toFloat() / maxCount
            linePaint.strokeWidth = (4f + popularity * 10f)
            // Gradient from bright yellow (low) to deep red (high) for better contrast
            val red = (255 * popularity).toInt().coerceIn(0, 255)
            val green = (200 * (1f - popularity)).toInt().coerceIn(0, 200)
            val blue = 50
            linePaint.color = android.graphics.Color.rgb(red, green, blue)

            canvas.drawLine(pStart.x.toFloat(), pStart.y.toFloat(), pEnd.x.toFloat(), pEnd.y.toFloat(), linePaint)

            // Arrow head
            val angle = kotlin.math.atan2((pEnd.y - pStart.y).toDouble(), (pEnd.x - pStart.x).toDouble())
            val arrowLen = 12f + popularity * 15f
            val angle1 = angle + Math.PI / 8
            val angle2 = angle - Math.PI / 8

            val x1 = pEnd.x - arrowLen * kotlin.math.cos(angle1).toFloat()
            val y1 = pEnd.y - arrowLen * kotlin.math.sin(angle1).toFloat()
            val x2 = pEnd.x - arrowLen * kotlin.math.cos(angle2).toFloat()
            val y2 = pEnd.y - arrowLen * kotlin.math.sin(angle2).toFloat()

            canvas.drawLine(pEnd.x.toFloat(), pEnd.y.toFloat(), x1, y1, linePaint)
            canvas.drawLine(pEnd.x.toFloat(), pEnd.y.toFloat(), x2, y2, linePaint)
        }
    }
} 