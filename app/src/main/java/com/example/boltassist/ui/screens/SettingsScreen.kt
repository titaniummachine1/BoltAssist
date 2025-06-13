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
import com.example.boltassist.TripManager

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
            }
        }
    }
} 