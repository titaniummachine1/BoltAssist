package com.example.boltassist

import java.text.SimpleDateFormat
import java.util.*

// --- Core Data Models ---

data class TripData(
    val id: String = UUID.randomUUID().toString(),
    val startTime: String,
    val endTime: String? = null,
    val durationMinutes: Int = 0,
    val startStreet: String = "Unknown",
    val endStreet: String = "Unknown",
    val earningsPLN: Int = 0,
    val startLocation: LocationData? = null,
    val endLocation: LocationData? = null,
    val isQuick: Boolean = false
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
)

// --- JSON Persistence Models ---

/**
 * Represents the nested structure for saving trips to JSON.
 * This is the modern format the app uses for storage.
 */
data class TripRecord(
    val info: TripInfo,
    val start: TripEndpoint? = null,
    val end: TripEndpoint? = null
)

data class TripInfo(
    val id: String,
    val durationMinutes: Int,
    val earningsPLN: Int,
    val isQuick: Boolean = false
)

data class TripEndpoint(
    val time: String? = null,
    val street: String = "Unknown",
    val location: LocationData? = null
)


// --- Prediction and Analysis Models ---

data class KalmanState(
    var estimate: Double = 5.0,
    var errorCovariance: Double = 25.0,
    val processNoise: Double = 1.0,
    val measurementNoise: Double = 4.0
)

// --- Conversion Helpers ---

internal fun TripData.toRecord(): TripRecord = TripRecord(
    info = TripInfo(id, durationMinutes, earningsPLN, isQuick),
    start = TripEndpoint(startTime, startStreet, startLocation),
    end = TripEndpoint(endTime, endStreet, endLocation)
)

internal fun TripRecord.toTripData(): TripData = TripData(
    id = info.id,
    startTime = start?.time ?: "",
    endTime = end?.time,
    durationMinutes = info.durationMinutes,
    startStreet = start?.street ?: "Unknown",
    endStreet = end?.street ?: "Unknown",
    earningsPLN = info.earningsPLN,
    startLocation = start?.location,
    endLocation = end?.location,
    isQuick = info.isQuick
) 