package com.example.boltassist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "edge_stats")
data class EdgeStat(
    @PrimaryKey val key: String,    // = "$edgeId|$dayType|$slot"
    val edgeId: Int,
    val dayType: String,
    val slot: Int,
    val avgSpeed: Float,
    val hits: Int
) 