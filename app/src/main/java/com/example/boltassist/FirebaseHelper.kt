package com.example.boltassist

import com.google.firebase.database.FirebaseDatabase

object FirebaseHelper {
    private val database = FirebaseDatabase.getInstance()
    private val ridesRef = database.getReference("rides")
    private val supplyDemandRef = database.getReference("supply_demand")

    fun pushRide(ride: Ride) {
        // key by timestamp+ID to ensure uniqueness
        val key = "${ride.startTs}_${ride.id}"
        ridesRef.child(key).setValue(ride)
    }
    
    fun pushSupplyDemand(sd: SupplyDemand) {
        supplyDemandRef.child(sd.key).setValue(sd)
    }
} 