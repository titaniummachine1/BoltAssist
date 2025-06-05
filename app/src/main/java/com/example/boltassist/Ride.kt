package com.example.boltassist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTs: Long,
    val endTs: Long,
    val amount: Float,
    val pickupLat: Double,
    val pickupLon: Double,
    val pickupEdge: Int?,
    val dropLat: Double?,
    val dropLon: Double?,
    val dropEdge: Int?
) 