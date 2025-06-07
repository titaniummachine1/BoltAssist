package com.example.boltassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.boltassist.ui.theme.BoltAssistTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BoltAssistTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WeekGrid()
                }
            }
        }
    }
}

@Composable
fun WeekGrid() {
    var isRecording by remember { mutableStateOf(false) }
    var currentEarnings by remember { mutableStateOf(0) }
    var selectedFolder by remember { mutableStateOf("Not Selected") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 7 rows (days) x 24 columns (hourly slots)
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
                            hourIndex = hourIndex
                        )
                    }
                }
            }
        }
        
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
        
        // Trip Recording Controls
        TripControls(
            isRecording = isRecording,
            currentEarnings = currentEarnings,
            selectedFolder = selectedFolder,
            onRecordingToggle = { isRecording = !isRecording },
            onEarningsChange = { change -> 
                currentEarnings = (currentEarnings + change).coerceAtLeast(0)
            },
            onFolderSelect = { selectedFolder = "Folder Selected" }
        )
    }
}

@Composable
fun GridCell(dayIndex: Int, hourIndex: Int) {
    // All cells start as "No Data" (black) - will be filled naturally with trip data
    Box(
        modifier = Modifier
            .size(25.dp)
            .background(Color.Black)
            .border(0.5.dp, Color.Gray)
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

@Composable
fun TripControls(
    isRecording: Boolean,
    currentEarnings: Int,
    selectedFolder: String,
    onRecordingToggle: () -> Unit,
    onEarningsChange: (Int) -> Unit,
    onFolderSelect: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Folder Selection
        Button(
            onClick = onFolderSelect,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedFolder == "Not Selected") Color.Gray else Color.Blue
            )
        ) {
            Text("Select Storage Folder: $selectedFolder")
        }
        
        // Recording Status
        Text(
            text = if (isRecording) "Recording Trip..." else "Ready to Record",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isRecording) Color.Red else Color.Gray
        )
        
        // Earnings Control
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { onEarningsChange(-5) },
                enabled = currentEarnings > 0
            ) {
                Text("-5", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            
            Text(
                text = "${currentEarnings} PLN",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(80.dp)
            )
            
            IconButton(onClick = { onEarningsChange(5) }) {
                Text("+5", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        // Start/Stop Button
        Button(
            onClick = onRecordingToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color.Red else Color.Green
            ),
            modifier = Modifier.size(120.dp, 48.dp)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isRecording) "STOP" else "START")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BoltAssistTheme {
        WeekGrid()
    }
}


