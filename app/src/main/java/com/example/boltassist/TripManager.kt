package com.example.boltassist

import android.content.Context
import android.location.Location
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class TripData(
    val id: String = UUID.randomUUID().toString(),
    val startTime: String,
    val endTime: String? = null,
    val durationMinutes: Int = 0,
    val startStreet: String = "Unknown",
    val endStreet: String = "Unknown",
    val earningsPLN: Int = 0,
    val startLocation: LocationData? = null,
    val endLocation: LocationData? = null
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
)

class TripManager(private val context: Context) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var storageDirectory: File? = null
    private var currentTrip: TripData? = null
    private var tripStartTime: Long = 0
    
    fun setStorageDirectory(directory: File) {
        storageDirectory = directory
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }
    
    fun startTrip(location: Location?): TripData {
        tripStartTime = System.currentTimeMillis()
        val startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        currentTrip = TripData(
            startTime = startTime,
            startLocation = location?.let { 
                LocationData(it.latitude, it.longitude) 
            },
            startStreet = getStreetFromLocation(location) // TODO: Implement OSM reverse geocoding
        )
        
        return currentTrip!!
    }
    
    fun stopTrip(location: Location?, earnings: Int): TripData? {
        val trip = currentTrip ?: return null
        
        val endTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val durationMinutes = ((System.currentTimeMillis() - tripStartTime) / 60000).toInt()
        
        val completedTrip = trip.copy(
            endTime = endTime,
            durationMinutes = durationMinutes,
            earningsPLN = earnings,
            endLocation = location?.let { 
                LocationData(it.latitude, it.longitude) 
            },
            endStreet = getStreetFromLocation(location) // TODO: Implement OSM reverse geocoding
        )
        
        saveTripToFile(completedTrip)
        currentTrip = null
        
        return completedTrip
    }
    
    private fun getStreetFromLocation(location: Location?): String {
        // TODO: Implement OpenStreetMap reverse geocoding
        // For now, return coordinates as placeholder
        return location?.let { 
            "Lat: %.4f, Lng: %.4f".format(it.latitude, it.longitude) 
        } ?: "Unknown"
    }
    
    private fun saveTripToFile(trip: TripData) {
        val directory = storageDirectory ?: return
        
        val fileName = "trip_${trip.id}.json"
        val file = File(directory, fileName)
        
        try {
            file.writeText(gson.toJson(trip))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getAllTrips(): List<TripData> {
        val directory = storageDirectory ?: return emptyList()
        val trips = mutableListOf<TripData>()
        
        directory.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
            try {
                val json = file.readText()
                val trip = gson.fromJson(json, TripData::class.java)
                trips.add(trip)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return trips.sortedByDescending { it.startTime }
    }
    
    fun isRecording(): Boolean = currentTrip != null
} 