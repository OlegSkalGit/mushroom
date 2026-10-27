package com.olegskal.mushroom.address

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.olegskal.mushroom.util.AppLogger
import kotlin.math.roundToInt

class AddressIngestor(
    val db: SQLiteDatabase,
    val countryCode: String,
    val countryName: String
) {
    companion object {
        private const val TAG = "AddressIngestor"
    }

    var countryId: Long = 1L
        private set

    private val placesCache = mutableMapOf<Pair<Long, String>, Long>()
    private val streetNamesCache = mutableMapOf<String, Long>()
    private val streetsCache = mutableMapOf<Pair<Long, Long>, Long>()


    data class HouseRecord(
        val streetId: Long,
        val number: String,
        val lat: Int,
        val lon: Int
    )

    private class GeomBox(
        var minLat: Int,
        var maxLat: Int,
        var minLon: Int,
        var maxLon: Int,
        val blobs: ArrayList<ByteArray> = ArrayList(2),
        var totalBytes: Int = 0
    ) {
        fun addBlob(b: ByteArray) {
            blobs.add(b)
            totalBytes += b.size
        }

        fun buildMergedBlob(): ByteArray {
            if (blobs.size == 1) return blobs[0]
            val res = ByteArray(totalBytes)
            var offset = 0
            for (i in 0 until blobs.size) {
                val b = blobs[i]
                System.arraycopy(b, 0, res, offset, b.size)
                offset += b.size
            }
            return res
        }
    }

    private val housesBatch = ArrayList<HouseRecord>(10000)
    private val batchKeys = HashSet<String>(10000)
    private val streetGeoms = HashMap<Long, GeomBox>()

    private var stmtInsertPlace: SQLiteStatement? = null
    private var stmtInsertStreetName: SQLiteStatement? = null
    private var stmtInsertStreet: SQLiteStatement? = null
    private var stmtInsertFts: SQLiteStatement? = null
    private var stmtInsertHouse: SQLiteStatement? = null
    private var stmtInsertStreetGeometry: SQLiteStatement? = null

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
        stmtInsertStreetGeometry = try {
            db.compileStatement(
                "INSERT OR REPLACE INTO street_geometries (street_id, min_lat, max_lat, min_lon, max_lon, geom) VALUES (?, ?, ?, ?, ?, ?);"
            )
        } catch (_: Exception) {
            null
        }
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

    fun addHouse(streetId: Long, houseNumber: String, lat: Double, lon: Double) {
        val latI = (lat * 100_000.0).roundToInt()
        val lonI = (lon * 100_000.0).roundToInt()
        addHouse(streetId, houseNumber, latI, lonI)
    }

    fun addHouse(streetId: Long, houseNumber: String, latI: Int, lonI: Int) {
        if (streetId <= 0L || (latI == 0 && lonI == 0)) return
        val num = houseNumber.trim()

        val batchKey = "$streetId|${num.lowercase()}|$latI|$lonI"
        if (!batchKeys.add(batchKey)) {
            return
        }

        housesBatch.add(HouseRecord(streetId, num, latI, lonI))
        if (housesBatch.size >= 10000) {
            flushHouses()
        }
    }

    fun flushHouses() {
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
            AppLogger.log(TAG, "flushHouses", false, "Error flushing houses: ${e.message}")
        } finally {
            if (ownTx) db.endTransaction()
            housesBatch.clear()
            batchKeys.clear()
        }
    }

    fun flushGeometries() {
        if (streetGeoms.isEmpty()) return
        val stmt = stmtInsertStreetGeometry ?: return
        val ownTx = !db.inTransaction()
        if (ownTx) db.beginTransaction()
        try {
            for ((streetId, box) in streetGeoms) {
                stmt.bindLong(1, streetId)
                stmt.bindLong(2, box.minLat.toLong())
                stmt.bindLong(3, box.maxLat.toLong())
                stmt.bindLong(4, box.minLon.toLong())
                stmt.bindLong(5, box.maxLon.toLong())
                stmt.bindBlob(6, box.buildMergedBlob())
                stmt.executeInsert()
            }
            if (ownTx) db.setTransactionSuccessful()
        } catch (e: Exception) {
            AppLogger.log(TAG, "flushGeometries", false, "Error flushing street geometries: ${e.message}")
        } finally {
            if (ownTx) db.endTransaction()
            streetGeoms.clear()
        }
    }

    fun flush() {
        flushHouses()
        flushGeometries()
    }

    fun addStreetGeometry(streetId: Long, minLat: Int, maxLat: Int, minLon: Int, maxLon: Int, geom: ByteArray) {
        if (geom.isEmpty() || streetId <= 0L) return
        val existing = streetGeoms[streetId]
        if (existing != null) {
            if (minLat < existing.minLat) existing.minLat = minLat
            if (maxLat > existing.maxLat) existing.maxLat = maxLat
            if (minLon < existing.minLon) existing.minLon = minLon
            if (maxLon > existing.maxLon) existing.maxLon = maxLon
            existing.addBlob(geom)
        } else {
            val box = GeomBox(minLat, maxLat, minLon, maxLon)
            box.addBlob(geom)
            streetGeoms[streetId] = box
        }
    }

    fun close() {
        flush()
        try { stmtInsertPlace?.close() } catch (_: Exception) {}
        try { stmtInsertStreetName?.close() } catch (_: Exception) {}
        try { stmtInsertStreet?.close() } catch (_: Exception) {}
        try { stmtInsertFts?.close() } catch (_: Exception) {}
        try { stmtInsertHouse?.close() } catch (_: Exception) {}
        try { stmtInsertStreetGeometry?.close() } catch (_: Exception) {}
    }
}
