package com.olegskal.mushroom.address

import android.database.sqlite.SQLiteDatabase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * RAM-просторова 2D-сітка для швидкого пошуку найближчого будинку та його вулиці (street_id)
 * для будь-якої точки POI в оперативній пам'яті без дискових звернень.
 */
class SpatialHouseGrid {

    companion object {
        // Розмір комірки сітки в одиницях координат таблиці houses.
        // У таблиці houses координати збережені в градусах * 100_000.
        // 5000 одиниць = 0.05 градуса (~4-5 км).
        private const val CELL_SIZE = 5000

        private fun cellKey(cellX: Int, cellY: Int): Long {
            return (cellX.toLong() shl 32) or (cellY.toLong() and 0xFFFFFFFFL)
        }

        fun buildFromDatabase(db: SQLiteDatabase, countryCode: String? = null): SpatialHouseGrid {
            val grid = SpatialHouseGrid()
            val sql = if (countryCode != null) {
                """
                    SELECT h.lat, h.lon, h.street_id 
                    FROM houses h 
                    JOIN streets s ON s.id = h.street_id 
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places c ON c.id = s.country_id 
                    WHERE UPPER(c.code) = ? AND sn.name != '';
                """.trimIndent()
            } else {
                """
                    SELECT h.lat, h.lon, h.street_id 
                    FROM houses h 
                    JOIN streets s ON s.id = h.street_id 
                    JOIN street_names sn ON sn.id = s.name_id
                    WHERE sn.name != '';
                """.trimIndent()
            }
            val args = if (countryCode != null) arrayOf(countryCode.uppercase()) else null
            val cursor = db.rawQuery(sql, args)
            cursor.use { c ->
                grid.ensureCapacity(c.count)
                while (c.moveToNext()) {
                    grid.add(c.getInt(0), c.getInt(1), c.getLong(2))
                }
            }
            return grid
        }
    }

    private var count = 0
    private var lats = IntArray(32768)
    private var lons = IntArray(32768)
    private var streetIds = LongArray(32768)

    // cellKey -> індекси будинків у масивах lats/lons/streetIds
    private val cells = HashMap<Long, ArrayList<Int>>()

    fun ensureCapacity(capacity: Int) {
        if (capacity > lats.size) {
            val newSize = max(capacity, lats.size * 2)
            lats = lats.copyOf(newSize)
            lons = lons.copyOf(newSize)
            streetIds = streetIds.copyOf(newSize)
        }
    }

    fun add(lat: Int, lon: Int, streetId: Long) {
        if (count >= lats.size) {
            val newSize = lats.size * 2
            lats = lats.copyOf(newSize)
            lons = lons.copyOf(newSize)
            streetIds = streetIds.copyOf(newSize)
        }
        val idx = count++
        lats[idx] = lat
        lons[idx] = lon
        streetIds[idx] = streetId

        val cx = lat / CELL_SIZE
        val cy = lon / CELL_SIZE
        val key = cellKey(cx, cy)
        var list = cells[key]
        if (list == null) {
            list = ArrayList(8)
            cells[key] = list
        }
        list.add(idx)
    }

    val size: Int get() = count

    /**
     * Пошук найближчого будинку в RAM.
     * @param poiLatMicro широта точки в мікроградусах (deg * 1_000_000)
     * @param poiLonMicro довгота точки в мікроградусах (deg * 1_000_000)
     * @return street_id найближчого будинку, або null якщо в базі немає будинків
     */
    fun findNearestStreetId(poiLatMicro: Int, poiLonMicro: Int): Long? {
        if (count == 0) return null

        // Координати будинків збережені як deg * 100_000 (div 10 від мікроградусів)
        val plat = poiLatMicro / 10
        val plon = poiLonMicro / 10
        val cx = plat / CELL_SIZE
        val cy = plon / CELL_SIZE

        val latDeg = poiLatMicro / 1_000_000.0
        val cosLat = cos(Math.toRadians(latDeg))
        val ky = 1.1132
        val kx = 1.1132 * cosLat

        var bestDistSq = Double.MAX_VALUE
        var bestStreetId: Long? = null

        // Пошук розширюваними кільцями по комірках сітки
        var radius = 0
        var stopAfterRing = -1
        while (radius <= 5) {
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    if (max(abs(dx), abs(dy)) != radius) continue
                    val cell = cells[cellKey(cx + dx, cy + dy)] ?: continue
                    val cellSize = cell.size
                    for (i in 0 until cellSize) {
                        val hIdx = cell[i]
                        val dlat = (plat - lats[hIdx]) * ky
                        val dlon = (plon - lons[hIdx]) * kx
                        val d2 = dlat * dlat + dlon * dlon
                        if (d2 < bestDistSq) {
                            bestDistSq = d2
                            bestStreetId = streetIds[hIdx]
                        }
                    }
                }
            }
            if (bestStreetId != null && stopAfterRing == -1) {
                // Знайдено будинок у поточному кільці.
                // Перевіряємо ще одне сусіднє кільце (radius + 1) на випадок границі комірок і зупиняємось.
                stopAfterRing = radius + 1
            }
            if (stopAfterRing != -1 && radius >= stopAfterRing) {
                break
            }
            radius++
        }

        return bestStreetId
    }

    /**
     * Пошук найближчого будинку з прив'язкою до вулиці у межах [maxDistMeters].
     * @param latI широта в deg * 100_000
     * @param lonI довгота в deg * 100_000
     * @param maxDistMeters максимальна відстань у метрах (за замовчуванням 40 м)
     */
    fun findNearestStreetIdWithMaxDist(latI: Int, lonI: Int, maxDistMeters: Double = 40.0): Long? {
        if (count == 0) return null

        val cx = latI / CELL_SIZE
        val cy = lonI / CELL_SIZE

        val latDeg = latI / 100_000.0
        val cosLat = cos(Math.toRadians(latDeg))
        val ky = 1.10574
        val kx = 1.11320 * cosLat

        var bestDist = maxDistMeters
        var bestStreetId: Long? = null

        for (dx in -1..1) {
            for (dy in -1..1) {
                val cell = cells[cellKey(cx + dx, cy + dy)] ?: continue
                val cellSize = cell.size
                for (i in 0 until cellSize) {
                    val hIdx = cell[i]
                    val dlat = (latI - lats[hIdx]) * ky
                    val dlon = (lonI - lons[hIdx]) * kx
                    val d = kotlin.math.hypot(dlat, dlon)
                    if (d < bestDist) {
                        bestDist = d
                        bestStreetId = streetIds[hIdx]
                    }
                }
            }
        }

        return bestStreetId
    }

    fun clear() {
        count = 0
        cells.clear()
        lats = IntArray(1024)
        lons = IntArray(1024)
        streetIds = LongArray(1024)
    }
}
