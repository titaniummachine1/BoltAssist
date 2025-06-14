package com.example.boltassist.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import com.example.boltassist.MapConfig
import com.example.boltassist.ScreenCaptureService
import com.example.boltassist.MapOverlayManager

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("BoltAssist", android.content.Context.MODE_PRIVATE) }
    
    // Get dynamic settings
    val selectedCity by remember { 
        derivedStateOf { prefs.getString("operation_city", "Olsztyn") ?: "Olsztyn" }
    }
    val operationRange by remember { 
        derivedStateOf { prefs.getFloat("operation_range", 15.0f) }
    }
    
    val mapViewState = remember(selectedCity, operationRange) {
        mutableStateOf(
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                
                // Enhanced zoom controls for better mobile experience
                setMultiTouchControls(true) // Enable pinch zoom
                
                // Get operation center (prefer main station for ride-sharing)
                val operationCenter = MapConfig.getOperationCenter(selectedCity)
                controller.setZoom(14.5) // Slightly higher default zoom for better street detail
                controller.setCenter(operationCenter)
                
                // Enhanced zoom limits with smaller steps for accuracy
                minZoomLevel = 12.0 // Prevent zooming out beyond operational area visibility
                maxZoomLevel = 20.0 // Allow very detailed street view
                
                // Restrict scrollable area to square bounds (easier than circle)
                val radiusKm = operationRange.toDouble()
                val radiusDegrees = radiusKm / 111.0 // Approximate conversion (1 degree ≈ 111 km)
                
                val northBound = operationCenter.latitude + radiusDegrees
                val southBound = operationCenter.latitude - radiusDegrees
                val eastBound = operationCenter.longitude + radiusDegrees
                val westBound = operationCenter.longitude - radiusDegrees
                
                // Set strict scrollable area bounds - keep camera within operational square
                setScrollableAreaLimitDouble(
                    org.osmdroid.util.BoundingBox(
                        northBound, eastBound, southBound, westBound
                    )
                )
                
                // Optimize tile loading strategy
                tileProvider.apply {
                    // Prioritize center tiles
                    // Modern OSMDroid handles center-out loading automatically
                    // but we can optimize cache strategy
                }
                
                // Optimize for mobile performance
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                isTilesScaledToDpi = true // Better scaling on different screen densities
                
                // Initialize overlays for heatmaps and predictions
                post {
                    // Initialize overlays after the map is ready
                    MapOverlayManager.initializeOverlays(this)
                }
                
                android.util.Log.d("BoltAssist", "Enhanced map configured for $selectedCity (${operationRange}km range)")
                android.util.Log.d("BoltAssist", "Operation center: ${operationCenter.latitude}, ${operationCenter.longitude}")
                android.util.Log.d("BoltAssist", "Bounds: N=$northBound, S=$southBound, E=$eastBound, W=$westBound")
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main map view
        AndroidView(
            factory = { mapViewState.value },
            modifier = Modifier.fillMaxSize()
        )
        
        // Heatmap toggle button (top right)
        var heatmapVisible by remember { mutableStateOf(true) }
        Button(
            onClick = { 
                heatmapVisible = !heatmapVisible
                MapOverlayManager.toggleHeatmapVisibility(mapViewState.value, heatmapVisible)
                android.util.Log.d("BoltAssist", "Heatmap visibility: $heatmapVisible")
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (heatmapVisible) Color(0xFF4CAF50) else Color.Gray
            )
        ) {
            Text("Heat", fontSize = 10.sp)
        }
        
        // Scan buttons for demand/supply analysis
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Scan Passenger Demand (left)
            Button(
                onClick = { 
                    val intent = Intent(context, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_CAPTURE_TYPE, "demand")
                    }
                    context.startService(intent)
                    android.util.Log.d("BoltAssist", "Started screen capture for passenger demand")
                },
                modifier = Modifier.weight(0.4f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C851)) // Green for demand
            ) {
                Text("Scan\nDemand", fontSize = 12.sp, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            
            Spacer(modifier = Modifier.weight(0.2f))
            
            // Scan Driver Supply (right)
            Button(
                onClick = { 
                    val intent = Intent(context, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_CAPTURE_TYPE, "supply")
                    }
                    context.startService(intent)
                    android.util.Log.d("BoltAssist", "Started screen capture for driver supply")
                },
                modifier = Modifier.weight(0.4f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)) // Red for supply
            ) {
                Text("Scan\nSupply", fontSize = 12.sp, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
} 