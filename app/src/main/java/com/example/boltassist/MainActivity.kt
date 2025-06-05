package com.example.boltassist

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.boltassist.ui.theme.BoltAssistTheme


class MainActivity : ComponentActivity() {
    private var hasLocationPermission by mutableStateOf(false)
    private var hasOverlayPermission by mutableStateOf(false)
    
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasLocationPermission = isGranted
        if (!isGranted) {
            Toast.makeText(this, "Location permission is required for GPS tracking", Toast.LENGTH_LONG).show()
        } else {
            Log.d("MainActivity", "Location permission granted")
        }
    }
    
    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (!hasOverlayPermission) {
            Toast.makeText(this, "Overlay permission is required for HUD display", Toast.LENGTH_LONG).show()
        } else {
            Log.d("MainActivity", "Overlay permission granted")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check initial permissions
        checkPermissions()
        
        setContent {
            BoltAssistTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionAwareUI()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Re-check permissions when user returns to app (e.g., from settings)
        checkPermissions()
    }
    
    private fun checkPermissions() {
        // Check location permission
        hasLocationPermission = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        // Check overlay permission
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        
        Log.d("MainActivity", "Location permission: $hasLocationPermission, Overlay permission: $hasOverlayPermission")
    }

    @Composable
    fun PermissionAwareUI() {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                !hasLocationPermission -> {
                    PermissionRequestUI(
                        title = "Location Permission Required",
                        description = "BoltAssist needs location access to track rides and provide navigation guidance.",
                        buttonText = "Grant Location Permission"
                    ) {
                        requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
                !hasOverlayPermission -> {
                    PermissionRequestUI(
                        title = "Overlay Permission Required", 
                        description = "BoltAssist needs overlay permission to display the floating HUD while using other apps.",
                        buttonText = "Grant Overlay Permission"
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))
                            requestOverlayPermissionLauncher.launch(intent)
                        }
                    }
                }
                else -> {
                    // Both permissions granted - show main UI
                    MainAppUI()
                }
            }
        }
    }
    
    @Composable
    fun PermissionRequestUI(
        title: String,
        description: String, 
        buttonText: String,
        onButtonClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(onClick = onButtonClick) {
                Text(buttonText)
            }
        }
    }
    
    @Composable
    fun MainAppUI() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "BoltAssist Ready",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = "All permissions granted. You can now launch the HUD overlay.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(onClick = {
                try {
                    Log.d("MainActivity", "Starting OverlayService")
                    val intent = Intent(this@MainActivity, OverlayService::class.java)
                    startService(intent)
                    Toast.makeText(this@MainActivity, "HUD Overlay launched", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to start OverlayService", e)
                    Toast.makeText(this@MainActivity, "Failed to start overlay: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }) {
                Text("Launch HUD Overlay")
            }
        }
    }
}


