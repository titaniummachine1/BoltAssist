package com.example.boltassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import com.example.boltassist.TripManager
import com.example.boltassist.MapConfig
import com.example.boltassist.StreetDataManager
import com.example.boltassist.TrafficDataManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayPath: String, 
    editMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onFolderSelect: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), 
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage settings
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Storage Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("Storage Path", fontWeight = FontWeight.Medium)
                Text(displayPath, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                Button(onClick = onFolderSelect, Modifier.fillMaxWidth()) {
                    Text("Select Directory")
                }
            }
        }
        
        // Operation area settings
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Operation Area", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                
                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("BoltAssist", Context.MODE_PRIVATE) }
                
                var selectedCity by remember { 
                    mutableStateOf(prefs.getString("operation_city", "Olsztyn") ?: "Olsztyn") 
                }
                var operationRange by remember { 
                    mutableStateOf(prefs.getFloat("operation_range", 10.0f)) 
                }
                
                // City selection
                Text("Operation City", fontWeight = FontWeight.Medium)
                val cities = MapConfig.getAllCityNames()
                var expandedCity by remember { mutableStateOf(false) }
                
                ExposedDropdownMenuBox(
                    expanded = expandedCity,
                    onExpandedChange = { expandedCity = !expandedCity },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedCity,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCity) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCity,
                        onDismissRequest = { expandedCity = false }
                    ) {
                        cities.forEach { city ->
                            DropdownMenuItem(
                                text = { Text(city) },
                                onClick = {
                                    selectedCity = city
                                    expandedCity = false
                                    prefs.edit().putString("operation_city", city).apply()
                                }
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                // Range selection
                Text("Operation Range: ${operationRange.toInt()} km", fontWeight = FontWeight.Medium)
                Slider(
                    value = operationRange,
                    onValueChange = { 
                        operationRange = it
                        prefs.edit().putFloat("operation_range", it).apply()
                    },
                    valueRange = 5f..50f,
                    steps = 8, // 5, 10, 15, 20, 25, 30, 35, 40, 45, 50
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    "Map will load within ${operationRange.toInt()}km of ${selectedCity} center\n" +
                    "• Loading prioritizes center tiles (faster startup)\n" + 
                    "• Based on main transport hub when available\n" +
                    "• Switch to Map tab to see changes",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        // Heatmap Analysis settings
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Heatmap Analysis", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                
                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("BoltAssist", Context.MODE_PRIVATE) }
                
                var colorSensitivity by remember { 
                    mutableStateOf(prefs.getFloat("heatmap_color_sensitivity", 0.6f)) 
                }
                var clusterThreshold by remember { 
                    mutableStateOf(prefs.getInt("heatmap_cluster_threshold", 60)) 
                }
                
                // Color sensitivity
                Text("Color Detection Sensitivity: ${(colorSensitivity * 100).toInt()}%", fontWeight = FontWeight.Medium)
                Slider(
                    value = colorSensitivity,
                    onValueChange = { 
                        colorSensitivity = it
                        prefs.edit().putFloat("heatmap_color_sensitivity", it).apply()
                    },
                    valueRange = 0.3f..0.9f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    "Higher = detects more orange/red pixels (may include false positives)\n" +
                    "Lower = only detects bright hotspots (may miss weak signals)",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Cluster threshold
                Text("Hotspot Cluster Size: $clusterThreshold pixels", fontWeight = FontWeight.Medium)
                Slider(
                    value = clusterThreshold.toFloat(),
                    onValueChange = { 
                        clusterThreshold = it.toInt()
                        prefs.edit().putInt("heatmap_cluster_threshold", clusterThreshold).apply()
                    },
                    valueRange = 30f..120f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    "Minimum pixels needed to form a hotspot\n" +
                    "Higher = fewer, larger hotspots • Lower = more, smaller hotspots",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // OSM reference info
                Text("OSM Reference Points", fontWeight = FontWeight.Medium)
                Text(
                    "Supports: Olsztyn, Warsaw, Krakow, Gdansk, Wroclaw\n" +
                    "• Auto-detects map rotation using street patterns\n" +
                    "• Uses known landmarks for accurate geo-positioning\n" +
                    "• Falls back to basic analysis for other cities",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
        
        // Debug settings
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Debug Options", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                
                // Edit mode toggle
                Row(
                    Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onEditModeChange(!editMode) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (editMode) Color.Red else MaterialTheme.colorScheme.primary
                        )
                    ) { 
                        Text(if (editMode) "Exit Edit" else "Edit Mode") 
                    }
                }
                
                if (editMode) {
                    Text(
                        "Edit Mode Active: Use the grid in Graph tab\n• Click cells to add a random trip\n• Long press to clear", 
                        fontSize = 12.sp, 
                        color = Color.Red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                Spacer(Modifier.height(8.dp))
                
                // Reset database
                Button(
                    onClick = { 
                        TripManager.resetDatabase()
                        android.util.Log.d("BoltAssist", "Database reset from Settings")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { 
                    Text("Reset Database", color = Color.White) 
                }
                
                Text(
                    "⚠️ Warning: This will permanently delete all trip data!",
                    fontSize = 12.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(top = 4.dp)
                )
                
                Spacer(Modifier.height(16.dp))
                
                // Diagnostic info
                Text("Diagnostic Info", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val tripsCount by remember { derivedStateOf { TripManager.tripsCache.size } }
                Text("Trips in cache: $tripsCount", fontSize = 12.sp, color = Color.Gray)
                Text("Storage: ${TripManager.getStorageInfo()}", fontSize = 12.sp, color = Color.Gray)
                Text("TripManager initialized: ${TripManager.isInitialized()}", fontSize = 12.sp, color = Color.Gray)
                Text("${StreetDataManager.getDataSummary()}", fontSize = 12.sp, color = Color.Gray)
                Text("${TrafficDataManager.getTrafficSummary()}", fontSize = 12.sp, color = Color.Gray)
                
                Button(
                    onClick = { 
                        // Force reload from storage
                        TripManager.reloadFromFile()
                        android.util.Log.d("BoltAssist", "Reloaded from file - cache size: ${TripManager.tripsCache.size}")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { 
                    Text("Reload from Storage") 
                }
                
                Button(
                    onClick = { 
                        // Debug: List all trips in cache
                        android.util.Log.d("BoltAssist", "=== DEBUG: ALL TRIPS IN CACHE (${TripManager.tripsCache.size}) ===")
                        TripManager.tripsCache.forEachIndexed { index, trip ->
                            android.util.Log.d("BoltAssist", "[$index] ID: ${trip.id}")
                            android.util.Log.d("BoltAssist", "  Start: ${trip.startTime}")
                            android.util.Log.d("BoltAssist", "  End: ${trip.endTime}")
                            android.util.Log.d("BoltAssist", "  Earnings: ${trip.earningsPLN} PLN")
                            android.util.Log.d("BoltAssist", "  Duration: ${trip.durationMinutes} min")
                            android.util.Log.d("BoltAssist", "  Start Street: ${trip.startStreet}")
                            android.util.Log.d("BoltAssist", "  End Street: ${trip.endStreet}")
                        }
                        android.util.Log.d("BoltAssist", "=== END DEBUG ===")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                ) { 
                    Text("Debug: List All Trips") 
                }
                
                Button(
                    onClick = { 
                        // Generate sample overlay data for testing
                        val prefs = context.getSharedPreferences("BoltAssist", Context.MODE_PRIVATE)
                        val selectedCity = prefs.getString("operation_city", "Olsztyn") ?: "Olsztyn"
                        val cityConfig = MapConfig.getCityConfig(selectedCity)
                        val centerLat = cityConfig.center.latitude
                        val centerLng = cityConfig.center.longitude
                        
                        // Generate 5-8 demand hotspots around city center
                        val demandCount = (5..8).random()
                        repeat(demandCount) {
                            val offsetLat = (Math.random() - 0.5) * 0.02 // ~1km radius
                            val offsetLng = (Math.random() - 0.5) * 0.02
                            StreetDataManager.addStreetData(
                                lat = centerLat + offsetLat,
                                lng = centerLng + offsetLng,
                                passengerDemand = (1..4).random(),
                                driverSupply = 0,
                                dataSource = "test_demand"
                            )
                        }
                        
                        // Generate 8-12 driver positions around city center
                        val driverCount = (8..12).random()
                        repeat(driverCount) {
                            val offsetLat = (Math.random() - 0.5) * 0.015 // ~750m radius
                            val offsetLng = (Math.random() - 0.5) * 0.015
                            StreetDataManager.addStreetData(
                                lat = centerLat + offsetLat,
                                lng = centerLng + offsetLng,
                                passengerDemand = 0,
                                driverSupply = 1,
                                dataSource = "test_supply"
                            )
                        }
                        
                        android.util.Log.d("BoltAssist", "Generated test data: $demandCount demand hotspots, $driverCount drivers for $selectedCity")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                ) { 
                    Text("🧪 Generate Test Overlay Data") 
                }
            }
        }
    }
} 