package com.olegskal.mushroom.address

import android.util.JsonReader
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Просторова 2D-сітка відрізків іменованих вулиць для швидкого геометричного
 * пошуку найближчої лінії дороги до будинку (point-to-segment distance).
 * Використовує компактні плоскі масиви для мінімізації навантаження на RAM Android.
 */
class SpatialStreetGrid {

    companion object {
        private const val TAG = "SpatialStreetGrid"

        // Розмір комірки сітки: 500 одиниць = 0.005 градуса (~500 метрів).
        // Оскільки радіус пошуку становить до 80 м, достатньо перевірити поточну комірку та її 8 сусідів.
        private const val CELL_SIZE = 500

        private fun cellKey(cellX: Int, cellY: Int): Long {
            return (cellX.toLong() shl 32) or (cellY.toLong() and 0xFFFFFFFFL)
        }
    }

    private var count = 0
    private var lat1s = IntArray(16384)
    private var lon1s = IntArray(16384)
    private var lat2s = IntArray(16384)
    private var lon2s = IntArray(16384)
    private var nameIndices = IntArray(16384)

    private val streetNames = ArrayList<String>()
    private val nameToId = HashMap<String, Int>()

    private val streetCentroidLats = HashMap<String, Double>()
    private val streetCentroidLons = HashMap<String, Double>()

    // cellKey -> список індексів відрізків
    private val cells = HashMap<Long, ArrayList<Int>>()

    val segmentCount: Int get() = count
    val streetCount: Int get() = streetNames.size

    private fun ensureCapacity(capacity: Int) {
        if (capacity > lat1s.size) {
            val newSize = max(capacity, lat1s.size * 2)
            lat1s = lat1s.copyOf(newSize)
            lon1s = lon1s.copyOf(newSize)
            lat2s = lat2s.copyOf(newSize)
            lon2s = lon2s.copyOf(newSize)
            nameIndices = nameIndices.copyOf(newSize)
        }
    }

    fun addSegment(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return

        var nameId = nameToId[cleanName]
        if (nameId == null) {
            nameId = streetNames.size
            streetNames.add(cleanName)
            nameToId[cleanName] = nameId
        }

        val l1 = (lat1Deg * 100_000.0).roundToInt()
        val n1 = (lon1Deg * 100_000.0).roundToInt()
        val l2 = (lat2Deg * 100_000.0).roundToInt()
        val n2 = (lon2Deg * 100_000.0).roundToInt()

        if (count >= lat1s.size) {
            ensureCapacity(lat1s.size * 2)
        }

        val idx = count++
        lat1s[idx] = l1
        lon1s[idx] = n1
        lat2s[idx] = l2
        lon2s[idx] = n2
        nameIndices[idx] = nameId

        val cx1 = l1 / CELL_SIZE
        val cy1 = n1 / CELL_SIZE
        val cx2 = l2 / CELL_SIZE
        val cy2 = n2 / CELL_SIZE

        val minCx = min(cx1, cx2)
        val maxCx = max(cx1, cx2)
        val minCy = min(cy1, cy2)
        val maxCy = max(cy1, cy2)

        for (cx in minCx..maxCx) {
            for (cy in minCy..maxCy) {
                val key = cellKey(cx, cy)
                var list = cells[key]
                if (list == null) {
                    list = ArrayList(8)
                    cells[key] = list
                }
                list.add(idx)
            }
        }
    }

    /**
     * Знаходить назву найближчої вулиці для будинку, якщо відстань від будинку
     * до лінії (осі) дороги не перевищує [maxDistanceMeters].
     */
    fun findNearestStreet(latDeg: Double, lonDeg: Double, maxDistanceMeters: Double = 80.0): String? {
        if (count == 0) return null

        val platI = (latDeg * 100_000.0).roundToInt()
        val plonI = (lonDeg * 100_000.0).roundToInt()
        val cx = platI / CELL_SIZE
        val cy = plonI / CELL_SIZE

        var bestDist = maxDistanceMeters
        var bestNameIdx = -1

        // Перевіряємо поточну комірку та 8 сусідніх комірок (радіус 1 комірки = 500м, що з запасом покриває 80м)
        for (dx in -1..1) {
            for (dy in -1..1) {
                val cell = cells[cellKey(cx + dx, cy + dy)] ?: continue
                val cSize = cell.size
                for (i in 0 until cSize) {
                    val idx = cell[i]
                    val l1 = lat1s[idx]
                    val n1 = lon1s[idx]
                    val l2 = lat2s[idx]
                    val n2 = lon2s[idx]

                    val lat1 = l1 / 100_000.0
                    val lon1 = n1 / 100_000.0
                    val lat2 = l2 / 100_000.0
                    val lon2 = n2 / 100_000.0

                    val latMidRad = Math.toRadians((lat1 + lat2 + latDeg) / 3.0)
                    val cosLat = cos(latMidRad)
                    val px = lonDeg * cosLat * 111320.0
                    val py = latDeg * 110574.0
                    val x1 = lon1 * cosLat * 111320.0
                    val y1 = lat1 * 110574.0
                    val x2 = lon2 * cosLat * 111320.0
                    val y2 = lat2 * 110574.0

                    val vx = x2 - x1
                    val vy = y2 - y1
                    val segLenSq = vx * vx + vy * vy

                    val dist = if (segLenSq < 1e-6) {
                        hypot(px - x1, py - y1)
                    } else {
                        val t = ((px - x1) * vx + (py - y1) * vy) / segLenSq
                        val clampedT = t.coerceIn(0.0, 1.0)
                        val projX = x1 + clampedT * vx
                        val projY = y1 + clampedT * vy
                        hypot(px - projX, py - projY)
                    }

                    if (dist < bestDist) {
                        bestDist = dist
                        bestNameIdx = nameIndices[idx]
                    }
                }
            }
        }

        return if (bestNameIdx >= 0 && bestNameIdx < streetNames.size) {
            streetNames[bestNameIdx]
        } else {
            null
        }
    }

    /**
     * Потокове завантаження геометрії доріг з тимчасового JSON-файлу через [JsonReader]
     * без створення важких об'єктів у RAM.
     */
    fun loadFromJsonFile(file: File): Int {
        if (!file.exists() || file.length() == 0L) return 0
        var loadedWays = 0

        try {
            file.bufferedReader(Charsets.UTF_8).use { br ->
                val reader = JsonReader(br)
                reader.beginObject()
                while (reader.hasNext()) {
                    val rootName = reader.nextName()
                    if (rootName == "elements") {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var streetName = ""
                            val ptsLat = ArrayList<Double>(16)
                            val ptsLon = ArrayList<Double>(16)

                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "tags" -> {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            val tagK = reader.nextName()
                                            val tagV = reader.nextString()
                                            if (tagK == "name:uk" && tagV.isNotBlank()) {
                                                streetName = tagV
                                            } else if (tagK == "name" && streetName.isBlank()) {
                                                streetName = tagV
                                            }
                                        }
                                        reader.endObject()
                                    }
                                    "geometry" -> {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            reader.beginObject()
                                            var pLat = 0.0
                                            var pLon = 0.0
                                            while (reader.hasNext()) {
                                                when (reader.nextName()) {
                                                    "lat" -> pLat = reader.nextDouble()
                                                    "lon" -> pLon = reader.nextDouble()
                                                    else -> reader.skipValue()
                                                }
                                            }
                                            reader.endObject()
                                            if (pLat != 0.0 || pLon != 0.0) {
                                                ptsLat.add(pLat)
                                                ptsLon.add(pLon)
                                            }
                                        }
                                        reader.endArray()
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (streetName.isNotBlank() && ptsLat.size >= 2) {
                                loadedWays++
                                val cleanStName = streetName.trim()
                                if (!streetCentroidLats.containsKey(cleanStName)) {
                                    streetCentroidLats[cleanStName] = ptsLat.average()
                                    streetCentroidLons[cleanStName] = ptsLon.average()
                                }
                                for (i in 0 until ptsLat.size - 1) {
                                    addSegment(ptsLat[i], ptsLon[i], ptsLat[i + 1], ptsLon[i + 1], cleanStName)
                                }
                            }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "loadFromJsonFile", false, "Помилка читання JSON доріг: ${e.message}")
        }

        return loadedWays
    }

    fun getStreetCentroids(): Map<String, Pair<Double, Double>> {
        val result = HashMap<String, Pair<Double, Double>>(streetCentroidLats.size)
        for ((name, lat) in streetCentroidLats) {
            val lon = streetCentroidLons[name] ?: continue
            result[name] = Pair(lat, lon)
        }
        return result
    }

    fun clear() {
        count = 0
        cells.clear()
        streetNames.clear()
        nameToId.clear()
        streetCentroidLats.clear()
        streetCentroidLons.clear()
        lat1s = IntArray(1024)
        lon1s = IntArray(1024)
        lat2s = IntArray(1024)
        lon2s = IntArray(1024)
        nameIndices = IntArray(1024)
    }
}
