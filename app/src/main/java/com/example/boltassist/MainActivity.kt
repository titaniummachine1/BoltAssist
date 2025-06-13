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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import android.location.LocationManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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
    
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // After permission granted retry starting floating window
            startFloatingWindowWithChecks()
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
                // Shared edit mode state
                var editMode by remember { mutableStateOf(false) }
                // Init TripManager and load persisted path (only once)
                var initComplete by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (initComplete) {
                        android.util.Log.d("BoltAssist", "MAIN: Skipping LaunchedEffect - already initialized")
                        return@LaunchedEffect
                    }
                    
                    android.util.Log.d("BoltAssist", "MAIN: MainActivity LaunchedEffect - TripManager already initialized: ${TripManager.isInitialized()}")
                    
                    if (!TripManager.isInitialized()) {
                    TripManager.initialize(this@MainActivity)
                        android.util.Log.d("BoltAssist", "MAIN: TripManager initialized with ${TripManager.tripsCache.size} trips")
                    } else {
                        android.util.Log.d("BoltAssist", "MAIN: TripManager already initialized with ${TripManager.tripsCache.size} trips")
                    }
                    
                    val prefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
                    val savedPath = prefs.getString("storage_path", null)
                    if (savedPath != null) {
                        android.util.Log.d("BoltAssist", "MAIN: Setting storage URI: $savedPath")
                        TripManager.setStorageDirectoryUri(Uri.parse(savedPath))
                        displayPath = Uri.parse(savedPath).lastPathSegment ?: savedPath
                    } else {
                        val defaultDir = getExternalFilesDir(null)?.resolve("BoltAssist")
                            ?: filesDir.resolve("BoltAssist")
                        android.util.Log.d("BoltAssist", "MAIN: Setting default storage directory: ${defaultDir.absolutePath}")
                        TripManager.setStorageDirectory(defaultDir)
                        displayPath = "Default App Directory"
                    }
                    
                    initComplete = true
                    android.util.Log.d("BoltAssist", "MAIN: Final cache size after storage setup: ${TripManager.tripsCache.size}")
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
                    Scaffold { inner ->
                        Box(Modifier.padding(inner)) {
                            when (selectedScreen) {
                                Screen.Graph -> GraphScreen(
                                    onStartFloatingWindow = { requestOverlayPermissionAndStart() },
                                    editMode = editMode
                                )
                                Screen.Map -> MapScreen()
                                Screen.Settings -> SettingsScreen(
                                    displayPath = displayPath,
                                    editMode = editMode,
                                    onEditModeChange = { editMode = it },
                                    onFolderSelect = {
                                        onFolderSelected = { selectedPath ->
                                            displayPath = Uri.parse(selectedPath).lastPathSegment ?: selectedPath
                                        }
                                        directoryPickerLauncher.launch(null)
                                    }
                                )
                            }
                            
                            // Menu button overlaid on top
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
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
        startFloatingWindowWithChecks()
    }

    // Ensures permission & GPS enabled before actually starting service
    private fun startFloatingWindowWithChecks() {
        // 1. Permission check
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            // request both permissions
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }

        // 2. GPS / location services enabled check
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsOn) {
            // Prompt user to enable location settings
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {}
            return
        }

        // All good – start service
        val intent = Intent(this, FloatingWindowService::class.java)
        selectedStoragePath?.let { intent.putExtra("storage_path", it) }
        startService(intent)
    }

    // Define navigation targets
    private enum class Screen(val title: String) { Graph("Graph"), Map("Map"), Settings("Settings") }
}

@Composable
fun GraphScreen(onStartFloatingWindow: () -> Unit, editMode: Boolean) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Box(Modifier.weight(1f)) { WeeklyEarningsGrid(editMode = editMode) }
        Spacer(Modifier.height(8.dp))
        
        // Main button row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartFloatingWindow, Modifier.weight(1f)) { Text("Begin") }
        }
        
        if (editMode) {
            Text(
                "Edit Mode: Click cells to add a random trip • Long press to clear",
                fontSize = 12.sp,
                color = Color.Red,
                modifier = Modifier.padding(top = 8.dp)
            )
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

@Composable
fun WeeklyEarningsGrid(editMode: Boolean = false) {
    // Get data version to trigger recomposition reliably
    val dataVersion = TripManager.dataVersion
    
    val actualGrid by remember(dataVersion) { mutableStateOf(TripManager.getWeeklyGrid()) }
    val kalmanGrid by remember(dataVersion) { mutableStateOf(TripDataManager.getAdvancedPredictionGrid()) }
    
    // Track system time to update highlight immediately and every 2 seconds for responsiveness
    var currentTime by remember { mutableStateOf(TripManager.getCurrentTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L) // Update every 2 seconds for faster response to time travel
            val newTime = TripManager.getCurrentTime()
            if (newTime != currentTime) {
                android.util.Log.d("BoltAssist", "Time change dethetected: $currentTime -> $newTime")
                currentTime = newTime
            }
        }
    }
    // Compute header index for highlighting – direct mapping 0-23.
    // The header shows labels 1-24, where label 1 represents 00:00-01:00, label 2 represents
    // 01:00-02:00, …, label 24 represents 23:00-24:00.  Therefore the calendar hour (0-23)
    // can be used directly: hour 0 → column 0 (label 1), hour 10 → column 10 (label 11), etc.
    // DO NOT shift this value; using raw 0-23 is the only alignment that works across the
    // entire 24-hour cycle (midnight included).
    val highlightIndex = currentTime.second

    LaunchedEffect(dataVersion) {
        android.util.Log.d("BoltAssist", "Grid recomposing due to data version change: $dataVersion")
        // Force update current time when trips change (like when time traveling)
        currentTime = TripManager.getCurrentTime()
        
        // Log grid data for debugging
        android.util.Log.d("BoltAssist", "Actual grid sample: [0][8]=${actualGrid[0][8]}, [1][8]=${actualGrid[1][8]}")
        android.util.Log.d("BoltAssist", "Prediction grid sample: [0][8]=${kalmanGrid[0][8]}, [1][8]=${kalmanGrid[1][8]}")
    }
    
    // Listen to lifecycle events to refresh time when app comes to foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Immediately update time when app resumes (handles time travel)
                currentTime = TripManager.getCurrentTime()
                android.util.Log.d("BoltAssist", "App resumed - refreshed time.")
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
                
                // Hour numbers 0-23
                repeat(24) { hour ->
                    Box(
                        modifier = Modifier.size(25.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$hour",
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
                        val isCurrent = day == currentTime.first && hour == highlightIndex
                        
                                                    // FIXED: Show the HIGHER of actual vs predicted to capture earning potential
                        val actualValue = actualGrid[day][hour]
                        val predictedValue = kalmanGrid[day][hour]
                        
                        val value = when {
                            actualValue > 0.0 && predictedValue > 0.0 -> {
                                // Both exist - show the higher value (potential vs reality)
                                maxOf(actualValue, predictedValue)
                            }
                            actualValue > 0.0 -> actualValue // Only actual data
                            predictedValue > 0.0 -> predictedValue // Only prediction (lowered threshold)
                            else -> 0.0 // No data
                        }
                            SimpleGridCell(
                                earnings = value,
                                isCurrentTime = isCurrent,
                                editMode = editMode,
                                onEditClick = { add -> 
                                    if (add) {
                                        TripDataManager.addTripForDayHour(day, hour)
                                    } else {
                                        TripDataManager.clearTripsForDayHour(day, hour)
                                    }
                                }
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
                                text = "$hour",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                        
                        // Day cells for this hour
                        repeat(7) { day ->
                            val isCurrent = day == currentTime.first && hour == highlightIndex
                            
                            // FIXED: Show the HIGHER of actual vs predicted to capture earning potential
                            val actualValue = actualGrid[day][hour]
                            val predictedValue = kalmanGrid[day][hour]
                            
                            val value = when {
                                actualValue > 0.0 && predictedValue > 0.0 -> {
                                    // Both exist - show the higher value (potential vs reality)
                                    maxOf(actualValue, predictedValue)
                                }
                                actualValue > 0.0 -> actualValue // Only actual data
                                predictedValue > 0.0 -> predictedValue // Only prediction (lowered threshold)
                                else -> 0.0 // No data
                            }
                            SimpleGridCell(
                                earnings = value,
                                isCurrentTime = isCurrent,
                                editMode = editMode,
                                onEditClick = { add -> 
                                    if (add) {
                                        TripDataManager.addTripForDayHour(day, hour)
                                    } else {
                                        TripDataManager.clearTripsForDayHour(day, hour)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleGridCell(
    earnings: Double, 
    isCurrentTime: Boolean,
    editMode: Boolean = false,
    onEditClick: ((Boolean) -> Unit)? = null
) {
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
        stops.zipWithNext().firstOrNull { (_, u) -> earnings <= u.first }?.let { (l, u) ->
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
            .then(
                if (editMode && onEditClick != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onEditClick(true) }, // Click to add a trip
                            onLongPress = { onEditClick(false) } // Long press to clear
                        )
                    }
                } else Modifier
            )
    ) {
        // Show text for earnings > 0.1 PLN (lowered threshold to see small predictions)
        if (earnings >= 0.1) {
            Text(
                text = "${earnings.toInt()}",
                fontSize = 8.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // No text for 0 or very small values - just pure black/colored cell
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
        GraphScreen(onStartFloatingWindow = {}, editMode = false)
    }
}


