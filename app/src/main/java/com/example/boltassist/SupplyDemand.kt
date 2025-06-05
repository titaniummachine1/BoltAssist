package com.example.boltassist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "supply_demand")
data class SupplyDemand(
    @PrimaryKey val key: String, // = "$gridId|$dayType|$slot"
    val gridId: String,
    val dayType: String,
    val slot: Int,
    val surge: Float,
    val drivers: Int,
    val demandBoost: Int,
    val ts: Long
) 