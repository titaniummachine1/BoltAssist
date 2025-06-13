package com.example.boltassist

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.boltassist.ui.theme.BoltAssistTheme
import com.example.boltassist.ui.screens.GraphScreen
import com.example.boltassist.ui.screens.MapScreen
import com.example.boltassist.ui.screens.SettingsScreen
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.LocationManager

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
                    
                    val sharedPrefs = getSharedPreferences("BoltAssist", MODE_PRIVATE)
                    val savedPath = sharedPrefs.getString("storage_path", null)
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
    private enum class Screen(val title: String) { 
        Graph("Graph"), 
        Map("Map"), 
        Settings("Settings") 
    }
}

// All UI composables have been moved to separate files:
// - GraphScreen -> ui/screens/GraphScreen.kt
// - MapScreen -> ui/screens/MapScreen.kt
// - SettingsScreen -> ui/screens/SettingsScreen.kt
// - WeeklyEarningsGrid -> ui/components/WeeklyEarningsGrid.kt
// - GridCell -> ui/components/GridCell.kt


