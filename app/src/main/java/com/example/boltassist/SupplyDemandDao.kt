package com.example.boltassist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SupplyDemandDao {
    @Query("SELECT * FROM supply_demand WHERE key = :key")
    suspend fun get(key: String): SupplyDemand?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sd: SupplyDemand)
} 