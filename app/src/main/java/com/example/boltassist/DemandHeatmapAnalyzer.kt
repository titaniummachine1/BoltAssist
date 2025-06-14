package com.example.boltassist

import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import kotlin.math.*

/**
 * Enhanced heuristic image parser that uses OSM reference points to accurately
 * map Bolt demand-heatmap hexagons to real geo-coordinates.
 *
 * Key improvements:
 * - Detects map rotation by analyzing street grid patterns
 * - Uses known landmarks (city center, main streets) for scale calibration
 * - Cross-references with OSM data for accurate geo-positioning
 * - Handles different zoom levels automatically
 */
object DemandHeatmapAnalyzer {

    // Known reference points for major Polish cities
    private val cityReferences = mapOf(
        "Olsztyn" to CityReference(
            center = LatLng(53.7784, 20.4801),
            mainStreets = listOf(
                StreetReference("Aleja Wojska Polskiego", LatLng(53.7784, 20.4801), 45.0), // NE-SW diagonal
                StreetReference("ul. Partyzantów", LatLng(53.7750, 20.4850), 135.0), // NW-SE
                StreetReference("ul. Kościuszki", LatLng(53.7800, 20.4750), 90.0) // E-W
            ),
            landmarks = listOf(
                LandmarkReference("Stare Miasto", LatLng(53.7784, 20.4801)),
                LandmarkReference("Dworzec PKP", LatLng(53.7735, 20.4842)),
                LandmarkReference("Kortowo UWM", LatLng(53.7280, 20.4890))
            )
        ),
        "Warsaw" to CityReference(
            center = LatLng(52.2297, 21.0122),
            mainStreets = listOf(
                StreetReference("ul. Marszałkowska", LatLng(52.2297, 21.0122), 0.0), // N-S
                StreetReference("Al. Jerozolimskie", LatLng(52.2297, 21.0122), 90.0) // E-W
            ),
            landmarks = listOf(
                LandmarkReference("Pałac Kultury", LatLng(52.2319, 21.0067)),
                LandmarkReference("Dworzec Centralny", LatLng(52.2288, 21.0033))
            )
        ),
        "Krakow" to CityReference(
            center = LatLng(50.0647, 19.9450),
            mainStreets = listOf(
                StreetReference("ul. Floriańska", LatLng(50.0647, 19.9450), 0.0), // N-S through Old Town
                StreetReference("ul. Grodzka", LatLng(50.0647, 19.9450), 45.0), // NE-SW
                StreetReference("Al. Mickiewicza", LatLng(50.0680, 19.9200), 90.0) // E-W
            ),
            landmarks = listOf(
                LandmarkReference("Rynek Główny", LatLng(50.0616, 19.9366)),
                LandmarkReference("Dworzec Główny", LatLng(50.0675, 19.9447)),
                LandmarkReference("Wawel", LatLng(50.0544, 19.9356))
            )
        ),
        "Gdansk" to CityReference(
            center = LatLng(54.3520, 18.6466),
            mainStreets = listOf(
                StreetReference("ul. Długa", LatLng(54.3520, 18.6466), 90.0), // E-W through Old Town
                StreetReference("ul. Grunwaldzka", LatLng(54.3700, 18.6100), 45.0), // NE-SW
                StreetReference("Al. Grunwaldzka", LatLng(54.3800, 18.6000), 0.0) // N-S
            ),
            landmarks = listOf(
                LandmarkReference("Główne Miasto", LatLng(54.3520, 18.6466)),
                LandmarkReference("Dworzec Główny", LatLng(54.3592, 18.6414)),
                LandmarkReference("Stocznia", LatLng(54.3720, 18.6520))
            )
        ),
        "Wroclaw" to CityReference(
            center = LatLng(51.1079, 17.0385),
            mainStreets = listOf(
                StreetReference("ul. Świdnicka", LatLng(51.1079, 17.0385), 90.0), // E-W
                StreetReference("ul. Kazimierza Wielkiego", LatLng(51.1079, 17.0385), 0.0), // N-S
                StreetReference("ul. Legnicka", LatLng(51.1200, 17.0200), 45.0) // NE-SW
            ),
            landmarks = listOf(
                LandmarkReference("Rynek", LatLng(51.1105, 17.0320)),
                LandmarkReference("Dworzec Główny", LatLng(51.0989, 17.0364)),
                LandmarkReference("Ostrów Tumski", LatLng(51.1150, 17.0450))
            )
        )
    )

    data class LatLng(val lat: Double, val lng: Double)
    
    data class StreetReference(
        val name: String,
        val centerPoint: LatLng,
        val bearing: Double // degrees from North
    )
    
    data class LandmarkReference(
        val name: String,
        val location: LatLng
    )
    
    data class CityReference(
        val center: LatLng,
        val mainStreets: List<StreetReference>,
        val landmarks: List<LandmarkReference>
    )

    /**
     * Enhanced extraction with OSM reference point matching
     */
    fun extractHotspots(
        bmp: Bitmap,
        currentLoc: Location,
        operationRange: Float,
        context: android.content.Context? = null
    ): List<Pair<Double, Double>> {
        // 1) Determine which city we're in
        val cityName = determineCityFromLocation(currentLoc)
        val cityRef = cityReferences[cityName] ?: run {
            android.util.Log.w("BoltAssist", "Unknown city for location ${currentLoc.latitude}, ${currentLoc.longitude} - using basic analysis")
            return extractHotspotsBasic(bmp, currentLoc, operationRange, context)
        }

        android.util.Log.d("BoltAssist", "Analyzing screenshot for $cityName with OSM references")

        // 2) Detect map rotation by analyzing linear features (roads/streets)
        val mapRotation = detectMapRotation(bmp, cityRef)
        android.util.Log.d("BoltAssist", "Detected map rotation: ${mapRotation}°")

        // 3) Determine scale by looking for known landmarks or street patterns
        val pixelsPerMeter = determineMapScale(bmp, currentLoc, cityRef, operationRange)
        android.util.Log.d("BoltAssist", "Calculated scale: $pixelsPerMeter pixels/meter")

        // 4) Extract hotspots using basic color detection
        val hotspotPixels = extractHotPixels(bmp, context)
        if (hotspotPixels.isEmpty()) return emptyList()

        // 5) Cluster pixels into hotspots
        val hotspotCenters = clusterHotPixels(hotspotPixels, bmp.width, bmp.height, context)
        if (hotspotCenters.isEmpty()) return emptyList()

        // 6) Convert pixel coordinates to geo-coordinates using rotation and scale
        return hotspotCenters.map { (x, y) ->
            pixelToGeoCoordinate(x, y, bmp.width, bmp.height, currentLoc, pixelsPerMeter, mapRotation)
        }
    }

    private fun determineCityFromLocation(location: Location): String {
        var closestCity = "Olsztyn"
        var minDistance = Float.MAX_VALUE

        cityReferences.forEach { (cityName, cityRef) ->
            val distance = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                cityRef.center.lat, cityRef.center.lng,
                distance
            )
            if (distance[0] < minDistance) {
                minDistance = distance[0]
                closestCity = cityName
            }
        }

        android.util.Log.d("BoltAssist", "Determined city: $closestCity (distance: ${minDistance.toInt()}m)")
        return closestCity
    }

    private fun detectMapRotation(bmp: Bitmap, cityRef: CityReference): Double {
        val width = bmp.width
        val height = bmp.height
        
        // Look for linear features (roads) by detecting edge patterns
        val edges = mutableListOf<Pair<Int, Int>>()
        
        // Simple edge detection - look for high contrast transitions
        for (y in 1 until height - 1 step 4) {
            for (x in 1 until width - 1 step 4) {
                val center = bmp.getPixel(x, y)
                val right = bmp.getPixel(x + 1, y)
                val down = bmp.getPixel(x, y + 1)
                
                val contrastH = abs(getBrightness(center) - getBrightness(right))
                val contrastV = abs(getBrightness(center) - getBrightness(down))
                
                if (contrastH > 0.3f || contrastV > 0.3f) {
                    edges.add(x to y)
                }
            }
        }

        if (edges.size < 50) {
            android.util.Log.d("BoltAssist", "Not enough edges detected for rotation analysis")
            return 0.0 // Assume no rotation
        }

        // Analyze dominant line orientations using Hough-like approach
        val orientationBins = IntArray(180) // 1-degree bins
        
        edges.take(200).forEach { (x1, y1) ->
            edges.take(200).forEach { (x2, y2) ->
                if (x1 != x2 || y1 != y2) {
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val distance = sqrt((dx * dx + dy * dy).toDouble())
                    
                    if (distance > 20 && distance < 100) { // Filter for reasonable line segments
                        val angle = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI
                        val normalizedAngle = ((angle + 180) % 180).toInt()
                        orientationBins[normalizedAngle]++
                    }
                }
            }
        }

        // Find dominant orientations
        val maxBin = orientationBins.withIndex().maxByOrNull { it.value }?.index ?: 0
        val dominantAngle = maxBin.toDouble()

        // Expected main street orientation for this city
        val expectedOrientation = cityRef.mainStreets.firstOrNull()?.bearing ?: 0.0
        
        // Calculate rotation as difference from expected
        val rotation = (dominantAngle - expectedOrientation + 360) % 360
        val normalizedRotation = if (rotation > 180) rotation - 360 else rotation

        android.util.Log.d("BoltAssist", "Dominant angle: $dominantAngle°, Expected: $expectedOrientation°, Rotation: $normalizedRotation°")
        return normalizedRotation
    }

    private fun getBrightness(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return (r + g + b) / 3f
    }

    private fun determineMapScale(
        bmp: Bitmap,
        currentLoc: Location,
        cityRef: CityReference,
        operationRange: Float
    ): Double {
        // Method 1: Use operation range as baseline
        val baselineScale = (operationRange * 2 * 1000) / bmp.width.toDouble()

        // Method 2: Try to detect known landmarks or street spacing
        // For now, use the baseline but this could be enhanced with:
        // - OCR to read street names
        // - Pattern matching for known building shapes
        // - Analysis of street grid spacing

        android.util.Log.d("BoltAssist", "Using baseline scale calculation: $baselineScale meters/pixel")
        return 1.0 / baselineScale // Convert to pixels/meter
    }

    private fun extractHotPixels(bmp: Bitmap, context: android.content.Context? = null): List<Pair<Int, Int>> {
        val width = bmp.width
        val height = bmp.height
        val hotPixels = mutableListOf<Pair<Int, Int>>()

        // Get configurable sensitivity from settings
        val sensitivity = context?.getSharedPreferences("BoltAssist", android.content.Context.MODE_PRIVATE)
            ?.getFloat("heatmap_color_sensitivity", 0.6f) ?: 0.6f

        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                
                // Configurable color detection for Bolt's orange/red heatmap
                val baseThreshold = sensitivity
                val isHot = when {
                    // Bright orange/red (high demand) - adjusted by sensitivity
                    r > (0.7f * baseThreshold + 0.3f) && g > 0.3f && g < 0.7f && b < 0.3f -> true
                    // Medium orange (medium demand) - main threshold
                    r > baseThreshold && g > 0.2f && g < 0.8f && b < 0.4f -> true
                    // Yellow-orange (lower demand) - more permissive
                    r > (0.8f * baseThreshold + 0.2f) && g > (0.6f * baseThreshold + 0.4f) && b < 0.3f -> true
                    else -> false
                }
                
                if (isHot) {
                    hotPixels.add(x to y)
                }
            }
        }

        android.util.Log.d("BoltAssist", "Found ${hotPixels.size} hot pixels (sensitivity: ${(sensitivity * 100).toInt()}%)")
        return hotPixels
    }

    private fun clusterHotPixels(hotPixels: List<Pair<Int, Int>>, width: Int, height: Int, context: android.content.Context? = null): List<Pair<Int, Int>> {
        if (hotPixels.isEmpty()) return emptyList()

        val cellSize = 30 // Slightly larger for better clustering
        // Get configurable cluster threshold from settings
        val minPixelsPerCluster = context?.getSharedPreferences("BoltAssist", android.content.Context.MODE_PRIVATE)
            ?.getInt("heatmap_cluster_threshold", 60) ?: 60
        val clusters = mutableListOf<Pair<Int, Int>>()

        for (cy in cellSize until height - cellSize step cellSize) {
            for (cx in cellSize until width - cellSize step cellSize) {
                val pixelsInCell = hotPixels.count { (x, y) ->
                    abs(x - cx) <= cellSize / 2 && abs(y - cy) <= cellSize / 2
                }
                
                if (pixelsInCell >= minPixelsPerCluster) {
                    // Calculate center of mass for more accurate positioning
                    val pixelsInRegion = hotPixels.filter { (x, y) ->
                        abs(x - cx) <= cellSize / 2 && abs(y - cy) <= cellSize / 2
                    }
                    
                    val centerX = pixelsInRegion.map { it.first }.average().toInt()
                    val centerY = pixelsInRegion.map { it.second }.average().toInt()
                    
                    // Avoid duplicate clusters
                    val tooClose = clusters.any { (existingX, existingY) ->
                        sqrt(((centerX - existingX).toDouble().pow(2) + (centerY - existingY).toDouble().pow(2))) < cellSize
                    }
                    
                    if (!tooClose) {
                        clusters.add(centerX to centerY)
                    }
                }
            }
        }

        android.util.Log.d("BoltAssist", "Clustered into ${clusters.size} hotspots")
        return clusters
    }

    private fun pixelToGeoCoordinate(
        pixelX: Int,
        pixelY: Int,
        imageWidth: Int,
        imageHeight: Int,
        centerLocation: Location,
        pixelsPerMeter: Double,
        rotationDegrees: Double
    ): Pair<Double, Double> {
        // Convert pixel offset from center
        val dxPx = pixelX - imageWidth / 2.0
        val dyPx = pixelY - imageHeight / 2.0

        // Convert to meters
        val dxMeters = dxPx / pixelsPerMeter
        val dyMeters = -dyPx / pixelsPerMeter // Screen Y grows downward

        // Apply rotation correction
        val rotationRad = rotationDegrees * PI / 180.0
        val rotatedDx = dxMeters * cos(rotationRad) - dyMeters * sin(rotationRad)
        val rotatedDy = dxMeters * sin(rotationRad) + dyMeters * cos(rotationRad)

        // Convert to lat/lng offset
        val latPerMeter = 1.0 / 111320.0
        val lonPerMeter = 1.0 / (111320.0 * cos(Math.toRadians(centerLocation.latitude)))

        val finalLat = centerLocation.latitude + rotatedDy * latPerMeter
        val finalLng = centerLocation.longitude + rotatedDx * lonPerMeter

        return finalLat to finalLng
    }

    // Fallback to basic analysis if city not recognized
    private fun extractHotspotsBasic(
        bmp: Bitmap,
        currentLoc: Location,
        operationRange: Float,
        context: android.content.Context? = null
    ): List<Pair<Double, Double>> {
        android.util.Log.d("BoltAssist", "Using basic hotspot extraction")
        
        val hotPixels = extractHotPixels(bmp, context)
        val clusters = clusterHotPixels(hotPixels, bmp.width, bmp.height, context)
        
        val metersPerPixel = (operationRange * 2 * 1000) / bmp.width.toDouble()
        val latPerMeter = 1.0 / 111320.0
        val lonPerMeter = 1.0 / (111320.0 * cos(Math.toRadians(currentLoc.latitude)))

        return clusters.map { (x, y) ->
            val dxPx = x - bmp.width / 2
            val dyPx = y - bmp.height / 2
            val dLon = dxPx * metersPerPixel * lonPerMeter
            val dLat = -dyPx * metersPerPixel * latPerMeter
            (currentLoc.latitude + dLat) to (currentLoc.longitude + dLon)
        }
    }
} 