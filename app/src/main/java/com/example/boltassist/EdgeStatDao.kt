package com.example.boltassist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EdgeStatDao {
    @Query("SELECT * FROM edge_stats WHERE key = :key")
    suspend fun get(key: String): EdgeStat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: EdgeStat)
} 