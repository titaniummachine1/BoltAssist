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
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
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
                // Navigation drawer state
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // Selected screen
                var selectedScreen by remember { mutableStateOf<Screen>(Screen.Graph) }
                // Storage path for Settings
                var displayPath by remember { mutableStateOf("Default App Directory") }
                // Init TripManager and load persisted path
                LaunchedEffect(Unit) {
                    TripManager.initialize(this@MainActivity)
                    val prefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
                    val saved = prefs.getString("storage_path", null)
                    if (saved != null) {
                        TripManager.setStorageDirectoryUri(Uri.parse(saved))
                        displayPath = Uri.parse(saved).lastPathSegment ?: saved
                    } else {
                        val defaultDir = getExternalFilesDir(null)?.resolve("BoltAssist")
                            ?: filesDir.resolve("BoltAssist")
                        TripManager.setStorageDirectory(defaultDir)
                        displayPath = "Default App Directory"
                    }
                }
                // Drawer
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Spacer(Modifier.height(16.dp))
                            Screen.values().forEach { screen ->
                                NavigationDrawerItem(
                                    label = { Text(screen.title) },
                                    selected = screen == selectedScreen,
                                    onClick = {
                                        selectedScreen = screen
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                )
                            }
                        }
                    }
                ) {
                    Scaffold(
                        topBar = {
                            CenterAlignedTopAppBar(
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                                    }
                                },
                                title = { Text("BoltAssist") }
                            )
                        }
                    ) { inner ->
                        Box(Modifier.padding(inner)) {
                            when (selectedScreen) {
                                Screen.Graph -> GraphScreen(onStartFloatingWindow = { requestOverlayPermissionAndStart() })
                                Screen.Map -> MapScreen()
                                Screen.Settings -> SettingsScreen(
                                    displayPath = displayPath,
                                    onFolderSelect = {
                                        onFolderSelected = { selectedPath ->
                                            displayPath = Uri.parse(selectedPath).lastPathSegment ?: selectedPath
                                        }
                                        directoryPickerLauncher.launch(null)
                                    }
                                )
                            }
                        }
                    }
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

    // Define navigation targets
    private enum class Screen(val title: String) { Graph("Graph"), Map("Map"), Settings("Settings") }
}

@Composable
fun GraphScreen(onStartFloatingWindow: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Box(Modifier.weight(1f)) { WeeklyEarningsGrid() }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartFloatingWindow, Modifier.weight(1f)) { Text("Begin") }
        }
    }
}

@Composable
fun MapScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Map tools coming soon…")
    }
}

@Composable
fun SettingsScreen(displayPath: String, onFolderSelect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Storage Path", fontWeight = FontWeight.Bold)
        Text(displayPath, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = onFolderSelect, Modifier.fillMaxWidth()) {
            Text("Select Directory")
        }
    }
}

@Composable
fun WeeklyEarningsGrid() {
    // Get live grid data
    val trips = TripManager.tripsCache
    val actualGrid = TripManager.getWeeklyGrid()
    val kalmanGrid = TripManager.getKalmanPredictionGrid()
    // Track system time to update highlight immediately and every 5 seconds for responsiveness
    var currentTime by remember { mutableStateOf(TripManager.getCurrentTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L) // Update every 5 seconds instead of 60 for quicker response to time changes
            currentTime = TripManager.getCurrentTime()
        }
    }
    // Compute header index for highlighting (map hour 0-23 to index 0-23 for 1-24 labels)
    val highlightIndex = (currentTime.second - 1 + 24) % 24

    LaunchedEffect(trips.size) {
        android.util.Log.d("BoltAssist", "Grid recomposing with ${trips.size} trips")
        // Force update current time when trips change (like when time traveling)
        currentTime = TripManager.getCurrentTime()
    }
    
    // Listen to lifecycle events to refresh time when app comes to foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Immediately update time when app resumes (handles time travel)
                currentTime = TripManager.getCurrentTime()
                android.util.Log.d("BoltAssist", "App resumed - refreshed current time to: $currentTime")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val polishDays = listOf("PN", "WT", "ŚR", "CZ", "PT", "SB", "ND")
    
    // Check orientation to decide grid layout
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // Landscape mode: Days vertical, Hours horizontal (current layout)
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
                        // Choose actual earnings for current hour, Kalman predictions for others
                        val isCurrent = day == currentTime.first && hour == highlightIndex
                        val value = if (isCurrent && actualGrid[day][hour] > 0) {
                            actualGrid[day][hour] // Show actual data if available for current time
                        } else {
                            kalmanGrid[day][hour] // Use Kalman filter predictions otherwise
                        }
                        SimpleGridCell(
                            earnings = value,
                            isCurrentTime = isCurrent
                        )
                    }
                }
            }
        }
    } else {
        // Portrait mode: Days horizontal, Hours vertical (rotated layout)
        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // Header column with day labels
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                // Empty cell for hour labels row
                Box(modifier = Modifier.size(25.dp))
                
                // Day labels
                repeat(7) { day ->
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
                }
            }
            
            // Scrollable grid content
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                // Data columns with hour labels
                repeat(24) { hour ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // Hour label
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
                        
                        // Day cells for this hour
                        repeat(7) { day ->
                            // Choose actual earnings for current hour, Kalman predictions for others
                            val isCurrent = day == currentTime.first && hour == highlightIndex
                            val value = if (isCurrent && actualGrid[day][hour] > 0) {
                                actualGrid[day][hour] // Show actual data if available for current time
                            } else {
                                kalmanGrid[day][hour] // Use Kalman filter predictions otherwise
                            }
                            SimpleGridCell(
                                earnings = value,
                                isCurrentTime = isCurrent
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleGridCell(earnings: Double, isCurrentTime: Boolean) {
    // Color stops for gradient: 0=black, 8=red, 25=yellow, 45=green, 90=excellentBlue
    val excellentBlue = Color(0.2f, 0.6f, 1f)
    val stops = listOf(
        0.0 to Color.Black,
        8.0 to Color.Red,
        25.0 to Color.Yellow,
        45.0 to Color.Green,
        90.0 to excellentBlue
    )
    // Compute fill color based on earnings by interpolating between nearest stops
    val fillColor = if (earnings <= stops.first().first) {
        stops.first().second
    } else {
        stops.zipWithNext().firstOrNull { (l, u) -> earnings <= u.first }?.let { (l, u) ->
            val (lVal, lCol) = l
            val (uVal, uCol) = u
            val t = ((earnings - lVal) / (uVal - lVal)).toFloat().coerceIn(0f,1f)
            lerp(lCol, uCol, t)
        } ?: stops.last().second
    }
    // Border highlights current hour
    val borderWidth = if (isCurrentTime) 2.dp else 0.5.dp
    val borderColor = if (isCurrentTime) Color.Blue else Color.Gray
    Box(
        modifier = Modifier
            .size(25.dp)
            .background(fillColor)
            .border(borderWidth, borderColor)
    ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BoltAssistTheme {
        GraphScreen(onStartFloatingWindow = {})
    }
}


