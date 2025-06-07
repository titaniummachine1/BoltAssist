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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
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

class MainActivity : ComponentActivity() {
    private var onFolderSelected: ((String) -> Unit)? = null
    
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Convert URI to display path
            val path = it.toString()
            onFolderSelected?.invoke(path)
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
                        }
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
        startService(intent)
        finish() // Close main activity
    }
}

@Composable
fun MainScreen(
    onStartFloatingWindow: () -> Unit,
    onFolderSelect: ((String) -> Unit) -> Unit
) {
    var selectedFolder by remember { mutableStateOf("Not Selected") }
    val context = LocalContext.current
    val tripManager = remember { TripManager(context) }
    
    // Set up default storage
    LaunchedEffect(Unit) {
        val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist") 
            ?: context.filesDir.resolve("BoltAssist")
        tripManager.setStorageDirectory(defaultDir)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // 7 rows (days) x 24 columns (hourly slots)
        WeeklyEarningsGrid(tripManager = tripManager)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Legend
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LegendItem(color = Color.Black, label = "No Data")
                LegendItem(color = Color(0.5f, 0f, 0f), label = "Very Poor (<8)")
                LegendItem(color = Color.Red, label = "Poor (8-25)")
                LegendItem(color = Color.Yellow, label = "Decent (25-45)")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LegendItem(color = Color.Green, label = "Good (45-80)")
                LegendItem(
                    color = Color(1f, 0.84f, 0f), 
                    label = "LEGENDARY (80+)", 
                    isSpecial = true
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Setup Controls - Moved to scrollable area
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                // Folder Selection
                Button(
                    onClick = { 
                        onFolderSelect { folderPath ->
                            selectedFolder = "Selected"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedFolder == "Not Selected") Color.Gray else Color.Blue
                    )
                ) {
                    Text("Select Storage Folder: $selectedFolder")
                }
            }
            
            item {
                // START FLOATING ASSISTANT - BIG GREEN BUTTON
                Button(
                    onClick = onStartFloatingWindow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Green
                    ),
                    modifier = Modifier.size(280.dp, 100.dp)
                ) {
                    Text(
                        text = "🚀 START DRIVE ASSISTANT 🚀",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            item {
                // Important: Overlay Permission Notice
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Yellow.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⚠️ Grant overlay permission when prompted",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Red
                        )
                        Text(
                            text = "Creates draggable floating 'Help' button for trip recording",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }
            
            item {
                // Debug: Manual refresh button
                Button(
                    onClick = { 
                        val defaultDir = context.getExternalFilesDir(null)?.resolve("BoltAssist") 
                            ?: context.filesDir.resolve("BoltAssist")
                        tripManager.setStorageDirectory(defaultDir)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text("🔄 Refresh Data (Debug)")
                }
            }
        }
    }
}

@Composable
fun WeeklyEarningsGrid(tripManager: TripManager) {
    var gridData by remember { mutableStateOf(tripManager.getWeeklyEarningsGrid()) }
    var refreshCounter by remember { mutableStateOf(0) }
    val currentTimeSlot = tripManager.getCurrentTimeSlot()
    
    // Immediate refresh when component loads and every 10 seconds for debugging
    LaunchedEffect(refreshCounter) {
        gridData = tripManager.getWeeklyEarningsGrid()
    }
    
    // Auto-refresh every 10 seconds for debugging + manual refresh capability
    LaunchedEffect(Unit) {
        while (true) {
            delay(10000) // 10 seconds for debugging (was 60 seconds)
            refreshCounter++
        }
    }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        repeat(7) { dayIndex ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(24) { hourIndex ->
                    GridCell(
                        dayIndex = dayIndex,
                        hourIndex = hourIndex,
                        cellData = gridData[dayIndex][hourIndex],
                        isCurrentTime = currentTimeSlot.first == dayIndex && currentTimeSlot.second == hourIndex
                    )
                }
            }
        }
    }
}

@Composable
fun GridCell(dayIndex: Int, hourIndex: Int, cellData: GridCellData, isCurrentTime: Boolean) {
    val backgroundColor = if (cellData.hasEnoughData()) {
        val hourlyEarnings = cellData.getHourlyEarnings()
        when {
            hourlyEarnings >= 80.0 -> Color(1f, 0.84f, 0f) // LEGENDARY - Pure Gold
            hourlyEarnings >= 45.0 -> {
                // Good to Legendary gradient
                val ratio = ((hourlyEarnings - 45.0) / 35.0).coerceIn(0.0, 1.0).toFloat()
                Color(ratio, 1f, 0f)
            }
            hourlyEarnings >= 25.0 -> {
                // Decent to Good
                val ratio = ((hourlyEarnings - 25.0) / 20.0).coerceIn(0.0, 1.0).toFloat()
                Color(1f - ratio, 1f, 0f)
            }
            hourlyEarnings >= 8.0 -> {
                // Poor to Decent
                val ratio = ((hourlyEarnings - 8.0) / 17.0).coerceIn(0.0, 1.0).toFloat()
                Color(1f, ratio, 0f)
            }
            else -> Color(0.5f, 0f, 0f) // Very Poor - Dark Red
        }
    } else {
        Color.Black // Not enough data
    }
    
    // Special effects for legendary and current time
    val isLegendary = cellData.isLegendary()
    val borderColor = when {
        isLegendary && isCurrentTime -> Color.Magenta // Both legendary and current
        isLegendary -> Color(1f, 0.65f, 0f) // Orange border for legendary
        isCurrentTime -> Color.Blue // Blue for current time
        else -> Color.Gray
    }
    val borderWidth = when {
        isLegendary && isCurrentTime -> 4.dp // Extra thick for legendary + current
        isLegendary -> 3.dp // Thick for legendary
        isCurrentTime -> 2.dp // Medium for current time
        else -> 0.5.dp
    }
    
    val cellSize = if (isLegendary) 28.dp else 25.dp // Slightly bigger for legendary
    
    Box(
        modifier = Modifier
            .size(cellSize)
            .background(backgroundColor)
            .border(borderWidth, borderColor)
    ) {
        // Add earnings text for legendary cells
        if (isLegendary) {
            Text(
                text = "${cellData.getHourlyEarnings().toInt()}",
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
            onStartFloatingWindow = { },
            onFolderSelect = { }
        )
    }
}


