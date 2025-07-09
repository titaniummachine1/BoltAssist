package com.example.boltassist

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Single source of truth for trip data.
 * Handles loading from/saving to the JSON database file and keeps an in-memory cache.
 * This decouples the data source from the application's business logic (TripManager, etc.).
 */
object TripRepository {
    private lateinit var context: Context
    private var storageDir: File? = null
    private var storageTreeUri: Uri? = null

    // The single source of truth for trip data in memory
    val tripsCache: SnapshotStateList<TripData> = mutableStateListOf()

    // These flags help with migrating old data formats
    internal var lastParsedWasOldFormat = false

    fun isInitialized(): Boolean = ::context.isInitialized

    fun initialize(appContext: Context) {
        if (isInitialized()) return
        context = appContext.applicationContext
        Log.d("BoltAssist", "TripRepository initialized.")
        reload()
    }

    fun setStorageDirectory(directory: File) {
        storageDir = directory
        storageTreeUri = null // Clear URI if a direct path is set
        Log.d("BoltAssist", "Repository storage set to directory: ${directory.absolutePath}")
        reload()
    }

    fun setStorageDirectoryUri(uri: Uri) {
        storageTreeUri = uri
        storageDir = null // Clear direct path if URI is set
        Log.d("BoltAssist", "Repository storage set to URI: $uri")
        reload()
    }

    fun addTrip(trip: TripData) {
        tripsCache.add(0, trip) // Add to top for chronological order
    }

    fun replaceTrip(index: Int, trip: TripData) {
        if (index in 0 until tripsCache.size) {
            tripsCache[index] = trip
        }
    }

    fun getAllTrips(): List<TripData> {
        return tripsCache.toList()
    }

    fun updateCache(newTrips: List<TripData>) {
        tripsCache.clear()
        tripsCache.addAll(newTrips.sortedByDescending { it.startTime })
    }

    fun reload() {
        if (storageTreeUri != null) {
            loadTripsFromUri()
        } else {
            loadTripsFromFile()
        }
    }

    fun save() {
        if (storageTreeUri != null) {
            saveTripsToUri()
        } else {
            saveTripsToFile()
        }
    }

    private fun saveTripsToFile() {
        val file = getDatabaseFile() ?: return
        try {
            FileOutputStream(file).use { outputStream ->
                val records = tripsCache.map { it.toRecord() }
                val json = GsonBuilder().setPrettyPrinting().create().toJson(records)
                outputStream.write(json.toByteArray())
                Log.d("BoltAssist", "Saved ${tripsCache.size} trips to file: ${file.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e("BoltAssist", "Failed to save trips to file", e)
        }
    }

    private fun loadTripsFromFile() {
        val file = getDatabaseFile() ?: return
        if (!file.exists()) {
            Log.d("BoltAssist", "No trips file found at path: ${file.absolutePath}")
            return
        }
        try {
            val json = file.readText()
            if (json.isNotBlank()) {
                val trips = parseTripsLenient(json)
                updateCache(trips)
                 if (lastParsedWasOldFormat) {
                    Log.d("BoltAssist", "LOAD FILE: Legacy format detected, migrating on next save.")
                }
                Log.d("BoltAssist", "Loaded ${tripsCache.size} trips from file.")
            }
        } catch (e: Exception) {
            Log.e("BoltAssist", "Failed to load/parse trips from file", e)
        }
    }

    private fun saveTripsToUri() {
        val uri = storageTreeUri ?: return
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return
        val fileDoc = tree.findFile("trips_database.json")
            ?: tree.createFile("application/json", "trips_database.json")

        fileDoc?.uri?.let { fileUri ->
            try {
                context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                    val records = tripsCache.map { it.toRecord() }
                    val json = GsonBuilder().setPrettyPrinting().create().toJson(records)
                    outputStream.write(json.toByteArray())
                    Log.d("BoltAssist", "Saved ${tripsCache.size} trips to URI: $fileUri")
                }
            } catch (e: Exception) {
                Log.e("BoltAssist", "Failed to save trips to URI", e)
            }
        }
    }

    private fun loadTripsFromUri() {
        val uri = storageTreeUri ?: return
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return
        val fileDoc = tree.findFile("trips_database.json") ?: return

        try {
            context.contentResolver.openInputStream(fileDoc.uri)?.use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }
                if (json.isNotBlank()) {
                    val trips = parseTripsLenient(json)
                    updateCache(trips)
                    if (lastParsedWasOldFormat) {
                         Log.d("BoltAssist", "LOAD URI: Legacy format detected, migrating on next save.")
                    }
                    Log.d("BoltAssist", "Loaded ${tripsCache.size} trips from URI.")
                }
            }
        } catch (e: Exception) {
            Log.e("BoltAssist", "Failed to load/parse trips from URI", e)
        }
    }

    private fun getDatabaseFile(): File? {
        val dir = storageDir ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e("BoltAssist", "Cannot create storage directory: $dir")
            return null
        }
        return File(dir, "trips_database.json")
    }

    internal fun parseTripsLenient(json: String): List<TripData> {
        val gson = Gson()
        lastParsedWasOldFormat = false

        // First, try parsing as the new, correct TripRecord structure
        try {
            val recordListType = object : TypeToken<List<TripRecord>>() {}.type
            val records: List<TripRecord> = gson.fromJson(json, recordListType)
            // Check if the parsed list is not null and has the expected structure
            if (records.all { it.info != null }) {
                Log.d("BoltAssist", "Parser: Detected new TripRecord format.")
                return records.map { it.toTripData() }
            }
        } catch (e: JsonSyntaxException) {
            // This is expected if the format is the old one.
        }

        // If that fails, fall back to the old flat TripData structure
        try {
            val tripListType = object : TypeToken<List<TripData>>() {}.type
            val trips: List<TripData> = gson.fromJson(json, tripListType)
            Log.w("BoltAssist", "Parser: Detected old TripData format. Will migrate on next save.")
            lastParsedWasOldFormat = true
            return trips
        } catch (e: JsonSyntaxException) {
            Log.e("BoltAssist", "JSON parsing failed for both new and old formats.", e)
        }

        return emptyList()
    }
} 