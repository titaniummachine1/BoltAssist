package com.example.boltassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val mapViewState = remember {
        mutableStateOf(
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(12.0)
                controller.setCenter(GeoPoint(52.2297, 21.0122)) // Default to Warsaw
            }
        )
    }

    AndroidView(
        factory = { mapViewState.value },
        modifier = Modifier.fillMaxSize()
    )
} 