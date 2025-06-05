package com.example.boltassist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SlotStatDao {
    @Query("SELECT * FROM slot_stats WHERE gridId=:g AND dayType=:d AND slot=:s")
    suspend fun get(g: String, d: String, s: Int): SlotStat?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: SlotStat)
} 