package com.example.boltassist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: Ride): Long

    @Query("SELECT * FROM rides ORDER BY startTs DESC")
    suspend fun getAllRides(): List<Ride>

    @Query("DELETE FROM rides")
    suspend fun clearAll()
} 