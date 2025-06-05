package com.example.boltassist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object StatEngine {
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // Called by TrackRecorder after map-matching each segment
    fun updateEdgeStats(edgeId: Int, lengthM: Float, travelS: Float, timestamp: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val slot = cal.get(Calendar.HOUR_OF_DAY) * 2 + (if (cal.get(Calendar.MINUTE) < 30) 0 else 1)
        val dayType = DayTypeClassifier.classify(cal)

        val key = "${edgeId}|${dayType}|${slot}"
        ioScope.launch {
            val db = AppDatabase.getDatabase(BoltApp.appContext!!)
            val dao = db.edgeStatDao()
            val existing = dao.get(key)
            val speedKmh = (lengthM / travelS) * 3.6f
            if (existing == null) {
                val newStat = EdgeStat(key, edgeId, dayType, slot, speedKmh, 1)
                dao.insert(newStat)
            } else {
                val newHits = existing.hits + 1
                val alpha = 1f / newHits
                val newAvg = (1 - alpha) * existing.avgSpeed + alpha * speedKmh
                val updated = existing.copy(avgSpeed = newAvg, hits = newHits)
                dao.insert(updated)
            }
        }
    }

    // Called by OverlayService when KASA clicked
    @Suppress("UNUSED_PARAMETER")
    fun findBestEdge(curLat: Double, curLon: Double, radiusKm: Float, onResult: (EdgeAdvice?) -> Unit) {
        ioScope.launch {
            val cal = Calendar.getInstance()
            val slot = cal.get(Calendar.HOUR_OF_DAY) * 2 + (if (cal.get(Calendar.MINUTE) < 30) 0 else 1)
            val dayType = DayTypeClassifier.classify(cal)
            val db = AppDatabase.getDatabase(BoltApp.appContext!!)

            // For now, generate some mock candidate edges
            val candidateEdges = generateMockCandidateEdges(curLat, curLon, radiusKm)

            var bestAdvice: EdgeAdvice? = null
            candidateEdges.forEach { edgeId ->
                // Get historical pph for cluster
                val clusterId = latLonToGridId(curLat, curLon) // simplified
                val slotStat = db.slotStatDao().get(clusterId, dayType, slot)
                val pphHist = slotStat?.pph ?: 10f  // fallback = 10 zł/h if no data

                // Live supply/demand
                val sdKey = "$clusterId|$dayType|$slot"
                val sd = db.supplyDemandDao().get(sdKey)
                val surgeVal = sd?.surge ?: 1f
                val demandBoostVal = sd?.demandBoost ?: 0
                val demandBoost = 1f + 0.3f * demandBoostVal
                val driversCount = sd?.drivers ?: 0
                val winProb = 1f / (1f + driversCount)

                val ephEdge = pphHist * surgeVal * demandBoost * winProb

                // Compute ETA to this edge start
                val edgeStart = MapMatcherHelper.getEdgeStartLatLng(edgeId)
                val eta = MapMatcherHelper.computeEta(curLat, curLon, edgeStart.first, edgeStart.second, dayType, slot)

                val score = ephEdge / eta

                val advice = EdgeAdvice(edgeId, edgeStart, ephEdge, eta, score, radiusKm.toInt())
                if (bestAdvice == null || advice.score > bestAdvice!!.score) {
                    bestAdvice = advice
                }
            }

            onResult(bestAdvice)
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private fun generateMockCandidateEdges(lat: Double, lon: Double, radiusKm: Float): List<Int> {
        // Generate some mock edges around the current location
        return (1..10).map { i ->
            ((lat + i * 0.001) * 1000 + (lon + i * 0.001) * 1000).toInt()
        }
    }
}

data class EdgeAdvice(
    val edgeId: Int,
    val latLon: Pair<Double, Double>,
    val eph: Float,
    val etaMin: Float,
    val score: Float,
    val radiusKm: Int
)

fun latLonToGridId(lat: Double, lon: Double): String {
    // Simple grid conversion - can be refined later
    val gridX = ((lat - 53.7) * 1000 / 400).toInt()
    val gridY = ((lon - 20.4) * 1000 / 400).toInt()
    return "$gridX-$gridY"
} 