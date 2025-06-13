package com.example.boltassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.boltassist.ui.components.WeeklyEarningsGrid

@Composable
fun GraphScreen(onStartFloatingWindow: () -> Unit, editMode: Boolean) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Box(Modifier.weight(1f)) { 
            WeeklyEarningsGrid(editMode = editMode) 
        }
        Spacer(Modifier.height(8.dp))
        
        // Main button row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartFloatingWindow, Modifier.weight(1f)) { 
                Text("Begin") 
            }
        }
        
        if (editMode) {
            Text(
                "Edit Mode: Click cells to add a random trip • Long press to clear",
                fontSize = 12.sp,
                color = Color.Red,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        // Legend
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LegendItem(Color.Black, "0 PLN")
            LegendItem(Color.Red, "8 PLN")
            LegendItem(Color.Yellow, "25 PLN")
            LegendItem(Color.Green, "45 PLN")
            LegendItem(Color(0.2f, 0.6f, 1f), "90+ PLN")
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