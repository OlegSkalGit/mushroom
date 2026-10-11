package com.olegskal.mushroom.address

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

data class SettlementItem(
    val lat: Double,
    val lon: Double,
    val name: String,
    val placeType: String,
    val adminLevel: String
)

class SpatialSettlementIndex(
    settlements: List<SettlementItem>,
    private val fallbackRegion: String
) {
    private val grid = mutableMapOf<Pair<Int, Int>, MutableList<SettlementItem>>()
    private val cities = mutableListOf<SettlementItem>()
    private val validAll = mutableListOf<SettlementItem>()
    private val cache = mutableMapOf<Pair<Long, Long>, String>()

    init {
        for (item in settlements) {
            val ptype = item.placeType
            val al = item.adminLevel
            if (ptype in listOf("borough", "district", "subdistrict", "county") || al in listOf("6", "10")) {
                continue
            }
            if (ptype in listOf("city", "town", "village", "hamlet")) {
                validAll.add(item)
            }
            if (ptype == "city") {
                cities.add(item)
            } else {
                val bLat = (item.lat * 10.0).roundToInt()
                val bLon = (item.lon * 10.0).roundToInt()
                for (dLat in -1..1) {
                    for (dLon in -1..1) {
                        val cell = Pair(bLat + dLat, bLon + dLon)
                        grid.getOrPut(cell) { mutableListOf() }.add(item)
                    }
                }
            }
        }
    }

    fun resolve(ptLat: Double, ptLon: Double): String {
        val cKey = Pair((ptLat * 1000.0).toLong(), (ptLon * 1000.0).toLong())
        cache[cKey]?.let { return it }

        val scored = mutableListOf<Pair<Double, String>>()
        val radLat = Math.toRadians(ptLat)
        val cosLat = cos(radLat)

        // 1. City within 18 km
        for (c in cities) {
            val distKm = hypot(ptLat - c.lat, (ptLon - c.lon) * cosLat) * 111.0
            if (distKm <= 18.0) {
                scored.add(Pair(0.2 + (distKm * 0.10), c.name))
            }
        }

        // 2. Local settlement candidates in grid
        val cell = Pair((ptLat * 10.0).roundToInt(), (ptLon * 10.0).roundToInt())
        val candidates = grid[cell] ?: emptyList()
        for (s in candidates) {
            val distKm = hypot(ptLat - s.lat, (ptLon - s.lon) * cosLat) * 111.0
            when {
                s.placeType == "town" && distKm <= 7.0 -> scored.add(Pair(0.3 + (distKm * 0.25), s.name))
                s.placeType == "village" && distKm <= 3.5 -> scored.add(Pair(0.4 + (distKm * 0.35), s.name))
                (s.placeType == "hamlet" || s.placeType == "isolated_dwelling") && distKm <= 1.5 -> scored.add(Pair(1.2 + (distKm * 0.50), s.name))
                s.placeType == "suburb" && distKm <= 2.5 -> scored.add(Pair(2.0 + distKm, s.name))
                (s.placeType == "municipality" || s.adminLevel in listOf("7", "8")) && distKm <= 8.0 -> scored.add(Pair(3.0 + (distKm * 0.4), s.name))
            }
        }

        if (scored.isNotEmpty()) {
            scored.sortBy { it.first }
            val res = scored.first().second
            cache[cKey] = res
            return res
        }

        if (validAll.isNotEmpty()) {
            var bestDist = Double.MAX_VALUE
            var bestName = fallbackRegion
            for (s in validAll) {
                val d = hypot(ptLat - s.lat, (ptLon - s.lon) * cosLat)
                if (d < bestDist) {
                    bestDist = d
                    bestName = s.name
                }
            }
            cache[cKey] = bestName
            return bestName
        }

        return fallbackRegion
    }
}
