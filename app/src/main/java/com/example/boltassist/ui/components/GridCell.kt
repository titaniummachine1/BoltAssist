package com.example.boltassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GridCell(
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