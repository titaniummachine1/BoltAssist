package com.example.boltassist

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Ride::class, EdgeStat::class, SupplyDemand::class, SlotStat::class], 
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun edgeStatDao(): EdgeStatDao
    abstract fun supplyDemandDao(): SupplyDemandDao
    abstract fun slotStatDao(): SlotStatDao

    companion object {
        @Volatile 
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bolt_assist_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
} 