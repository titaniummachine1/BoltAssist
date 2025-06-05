package com.example.boltassist

import androidx.room.Entity

@Entity(tableName = "slot_stats", primaryKeys = ["gridId", "dayType", "slot"])
data class SlotStat(
    val gridId: String,
    val dayType: String,
    val slot: Int,
    val pph: Float,
    val hits: Int
) 