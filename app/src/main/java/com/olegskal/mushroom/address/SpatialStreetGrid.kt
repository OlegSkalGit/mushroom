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
    fun loadFromJsonFile(file: File, countryCode: String = "UA"): Int {
        if (!file.exists() || file.length() == 0L) return 0
        var loadedWays = 0
        val preferUk = countryCode.equals("UA", ignoreCase = true)

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
                            var nameLocal = ""
                            var nameUk = ""
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
                                                nameUk = tagV
                                            } else if (tagK == "name" && tagV.isNotBlank()) {
                                                nameLocal = tagV
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

                            val streetName = if (preferUk) {
                                nameUk.ifBlank { nameLocal }
                            } else {
                                nameLocal.ifBlank { nameUk }
                            }

                            if (streetName.isNotBlank() && ptsLat.size >= 2) {
                                loadedWays++
                                for (i in 0 until ptsLat.size - 1) {
                                    addSegment(ptsLat[i], ptsLon[i], ptsLat[i + 1], ptsLon[i + 1], streetName)
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

    fun clear() {
        count = 0
        cells.clear()
        streetNames.clear()
        nameToId.clear()
        lat1s = IntArray(1024)
        lon1s = IntArray(1024)
        lat2s = IntArray(1024)
        lon2s = IntArray(1024)
        nameIndices = IntArray(1024)
    }

    data class StreetCluster(
        val canonicalName: String,
        val centerLat: Double,
        val centerLon: Double,
        val segmentCount: Int,
        val minLatI: Int = 0,
        val maxLatI: Int = 0,
        val minLonI: Int = 0,
        val maxLonI: Int = 0,
        val segmentsBlob: ByteArray = ByteArray(0)
    )

    /**
     * Групує відрізки вулиць за канонічною назвою та просторовою близькістю (~2.5 км),
     * щоб однойменні вулиці в різних селах/містах області не зливалися в одну,
     * та обчислює геометричний центр кожної вулиці безпосередньо на лінії дороги.
     * Якщо передано [onCluster], кластери передаються потоково для мінімізації навантаження на RAM.
     */
    fun extractStreetClusters(onCluster: ((StreetCluster) -> Unit)? = null): List<StreetCluster> {
        if (count == 0) return emptyList()

        val canonicalById = arrayOfNulls<String>(streetNames.size)
        val segsByCanonical = HashMap<String, ArrayList<Int>>()
        val displayByKey = HashMap<String, String>()

        for (i in 0 until streetNames.size) {
            val raw = streetNames[i]
            val canonical = AddressNormalizer.canonicalStreetNameUniversal(raw).trim()
            if (canonical.length >= 2 && canonical.any { it.isLetter() }) {
                canonicalById[i] = canonical
                val key = canonical.lowercase()
                if (!displayByKey.containsKey(key)) {
                    displayByKey[key] = canonical
                }
            }
        }

        for (idx in 0 until count) {
            val cName = canonicalById[nameIndices[idx]] ?: continue
            val key = cName.lowercase()
            segsByCanonical.getOrPut(key) { ArrayList() }.add(idx)
        }

        // Поріг просторової кластеризації: 2500 одиниць = 0.025 градуса (~2.5 км)
        val clusterRadiusUnits = 2500
        val result = ArrayList<StreetCluster>(segsByCanonical.size)

        class ClusterBox(firstIdx: Int) {
            val segs = ArrayList<Int>(8)
            var minLat: Int
            var maxLat: Int
            var minLon: Int
            var maxLon: Int

            init {
                segs.add(firstIdx)
                val l1 = lat1s[firstIdx]
                val l2 = lat2s[firstIdx]
                val n1 = lon1s[firstIdx]
                val n2 = lon2s[firstIdx]
                minLat = min(l1, l2)
                maxLat = max(l1, l2)
                minLon = min(n1, n2)
                maxLon = max(n1, n2)
            }

            fun add(idx: Int) {
                segs.add(idx)
                val l1 = lat1s[idx]
                val l2 = lat2s[idx]
                val n1 = lon1s[idx]
                val n2 = lon2s[idx]
                if (l1 < minLat) minLat = l1
                if (l2 < minLat) minLat = l2
                if (l1 > maxLat) maxLat = l1
                if (l2 > maxLat) maxLat = l2
                if (n1 < minLon) minLon = n1
                if (n2 < minLon) minLon = n2
                if (n1 > maxLon) maxLon = n1
                if (n2 > maxLon) maxLon = n2
            }

            fun isNear(idx: Int): Boolean {
                val midLat = (lat1s[idx] + lat2s[idx]) shr 1
                val midLon = (lon1s[idx] + lon2s[idx]) shr 1
                return midLat >= (minLat - clusterRadiusUnits) &&
                    midLat <= (maxLat + clusterRadiusUnits) &&
                    midLon >= (minLon - clusterRadiusUnits) &&
                    midLon <= (maxLon + clusterRadiusUnits)
            }
        }

        for ((key, segIndices) in segsByCanonical) {
            val canonicalName = displayByKey[key] ?: continue
            val clusters = ArrayList<ClusterBox>(2)

            for (i in 0 until segIndices.size) {
                val segIdx = segIndices[i]
                var matchedCluster: ClusterBox? = null
                for (c in clusters) {
                    if (c.isNear(segIdx)) {
                        matchedCluster = c
                        break
                    }
                }
                if (matchedCluster != null) {
                    matchedCluster.add(segIdx)
                } else {
                    clusters.add(ClusterBox(segIdx))
                }
            }

            for (c in clusters) {
                val cSegs = c.segs
                val n = cSegs.size
                if (n == 0) continue

                var sumLat = 0L
                var sumLon = 0L
                for (i in 0 until n) {
                    val idx = cSegs[i]
                    sumLat += (lat1s[idx].toLong() + lat2s[idx].toLong()) / 2L
                    sumLon += (lon1s[idx].toLong() + lon2s[idx].toLong()) / 2L
                }
                val avgLat = sumLat.toDouble() / n
                val avgLon = sumLon.toDouble() / n

                var bestLat = avgLat
                var bestLon = avgLon
                var bestDistSq = Double.MAX_VALUE

                for (i in 0 until n) {
                    val idx = cSegs[i]
                    val midLat = (lat1s[idx] + lat2s[idx]) * 0.5
                    val midLon = (lon1s[idx] + lon2s[idx]) * 0.5
                    val dLat = midLat - avgLat
                    val dLon = midLon - avgLon
                    val dSq = dLat * dLat + dLon * dLon
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq
                        bestLat = midLat
                        bestLon = midLon
                    }
                }

                val centerLatDeg = bestLat / 100_000.0
                val centerLonDeg = bestLon / 100_000.0
                if (centerLatDeg != 0.0 || centerLonDeg != 0.0) {
                    val buf = java.nio.ByteBuffer.allocate(n * 16)
                    for (i in 0 until n) {
                        val idx = cSegs[i]
                        buf.putInt(lat1s[idx])
                        buf.putInt(lon1s[idx])
                        buf.putInt(lat2s[idx])
                        buf.putInt(lon2s[idx])
                    }
                    val cluster = StreetCluster(
                        canonicalName = canonicalName,
                        centerLat = centerLatDeg,
                        centerLon = centerLonDeg,
                        segmentCount = n,
                        minLatI = c.minLat,
                        maxLatI = c.maxLat,
                        minLonI = c.minLon,
                        maxLonI = c.maxLon,
                        segmentsBlob = buf.array()
                    )
                    if (onCluster != null) {
                        onCluster(cluster)
                    } else {
                        result.add(cluster)
                    }
                }
            }
        }

        if (onCluster != null) {
            return emptyList()
        }

        // Спочатку більші вулиці (більше сегментів), щоб основна частина вулиці мала пріоритет
        result.sortByDescending { it.segmentCount }
        return result
    }
}
