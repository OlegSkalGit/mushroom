package com.olegskal.mushroom.address

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.olegskal.mushroom.util.AppLogger
import java.util.ArrayDeque
import kotlin.math.roundToInt

class AddressIngestor(
    val db: SQLiteDatabase,
    val countryCode: String,
    val countryName: String
) {
    companion object {
        private const val TAG = "AddressIngestor"
    }

    data class NearestAddress(
        val streetName: String?,
        val cityName: String?
    )

    var countryId: Long = 1L
        private set

    private val placesCache = mutableMapOf<Pair<Long, String>, Long>()
    private val streetNamesCache = mutableMapOf<String, Long>()
    private val streetsCache = mutableMapOf<Pair<Long, Long>, Long>()

    private data class CachedHouse(
        val latI: Int,
        val lonI: Int,
        val street: String,
        val city: String
    )
    private val recentHouses = ArrayDeque<CachedHouse>(512)

    data class HouseRecord(
        val streetId: Long,
        val number: String,
        val lat: Int,
        val lon: Int
    )

    private val housesBatch = ArrayList<HouseRecord>(10000)
    private val batchCoords = HashSet<Long>(10000)

    private var stmtInsertPlace: SQLiteStatement? = null
    private var stmtInsertStreetName: SQLiteStatement? = null
    private var stmtInsertStreet: SQLiteStatement? = null
    private var stmtInsertFts: SQLiteStatement? = null
    private var stmtInsertHouse: SQLiteStatement? = null

    init {
        AddressDb.ensureTablesExist(db)
        initCountry()
        warmupCache()
        initStatements()
    }

    private fun initStatements() {
        stmtInsertPlace = db.compileStatement(
            "INSERT INTO admin_places (osm_id, parent_id, level, name, code) VALUES (?, ?, ?, ?, ?);"
        )
        stmtInsertStreetName = db.compileStatement(
            "INSERT OR IGNORE INTO street_names (name) VALUES (?);"
        )
        stmtInsertStreet = db.compileStatement(
            "INSERT INTO streets (place_id, region_id, country_id, name_id) VALUES (?, ?, ?, ?);"
        )
        stmtInsertFts = try {
            db.compileStatement(
                "INSERT INTO search_fts (rowid, region_name, city_name, street_name) VALUES (?, ?, ?, ?);"
            )
        } catch (_: Exception) {
            null
        }
        stmtInsertHouse = db.compileStatement(
            "INSERT OR REPLACE INTO houses (street_id, number, lat, lon) VALUES (?, ?, ?, ?);"
        )
    }

    private fun initCountry() {
        val cursor = db.rawQuery(
            "SELECT id FROM admin_places WHERE level = 2 AND (code = ? OR name = ?) LIMIT 1;",
            arrayOf(countryCode.uppercase(), countryName)
        )
        var cid: Long? = null
        cursor.use {
            if (it.moveToFirst()) {
                cid = it.getLong(0)
            }
        }
        if (cid != null) {
            countryId = cid!!
        } else {
            val stmt = db.compileStatement(
                "INSERT INTO admin_places (osm_id, parent_id, level, name, code) VALUES (0, NULL, 2, ?, ?);"
            )
            try {
                stmt.bindString(1, countryName)
                stmt.bindString(2, countryCode.uppercase())
                countryId = stmt.executeInsert()
            } finally {
                stmt.close()
            }
        }
    }

    private fun warmupCache() {
        try {
            val pCursor = db.rawQuery("""
                WITH RECURSIVE places_tree(id, parent_id, name) AS (
                    SELECT id, parent_id, name FROM admin_places WHERE id = ?
                    UNION ALL
                    SELECT p.id, p.parent_id, p.name FROM admin_places p
                    JOIN places_tree pt ON p.parent_id = pt.id
                )
                SELECT id, parent_id, name FROM places_tree;
            """.trimIndent(), arrayOf(countryId.toString()))
            pCursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val parentId = if (it.isNull(1)) 0L else it.getLong(1)
                    val name = it.getString(2) ?: ""
                    placesCache[Pair(parentId, name.lowercase())] = id
                }
            }

            val sCursor = db.rawQuery("SELECT id, name FROM street_names;", null)
            sCursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: ""
                    streetNamesCache[name.lowercase()] = id
                }
            }

            if (placesCache.isNotEmpty()) {
                val pids = placesCache.values.distinct()
                for (chunk in pids.chunked(500)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    val args = chunk.map { it.toString() }.toTypedArray()
                    val stCursor = db.rawQuery(
                        "SELECT id, place_id, name_id FROM streets WHERE place_id IN ($placeholders);",
                        args
                    )
                    stCursor.use {
                        while (it.moveToNext()) {
                            val id = it.getLong(0)
                            val placeId = it.getLong(1)
                            val nameId = it.getLong(2)
                            streetsCache[Pair(placeId, nameId)] = id
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "warmupCache", false, "Cache warmup warning: ${e.message}")
        }
    }

    fun getOrCreatePlace(parentId: Long, name: String, level: Int, osmId: Long? = null, code: String? = null): Long {
        if (name.isBlank()) return parentId
        val clean = name.trim()
        val key = Pair(parentId, clean.lowercase())
        placesCache[key]?.let { return it }

        try {
            val cursor = db.rawQuery(
                "SELECT id FROM admin_places WHERE parent_id = ? AND level = ? AND name = ? LIMIT 1;",
                arrayOf(parentId.toString(), level.toString(), clean)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val existingId = it.getLong(0)
                    placesCache[key] = existingId
                    return existingId
                }
            }
        } catch (_: Exception) {}

        val stmt = stmtInsertPlace ?: return parentId
        stmt.bindLong(1, osmId ?: 0L)
        if (parentId > 0) stmt.bindLong(2, parentId) else stmt.bindNull(2)
        stmt.bindLong(3, level.toLong())
        stmt.bindString(4, clean)
        val cleanCode = code?.trim()
        if (!cleanCode.isNullOrBlank()) {
            stmt.bindString(5, cleanCode)
        } else {
            stmt.bindNull(5)
        }
        val id = stmt.executeInsert()
        placesCache[key] = id
        return id
    }

    fun getOrCreateStreetName(name: String): Long {
        val clean = AddressNormalizer.canonicalStreetNameUniversal(name).ifBlank { name.trim() }
        val key = clean.lowercase()
        streetNamesCache[key]?.let { return it }

        val stmt = stmtInsertStreetName
        stmt?.bindString(1, clean)
        stmt?.execute()

        val cursor = db.rawQuery("SELECT id FROM street_names WHERE name = ? LIMIT 1;", arrayOf(clean))
        var nid = 1L
        cursor.use {
            if (it.moveToFirst()) nid = it.getLong(0)
        }
        streetNamesCache[key] = nid
        return nid
    }

    fun getOrCreateStreet(
        placeId: Long,
        streetName: String,
        regionName: String,
        cityName: String,
        regionId: Long? = null
    ): Long {
        val clean = AddressNormalizer.canonicalStreetNameUniversal(streetName)
            .ifBlank { streetName.trim() }
            .ifBlank { cityName.trim() }
        val nameId = getOrCreateStreetName(clean)
        val key = Pair(placeId, nameId)
        streetsCache[key]?.let { return it }

        val stmt = stmtInsertStreet ?: return 0L
        stmt.bindLong(1, placeId)
        if (regionId != null && regionId > 0) stmt.bindLong(2, regionId) else stmt.bindNull(2)
        stmt.bindLong(3, countryId)
        stmt.bindLong(4, nameId)
        val sid = stmt.executeInsert()
        streetsCache[key] = sid

        stmtInsertFts?.let { fts ->
            try {
                fts.bindLong(1, sid)
                fts.bindString(2, regionName.lowercase().trim())
                fts.bindString(3, cityName.lowercase().trim())
                fts.bindString(4, clean.lowercase().trim())
                fts.executeInsert()
            } catch (_: Exception) {}
        }

        return sid
    }

    fun registerKnownAddress(lat: Double, lon: Double, street: String, city: String) {
        val s = street.trim()
        val c = city.trim()
        if (s.isBlank() || c.isBlank() || s.equals(c, ignoreCase = true)) return
        val latI = if (kotlin.math.abs(lat) > 1000.0) (lat / 10.0).roundToInt() else (lat * 100_000.0).roundToInt()
        val lonI = if (kotlin.math.abs(lon) > 1000.0) (lon / 10.0).roundToInt() else (lon * 100_000.0).roundToInt()
        if (recentHouses.size >= 500) {
            recentHouses.pollFirst()
        }
        recentHouses.addLast(CachedHouse(latI, lonI, s, c))
    }

    fun findNearestAddress(lat: Double, lon: Double, maxDistMeters: Double = 1000.0): NearestAddress? {
        val latI = if (kotlin.math.abs(lat) > 1000.0) (lat / 10.0).roundToInt() else (lat * 100_000.0).roundToInt()
        val lonI = if (kotlin.math.abs(lon) > 1000.0) (lon / 10.0).roundToInt() else (lon * 100_000.0).roundToInt()
        val latDeg = latI / 100_000.0
        val cosLat = kotlin.math.cos(Math.toRadians(latDeg))
        val maxDistSq = maxDistMeters * maxDistMeters

        var bestStreet: String? = null
        var bestCity: String? = null
        var minDistSq = maxDistSq

        // 1. Пошук у буфері останніх будинків (O(N), N<=500, пам'ять, ~0.005 мс)
        val iter = recentHouses.descendingIterator()
        while (iter.hasNext()) {
            val h = iter.next()
            val dy = (latI - h.latI) * 1.1132
            if (kotlin.math.abs(dy) > maxDistMeters) continue
            val dx = (lonI - h.lonI) * 1.1132 * cosLat
            val d2 = dy * dy + dx * dx
            if (d2 < minDistSq) {
                minDistSq = d2
                bestStreet = h.street
                bestCity = h.city
                if (d2 < 625.0) break
            }
        }

        if (bestStreet != null || bestCity != null) {
            return NearestAddress(bestStreet, bestCity)
        }

        // 2. Пошук у SQLite через просторовий індекс idx_houses_coords
        val dLatI = (maxDistMeters / 1.1132).roundToInt() + 1
        val dLonI = (maxDistMeters / (1.1132 * kotlin.math.max(0.1, cosLat))).roundToInt() + 1

        val minLatI = latI - dLatI
        val maxLatI = latI + dLatI
        val minLonI = lonI - dLonI
        val maxLonI = lonI + dLonI

        try {
            val cursor = db.rawQuery("""
                SELECT sn.name, ap.name, h.lat, h.lon
                FROM houses h
                JOIN streets s ON s.id = h.street_id
                JOIN street_names sn ON sn.id = s.name_id
                JOIN admin_places ap ON ap.id = s.place_id
                WHERE h.lat BETWEEN ? AND ? AND h.lon BETWEEN ? AND ?
                LIMIT 25;
            """.trimIndent(), arrayOf(minLatI.toString(), maxLatI.toString(), minLonI.toString(), maxLonI.toString()))

            cursor.use { c ->
                while (c.moveToNext()) {
                    val sName = c.getString(0) ?: ""
                    val cName = c.getString(1) ?: ""
                    val hLat = c.getInt(2)
                    val hLon = c.getInt(3)

                    val dy = (latI - hLat) * 1.1132
                    val dx = (lonI - hLon) * 1.1132 * cosLat
                    val d2 = dy * dy + dx * dx
                    if (d2 < minDistSq) {
                        minDistSq = d2
                        val realStreet = if (sName.isNotBlank() && !sName.equals(cName, ignoreCase = true)) sName else null
                        val realCity = cName.ifBlank { null }
                        if (realStreet != null) bestStreet = realStreet
                        if (realCity != null) bestCity = realCity
                        if (d2 < 625.0) break
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "findNearestAddress", false, "DB nearest search error: ${e.message}")
        }

        if (bestStreet != null || bestCity != null) {
            return NearestAddress(bestStreet, bestCity)
        }
        return null
    }

    fun addHouse(streetId: Long, houseNumber: String, lat: Double, lon: Double) {
        val num = houseNumber.trim()
        if (num.isBlank()) return

        val latI = if (kotlin.math.abs(lat) > 1000.0) {
            (lat / 10.0).roundToInt()
        } else {
            (lat * 100_000.0).roundToInt()
        }
        val lonI = if (kotlin.math.abs(lon) > 1000.0) {
            (lon / 10.0).roundToInt()
        } else {
            (lon * 100_000.0).roundToInt()
        }

        val coordKey = (latI.toLong() shl 32) or (lonI.toLong() and 0xFFFFFFFFL)
        if (!batchCoords.add(coordKey)) {
            return
        }

        housesBatch.add(HouseRecord(streetId, num, latI, lonI))
        if (housesBatch.size >= 10000) {
            flush()
        }
    }

    fun flush() {
        if (housesBatch.isEmpty()) return
        val stmt = stmtInsertHouse ?: return
        val ownTx = !db.inTransaction()
        if (ownTx) db.beginTransaction()
        try {
            for (h in housesBatch) {
                stmt.bindLong(1, h.streetId)
                stmt.bindString(2, h.number)
                stmt.bindLong(3, h.lat.toLong())
                stmt.bindLong(4, h.lon.toLong())
                stmt.executeInsert()
            }
            if (ownTx) db.setTransactionSuccessful()
        } catch (e: Exception) {
            AppLogger.log(TAG, "flush", false, "Error flushing houses: ${e.message}")
        } finally {
            if (ownTx) db.endTransaction()
            housesBatch.clear()
            batchCoords.clear()
        }
    }

    fun close() {
        flush()
        recentHouses.clear()
        try { stmtInsertPlace?.close() } catch (_: Exception) {}
        try { stmtInsertStreetName?.close() } catch (_: Exception) {}
        try { stmtInsertStreet?.close() } catch (_: Exception) {}
        try { stmtInsertFts?.close() } catch (_: Exception) {}
        try { stmtInsertHouse?.close() } catch (_: Exception) {}
    }
}
