package com.example.boltassist.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.res.Configuration
import com.example.boltassist.TripManager
import com.example.boltassist.TripDataManager
import kotlinx.coroutines.delay
import kotlin.math.max

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
                android.util.Log.d("BoltAssist", "Time change detected: $currentTime -> $newTime")
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
                                max(actualValue, predictedValue)
                            }
                            actualValue > 0.0 -> actualValue // Only actual data
                            predictedValue > 0.0 -> predictedValue // Only prediction (lowered threshold)
                            else -> 0.0 // No data
                        }
                        
                        GridCell(
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
                                    max(actualValue, predictedValue)
                                }
                                actualValue > 0.0 -> actualValue // Only actual data
                                predictedValue > 0.0 -> predictedValue // Only prediction (lowered threshold)
                                else -> 0.0 // No data
                            }
                            
                            GridCell(
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