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
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 7 rows (days) x 24 columns (hourly slots)
        WeeklyEarningsGrid(tripManager = tripManager)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Legend
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LegendItem(color = Color.Black, label = "No Data")
            LegendItem(color = Color.Red, label = "Bad")
            LegendItem(color = Color.Yellow, label = "Decent")
            LegendItem(color = Color.Green, label = "Good")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Setup Controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
            
            // Important: Overlay Permission Notice
            Card(
                modifier = Modifier.padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Yellow.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚠️ IMPORTANT",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                    Text(
                        text = "You must grant overlay permission for the floating window to work!",
                        fontSize = 14.sp,
                        color = Color.Black
                    )
                }
            }
            
            // Start Floating Assistant
            Button(
                onClick = onStartFloatingWindow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Green
                ),
                modifier = Modifier.size(250.dp, 80.dp)
            ) {
                Text(
                    text = "Grant Permission & Start\nDrive Assistant",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Text(
                text = "This will create a draggable floating 'Help' button for recording trips while driving",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun WeeklyEarningsGrid(tripManager: TripManager) {
    var gridData by remember { mutableStateOf(tripManager.getWeeklyEarningsGrid()) }
    val currentTimeSlot = tripManager.getCurrentTimeSlot()
    
    // Refresh data every minute
    LaunchedEffect(Unit) {
        while (true) {
            delay(60000) // 1 minute
            gridData = tripManager.getWeeklyEarningsGrid()
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
        val minEarnings = 10.0
        val maxEarnings = 50.0
        val ratio = ((hourlyEarnings - minEarnings) / (maxEarnings - minEarnings)).coerceIn(0.0, 1.0)
        
        if (ratio <= 0.5) {
            // Red to Yellow
            val localRatio = (ratio * 2).toFloat()
            Color(1f, localRatio, 0f)
        } else {
            // Yellow to Green
            val localRatio = ((ratio - 0.5) * 2).toFloat()
            Color(1f - localRatio, 1f, 0f)
        }
    } else {
        Color.Black // Not enough data
    }
    
    val borderColor = if (isCurrentTime) Color.Blue else Color.Gray
    val borderWidth = if (isCurrentTime) 2.dp else 0.5.dp
    
    Box(
        modifier = Modifier
            .size(25.dp)
            .background(backgroundColor)
            .border(borderWidth, borderColor)
    )
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color)
                .border(0.5.dp, Color.Gray)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
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


