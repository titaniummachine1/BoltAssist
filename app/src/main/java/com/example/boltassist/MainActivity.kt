package com.example.boltassist

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.boltassist.ui.theme.BoltAssistTheme
import java.io.File
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box

class MainActivity : ComponentActivity() {
    private var onFolderSelected: ((String) -> Unit)? = null
    private var selectedStoragePath: String? = null
    
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Save the selected URI path
            selectedStoragePath = it.toString()
            
            // Save to SharedPreferences for persistence
            val prefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
            prefs.edit().putString("storage_path", selectedStoragePath).apply()
            
            onFolderSelected?.invoke(selectedStoragePath!!)
        }
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        if (canDrawOverlays()) {
            startFloatingWindow()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load saved storage path
        val prefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
        selectedStoragePath = prefs.getString("storage_path", null)
        
        setContent {
            BoltAssistTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartFloatingWindow = { requestOverlayPermissionAndStart() },
                        onFolderSelect = { callback ->
                            onFolderSelected = callback
                            directoryPickerLauncher.launch(null)
                        },
                        savedStoragePath = selectedStoragePath
                    )
                }
            }
        }
    }
    
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
            }
        }
    
    private fun requestOverlayPermissionAndStart() {
        if (canDrawOverlays()) {
            startFloatingWindow()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            overlayPermissionLauncher.launch(intent)
        }
    }
    
    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        
        // Pass the selected storage path to the service
        selectedStoragePath?.let {
            intent.putExtra("storage_path", it)
        }
        
        startService(intent)
    }
}

@Composable
fun MainScreen(
    onStartFloatingWindow: () -> Unit,
    onFolderSelect: ((String) -> Unit) -> Unit,
    savedStoragePath: String?
) {
    val context = LocalContext.current
    
    // Storage path display state
    var displayPath by remember { mutableStateOf("Default App Directory") }

    // Initialize singleton TripManager and set storage
    LaunchedEffect(savedStoragePath) {
        TripManager.initialize(context)
        
        if (savedStoragePath != null) {
            TripManager.setStorageDirectoryUri(Uri.parse(savedStoragePath))
            displayPath = "Selected: ${Uri.parse(savedStoragePath).lastPathSegment}"
        } else {
            val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist")
                ?: context.filesDir.resolve("BoltAssist")
            TripManager.setStorageDirectory(defaultDir)
            displayPath = "Default App Directory"
        }
        
        android.util.Log.d("BoltAssist", "MainActivity: TripManager initialized with ${TripManager.tripsCache.size} trips")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Storage status display
        Text(
            text = "Storage: $displayPath",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        // Grid
        Box(modifier = Modifier.weight(1f)) {
            WeeklyEarningsGrid()
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Legend
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            LegendItem(color = Color.Black, label = "No Data")
            LegendItem(color = Color.Red, label = "Poor (<8)")
            LegendItem(color = Color.Yellow, label = "Decent (8-25)")
            LegendItem(color = Color.Green, label = "Good (25-45)")
            LegendItem(color = Color(1f,0.65f,0f), label = "Legendary (45+)", isSpecial = true)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                onFolderSelect { newUri ->
                    TripManager.setStorageDirectoryUri(Uri.parse(newUri))
                    displayPath = "Selected: ${Uri.parse(newUri).lastPathSegment}"
                }
            }) {
                Text("Select Directory")
            }
            Button(onClick = {
                TripManager.generateTestData()
                android.util.Log.d("BoltAssist", "Test data generated, cache size: ${TripManager.tripsCache.size}")
            }) {
                Text("Test Data")
            }
            Button(onClick = onStartFloatingWindow, modifier = Modifier.weight(1f)) {
                Text("Begin")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
fun WeeklyEarningsGrid() {
    // Get live grid data
    val trips = TripManager.tripsCache
    val gridData = TripManager.getWeeklyGrid()
    val currentTime = TripManager.getCurrentTime()
    
    LaunchedEffect(trips.size) {
        android.util.Log.d("BoltAssist", "Grid recomposing with ${trips.size} trips")
    }

    val polishDays = listOf("PN", "WT", "ŚR", "CZ", "PT", "SB", "ND")

    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // Header row with hour numbers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Empty cell for day labels column
            Box(modifier = Modifier.size(25.dp))
            
            // Hour numbers 1-24
            repeat(24) { hour ->
                Box(
                    modifier = Modifier.size(25.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${hour + 1}",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
        
        // Data rows with day labels
        repeat(7) { day ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Day label
                Box(
                    modifier = Modifier.size(25.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = polishDays[day],
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                
                // Hour cells
                repeat(24) { hour ->
                    SimpleGridCell(
                        earnings = gridData[day][hour],
                        isCurrentTime = currentTime.first == day && currentTime.second == hour
                    )
                }
            }
        }
    }
}

@Composable
fun SimpleGridCell(earnings: Double, isCurrentTime: Boolean) {
    // Simple color calculation
    val backgroundColor = when {
        earnings == 0.0 -> Color.Black // No data
        earnings >= 80.0 -> Color(1f, 0.84f, 0f) // LEGENDARY - Gold
        earnings >= 45.0 -> Color.Green // Good
        earnings >= 25.0 -> Color.Yellow // Decent  
        earnings >= 8.0 -> Color.Red // Poor
        else -> Color(0.5f, 0f, 0f) // Very Poor - Dark Red
    }
    
    // Consistent sizing and borders for all cells
    val borderColor = if (isCurrentTime) Color.Blue else Color.Gray
    val borderWidth = if (isCurrentTime) 2.dp else 0.5.dp
    val cellSize = 25.dp // Same size for all cells
    
    Box(
        modifier = Modifier
            .size(cellSize)
            .background(backgroundColor)
            .border(borderWidth, borderColor)
    ) {
        // Show earnings text for any cell with data
        if (earnings > 0.0) {
            Text(
                text = "${earnings.toInt()}",
                fontSize = 8.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String, isSpecial: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (isSpecial) 20.dp else 16.dp)
                .background(color)
                .border(
                    if (isSpecial) 2.dp else 0.5.dp, 
                    if (isSpecial) Color(1f, 0.65f, 0f) else Color.Gray
                )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = if (isSpecial) 13.sp else 11.sp,
            fontWeight = if (isSpecial) FontWeight.Bold else FontWeight.Medium,
            color = if (isSpecial) Color(1f, 0.65f, 0f) else Color.Black
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BoltAssistTheme {
        MainScreen(
            onStartFloatingWindow = {},
            onFolderSelect = { _ -> },
            savedStoragePath = null
        )
    }
}


