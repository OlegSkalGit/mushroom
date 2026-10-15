package com.olegskal.mushroom.address

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.map.PoiManager
import com.olegskal.mushroom.math.GeoMath
import kotlin.math.roundToInt
import org.json.JSONObject
import java.io.File

data class RegionTarget(
    val relId: Long,
    val name: String,
    val minLat: Double = 0.0,
    val minLon: Double = 0.0,
    val maxLat: Double = 0.0,
    val maxLon: Double = 0.0
) {
    val isBbox: Boolean
        get() = minLat != 0.0 || maxLat != 0.0
}

object AddressDownloadManager {

    private const val TAG = "AddressDownloadManager"
    private val client = OverpassAddressClient()
    val cachedRegionCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val cachedRegions = java.util.concurrent.ConcurrentHashMap<String, List<RegionTarget>>()

    fun getLoadedRegionCount(context: Context, countryCode: String): Int {
        val db = AddressDb.openDatabase(context, writable = false) ?: return 0
        return try {
            val cursor = db.rawQuery("""
                SELECT count(DISTINCT s.region_id) FROM houses h
                JOIN streets s ON h.street_id = s.id
                JOIN admin_places ap ON s.country_id = ap.id
                WHERE ap.level = 2 AND ap.code = ? AND s.region_id IS NOT NULL;
            """.trimIndent(), arrayOf(countryCode.uppercase()))
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun hasAddressesForCountry(context: Context, countryCode: String): Boolean {
        if (AddressDb.openDatabase(context, writable = false) == null) return false
        val code = countryCode.uppercase()
        val expected = getCountryRegionCount(context, code)
        val loaded = getLoadedRegionCount(context, code)
        if (expected <= 1) {
            return getCountryHouseCount(context, code) > 100L
        }
        val houseCount = getCountryHouseCount(context, code)
        if (houseCount < 100L) return false
        val minRequired = if (expected >= 20) (expected - 2).coerceAtLeast(1) else (expected - 1).coerceAtLeast(1)
        return loaded >= minRequired
    }

    fun getCountryHouseCount(context: Context, countryCode: String): Long {
        val db = AddressDb.openDatabase(context, writable = false) ?: return 0L
        return try {
            val cursor = db.rawQuery("""
                SELECT count(*) FROM houses h
                JOIN streets s ON h.street_id = s.id
                JOIN admin_places ap ON s.country_id = ap.id
                WHERE ap.level = 2 AND (ap.code = ? OR ap.code IS NULL OR ap.code = '');
            """.trimIndent(), arrayOf(countryCode.uppercase()))
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun getRegionHouseCount(db: SQLiteDatabase, regionName: String): Long {
        return try {
            val cursor = db.rawQuery("""
                SELECT count(*) FROM houses h
                JOIN streets s ON h.street_id = s.id
                WHERE s.region_id IN (SELECT id FROM admin_places WHERE level = 4 AND (name = ? OR name LIKE ?));
            """.trimIndent(), arrayOf(regionName, "%$regionName%"))
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun loadCachedRegionsFromDb(db: SQLiteDatabase, countryId: Long, countryCode: String? = null): List<RegionTarget> {
        return try {
            val isUa = countryCode?.uppercase() == "UA"
            if (isUa) {
                try {
                    db.execSQL("""
                        DELETE FROM admin_places 
                        WHERE parent_id = ? AND level = 4 
                          AND (osm_id = 3795586 OR (name LIKE '%Крим%' AND osm_id != 72639 AND name != 'Автономна Республіка Крим'));
                    """.trimIndent(), arrayOf(countryId.toString()))
                } catch (_: Exception) {}
            }
            val cursor = db.rawQuery("""
                SELECT osm_id, name FROM admin_places 
                WHERE parent_id = ? AND level = 4 
                ORDER BY name ASC;
            """.trimIndent(), arrayOf(countryId.toString()))
            val list = mutableListOf<RegionTarget>()
            val seenNames = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    val osmId = it.getLong(0)
                    val name = it.getString(1) ?: ""
                    if (name.isBlank()) continue
                    val nameLow = name.lowercase().trim()
                    if (isUa) {
                        if (osmId == 3795586L) continue
                        if (nameLow.contains("крим") && seenNames.any { it.contains("крим") }) continue
                        if (nameLow.contains("севастополь") && seenNames.any { it.contains("севастополь") }) continue
                    }
                    if (nameLow in seenNames) continue
                    seenNames.add(nameLow)
                    list.add(RegionTarget(osmId, name))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRegionsToDb(db: SQLiteDatabase, countryId: Long, regions: List<RegionTarget>, countryCode: String? = null) {
        try {
            val isUa = countryCode?.uppercase() == "UA"
            val existingNames = mutableSetOf<String>()
            val cur = db.rawQuery(
                "SELECT name FROM admin_places WHERE parent_id = ? AND level = 4;",
                arrayOf(countryId.toString())
            )
            cur.use {
                while (it.moveToNext()) {
                    existingNames.add(it.getString(0).lowercase().trim())
                }
            }
            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT INTO admin_places (osm_id, parent_id, level, name) VALUES (?, ?, 4, ?);"
                )
                for (r in regions) {
                    val clean = r.name.lowercase().trim()
                    if (isUa) {
                        if (r.relId == 3795586L) continue
                        if (clean.contains("крим") && existingNames.any { it.contains("крим") }) continue
                        if (clean.contains("севастополь") && existingNames.any { it.contains("севастополь") }) continue
                    }
                    if (clean in existingNames) continue
                    stmt.bindLong(1, if (r.relId > 0) r.relId else 0L)
                    stmt.bindLong(2, countryId)
                    stmt.bindString(3, r.name)
                    stmt.executeInsert()
                    existingNames.add(clean)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "saveRegionsToDb", false, "Error saving regions to DB: ${e.message}")
        }
    }

    fun fetchOverpassRegions(
        country: MapCountry,
        logFn: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        maxPasses: Int = 3,
        db: SQLiteDatabase? = null,
        countryId: Long? = null
    ): List<RegionTarget> {
        val code = country.code.uppercase()
        val cached = cachedRegions[code]
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }

        // 1. Перевірка збережених регіонів у SQLite (якщо вже завантажувалися раніше)
        if (db != null && countryId != null) {
            val dbRegions = loadCachedRegionsFromDb(db, countryId, code)
            if (dbRegions.size >= 2) {
                cachedRegionCounts[code] = dbRegions.size
                cachedRegions[code] = dbRegions
                logFn("[+] Завантажено ${dbRegions.size} регіонів зі збереженої бази даних")
                return dbRegions
            }
        }

        val regions = mutableListOf<RegionTarget>()

        for (pass in 1..maxPasses) {
            if (isCancelled()) return emptyList()

            logFn("Швидкий запит списку регіонів (admin_level=4) для ${country.nameUk} (прохід $pass/$maxPasses)...")

            // Стратегія 1: Швидкий запит за ISO3166-2 з географічними межами (bb)
            val q1 = """
                [out:json][timeout:30];
                relation["admin_level"="4"]["ISO3166-2"~"^$code"];
                out tags bb;
            """.trimIndent()
            var raw = client.query(q1, timeoutSec = 35, logFn = logFn, isCancelled = isCancelled, maxCycles = 2)

            // Стратегія 2: Резервний запит меж країни
            if (raw.isNullOrBlank() && !isCancelled()) {
                val q2 = """
                    [out:json][timeout:45];
                    relation["admin_level"="2"]["ISO3166-1"="$code"];
                    out tags bb;
                """.trimIndent()
                raw = client.query(q2, timeoutSec = 50, logFn = logFn, isCancelled = isCancelled, maxCycles = 2)
            }

            if (!raw.isNullOrBlank()) {
                val seenNames = mutableSetOf<String>()
                try {
                    val json = JSONObject(raw)
                    val elements = json.optJSONArray("elements")
                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val el = elements.optJSONObject(i) ?: continue
                            val relId = el.optLong("id")
                            if (relId <= 0L) continue
                            val tags = el.optJSONObject("tags")
                            val nameUk = tags?.optString("name:uk")?.trim() ?: ""
                            val nameLocal = tags?.optString("name")?.trim() ?: ""
                            val nameEn = tags?.optString("name:en")?.trim() ?: ""
                            val name = nameUk.ifBlank { nameLocal.ifBlank { nameEn } }.trim()
                            if (name.isBlank()) continue
                            val nameLow = name.lowercase()
                            if (code == "UA") {
                                if (relId == 3795586L) continue
                                if (nameLow.contains("крим") && seenNames.any { it.contains("крим") }) continue
                                if (nameLow.contains("севастополь") && seenNames.any { it.contains("севастополь") }) continue
                            }
                            if (nameLow in seenNames) continue
                            seenNames.add(nameLow)

                            val bounds = el.optJSONObject("bounds")
                            val minLat = bounds?.optDouble("minlat") ?: 0.0
                            val minLon = bounds?.optDouble("minlon") ?: 0.0
                            val maxLat = bounds?.optDouble("maxlat") ?: 0.0
                            val maxLon = bounds?.optDouble("maxlon") ?: 0.0

                            regions.add(RegionTarget(relId, name, minLat, minLon, maxLat, maxLon))
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.log(TAG, "fetchRegions", false, "Error parsing regions JSON: ${e.message}")
                }
            }

            if (regions.size >= 2) {
                cachedRegionCounts[code] = regions.size
                cachedRegions[code] = regions
                if (db != null && countryId != null) {
                    saveRegionsToDb(db, countryId, regions, code)
                }
                logFn("[+] Знайдено ${regions.size} адміністративних регіонів для ${country.nameUk}")
                return regions
            }

            if (pass < maxPasses && !isCancelled()) {
                client.interruptibleSleep(2000L, isCancelled)
            }
        }

        // Резервний варіант за алгоритмом скрипта: вся країна в один прохід
        val fallback = listOf(RegionTarget(0L, country.nameUk))
        cachedRegionCounts[code] = 1
        cachedRegions[code] = fallback
        logFn("[i] Окремі регіони admin_level=4 не знайдені. Завантаження країни ${country.nameUk} цілком...")
        return fallback
    }

    fun getCountryRegionCount(countryCode: String): Int {
        val cached = cachedRegionCounts[countryCode.uppercase()]
        if (cached != null && cached > 0) return cached
        return getEstimatedRegionCount(countryCode)
    }

    fun getCountryRegionCount(context: Context, countryCode: String): Int {
        val code = countryCode.uppercase()
        val cached = cachedRegionCounts[code]
        if (cached != null && cached > 0) return cached
        val db = AddressDb.openDatabase(context, writable = false)
        if (db != null) {
            try {
                val cur = db.rawQuery(
                    "SELECT count(*) FROM admin_places WHERE level = 4 AND parent_id = (SELECT id FROM admin_places WHERE level = 2 AND (code = ? OR code IS NULL) LIMIT 1);",
                    arrayOf(code)
                )
                cur.use {
                    if (it.moveToFirst()) {
                        val count = it.getInt(0)
                        if (count > 0) {
                            val adjusted = if (code == "UA" && count > 27) 27 else count
                            cachedRegionCounts[code] = adjusted
                            return adjusted
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return getEstimatedRegionCount(code)
    }

    fun getEstimatedRegionCount(countryCode: String): Int {
        return when (countryCode.uppercase()) {
            "UA" -> 27
            "PL" -> 16
            "DE" -> 16
            "FR" -> 18
            "IT" -> 20
            "ES" -> 17
            "RO" -> 42
            "SK" -> 8
            "CZ" -> 14
            "HU" -> 20
            "AT" -> 9
            "CH" -> 26
            "GB" -> 12
            "US" -> 50
            "CA" -> 13
            "BY" -> 7
            "MD" -> 35
            "BG" -> 28
            "HR" -> 21
            "RS" -> 29
            "NO" -> 11
            "SE" -> 21
            "FI" -> 19
            else -> 10
        }
    }

    private fun fetchAssociatedStreets(
        region: RegionTarget,
        countryCode: String,
        logFn: (String) -> Unit,
        isCancelled: () -> Boolean
    ): Map<Pair<String, Long>, String> {
        val areaClause = if (region.relId > 0) {
            "area(${3600000000L + region.relId})->.a;"
        } else {
            "area[\"ISO3166-1\"=\"${countryCode.uppercase()}\"][admin_level=2]->.a;"
        }
        val qArea = """
            [out:json][timeout:60];
            $areaClause
            relation["type"="associatedStreet"](area.a);
            out body;
        """.trimIndent()
        var raw = client.query(qArea, timeoutSec = 65, logFn = logFn, isCancelled = isCancelled, maxCycles = 1)

        val hasAssoc = try {
            raw != null && (JSONObject(raw).optJSONArray("elements")?.length() ?: 0) > 0
        } catch (_: Exception) { false }

        if (!hasAssoc && region.isBbox && !isCancelled()) {
            val bboxStr = "(${region.minLat}, ${region.minLon}, ${region.maxLat}, ${region.maxLon})"
            val qBbox = """
                [out:json][timeout:60];
                relation["type"="associatedStreet"]$bboxStr;
                out body;
            """.trimIndent()
            raw = client.query(qBbox, timeoutSec = 65, logFn = logFn, isCancelled = isCancelled, maxCycles = 1)
        }
        if (raw.isNullOrBlank()) return emptyMap()
        val assocMap = mutableMapOf<Pair<String, Long>, String>()
        try {
            val json = JSONObject(raw)
            val elements = json.optJSONArray("elements") ?: return emptyMap()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val tags = el.optJSONObject("tags") ?: continue
                val sName = (tags.optString("name:uk").ifBlank {
                    tags.optString("name").ifBlank {
                        tags.optString("addr:street")
                    }
                }).trim()
                if (sName.isBlank()) continue

                val members = el.optJSONArray("members") ?: continue
                for (j in 0 until members.length()) {
                    val m = members.optJSONObject(j) ?: continue
                    val role = m.optString("role")
                    if (role in listOf("house", "address", "building", "")) {
                        val mType = m.optString("type")
                        val mRef = m.optLong("ref")
                        if (mRef > 0) {
                            assocMap[Pair(mType, mRef)] = sName
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return assocMap
    }

    private fun fetchRegionSettlements(
        region: RegionTarget,
        countryCode: String,
        fallbackRegion: String,
        logFn: (String) -> Unit,
        isCancelled: () -> Boolean
    ): SpatialSettlementIndex {
        val areaClause = if (region.relId > 0) {
            "area(${3600000000L + region.relId})->.a;"
        } else {
            "area[\"ISO3166-1\"=\"${countryCode.uppercase()}\"][admin_level=2]->.a;"
        }
        val qArea = """
            [out:csv(::id, ::type, ::lat, ::lon, "name:uk", "name", "name:en", "place", "admin_level"; false; ";")][timeout:60];
            $areaClause
            (
              node["place"~"city|town|village|hamlet|suburb|isolated_dwelling"](area.a);
              relation["place"~"city|town|village|municipality"](area.a);
            );
            out center;
        """.trimIndent()
        var rawCsv = client.query(qArea, timeoutSec = 65, logFn = logFn, isCancelled = isCancelled, maxCycles = 1)

        val hasSettlements = (rawCsv?.lines()?.count { it.isNotBlank() } ?: 0) > 2
        if (!hasSettlements && region.isBbox && !isCancelled()) {
            val bboxStr = "(${region.minLat}, ${region.minLon}, ${region.maxLat}, ${region.maxLon})"
            val qBbox = """
                [out:csv(::id, ::type, ::lat, ::lon, "name:uk", "name", "name:en", "place", "admin_level"; false; ";")][timeout:60];
                (
                  node["place"~"city|town|village|hamlet|suburb|isolated_dwelling"]$bboxStr;
                  relation["place"~"city|town|village|municipality"]$bboxStr;
                );
                out center;
            """.trimIndent()
            rawCsv = client.query(qBbox, timeoutSec = 65, logFn = logFn, isCancelled = isCancelled, maxCycles = 1)
        }
        val settlements = mutableListOf<SettlementItem>()
        val nodeStems = mutableMapOf<String, String>()

        if (!rawCsv.isNullOrBlank()) {
            for (line in rawCsv.lines()) {
                val parts = line.trim().split(";")
                if (parts.size < 8) continue
                val lat = parts[2].trim().toDoubleOrNull() ?: continue
                val lon = parts[3].trim().toDoubleOrNull() ?: continue
                val nameUk = parts[4].trim()
                val nameLocal = parts[5].trim()
                val nameEn = parts[6].trim()
                val rawName = nameUk.ifBlank { nameLocal.ifBlank { nameEn } }
                if (rawName.isBlank()) continue
                val cleanName = AddressNormalizer.cleanSettlementNameUniversal(rawName)
                if (cleanName.isBlank()) continue

                val ptype = parts[7].trim()
                val al = if (parts.size > 8) parts[8].trim() else ""

                if (ptype in listOf("city", "town", "village")) {
                    val stem = cleanName.take(4).lowercase()
                    if (!nodeStems.containsKey(stem)) {
                        nodeStems[stem] = cleanName
                    }
                }
                settlements.add(SettlementItem(lat, lon, cleanName, ptype, al))
            }
        }
        return SpatialSettlementIndex(settlements, fallbackRegion)
    }

    private fun extractAddressesFromPoiFile(
        poiFile: File,
        country: MapCountry,
        ingestor: AddressIngestor,
        isCancelled: () -> Boolean = { false }
    ): Int {
        if (!poiFile.exists() || poiFile.length() < 1024L || isCancelled()) return 0
        var count = 0
        try {
            val poiDb = SQLiteDatabase.openDatabase(poiFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = poiDb.rawQuery("""
                SELECT i.lat, i.lon, p.data 
                FROM poi_data p 
                JOIN poi_index i ON p.id = i.id 
                WHERE p.data LIKE '%addr:%';
            """.trimIndent(), null)

            val appDb = ingestor.db
            appDb.beginTransaction()
            try {
                cursor.use {
                    var inTx = 0
                    while (it.moveToNext()) {
                        if (isCancelled()) break
                        val latRaw = it.getDouble(0)
                        val lonRaw = it.getDouble(1)
                        val data = it.getString(2) ?: ""

                        var street = ""
                        var house = ""
                        var city = ""

                        for (entry in data.split("\r")) {
                            if (entry.contains("=")) {
                                val kv = entry.split("=", limit = 2)
                                val k = kv[0].trim().lowercase()
                                val v = kv[1].trim()
                                when (k) {
                                    "addr:street" -> street = v
                                    "addr:housenumber" -> house = v
                                    "addr:city", "addr:town", "addr:village", "addr:place" -> if (city.isBlank()) city = v
                                }
                            }
                        }

                        if (city.isBlank()) city = country.nameUk
                        if (street.isNotBlank() || house.isNotBlank()) {
                            val cityId = ingestor.getOrCreatePlace(ingestor.countryId, city, level = 8)
                            val streetId = ingestor.getOrCreateStreet(cityId, street, country.nameUk, city)
                            ingestor.addHouse(streetId, house, latRaw, lonRaw)
                            count++
                            inTx++
                            if (inTx >= 2000) {
                                ingestor.flush()
                                appDb.setTransactionSuccessful()
                                appDb.endTransaction()
                                appDb.beginTransaction()
                                inTx = 0
                            }
                        }
                    }
                }
                ingestor.flush()
                appDb.setTransactionSuccessful()
            } finally {
                if (appDb.inTransaction()) {
                    appDb.endTransaction()
                }
            }
            poiDb.close()
        } catch (e: Exception) {
            AppLogger.log(TAG, "extractAddressesFromPoiFile", false, "POI address extraction warning: ${e.message}")
        }
        return count
    }

    /**
     * Unified function for importing named POI points into address database (addr.db).
     * Used both during full country address database generation and for updating an existing database.
     */
    fun importPoiLocationsToAddressDb(
        context: Context,
        country: MapCountry,
        onProgress: (progressRatio: Double, statusText: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Int {
        val db = AddressDb.openDatabase(context, writable = true) ?: return 0
        AddressDb.ensureTablesExist(db)

        val candidatePoiFiles = listOf(
            File(MushroomStorageManager.poiDir, country.poiFileName),
            File(File(context.filesDir, "poi"), country.poiFileName)
        )
        val poiFile = candidatePoiFiles.firstOrNull { it.exists() && it.length() > 1024L }
        if (poiFile == null || isCancelled()) {
            AppLogger.log(TAG, "importPoiLocationsToAddressDb", false, "POI file not found for ${country.code}")
            return 0
        }

        var count = 0
        var poiDb: SQLiteDatabase? = null
        var stmtInsertPoi: android.database.sqlite.SQLiteStatement? = null
        var stmtInsertPoiFts: android.database.sqlite.SQLiteStatement? = null

        try {
            poiDb = SQLiteDatabase.openDatabase(poiFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

            val totalNamedPois = try {
                val c = poiDb.rawQuery("SELECT count(1) FROM poi_data WHERE data LIKE '%name=%';", null)
                c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            } catch (_: Exception) { 0 }

            if (totalNamedPois == 0 || isCancelled()) {
                return 0
            }

            val countryCode = country.code.uppercase()
            try {
                db.beginTransaction()
                try {
                    db.execSQL("""
                        DELETE FROM poi_fts 
                        WHERE rowid IN (SELECT id FROM poi_places WHERE country_code = ?);
                    """.trimIndent(), arrayOf(countryCode))
                    db.execSQL("DELETE FROM poi_places WHERE country_code = ?;", arrayOf(countryCode))
                    db.execSQL("DELETE FROM poi_country_ranges WHERE country_code = ?;", arrayOf(countryCode))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "importPoiLocationsToAddressDb", false, "Country cleanup error: ${e.message}")
            }

            stmtInsertPoi = db.compileStatement("""
                INSERT INTO poi_places (poi_id, name, category_id, street_id, house_number, lat, lon, country_code) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent())

            stmtInsertPoiFts = try {
                db.compileStatement("""
                    INSERT INTO poi_fts (rowid, poi_name, category_name, city_name, street_name) 
                    VALUES (?, ?, ?, ?, ?);
                """.trimIndent())
            } catch (_: Exception) {
                null
            }

            val catDefs = PoiManager.DEFAULT_CATEGORIES.associateBy { it.id }
            val addrMatchCache = mutableMapOf<Pair<String, String>, Pair<Long, String>?>()

            val chunkSize = 5000
            var lastId = -1L
            var minInsertedId = Long.MAX_VALUE
            var maxInsertedId = 0L
            var hasMore = true

            onProgress(0.01, "${country.nameUk}: Початок імпорту точок інтересу (0/$totalNamedPois)")

            while (hasMore && !isCancelled()) {
                val chunkCursor = poiDb.rawQuery("""
                    SELECT p.id, i.lat, i.lon, p.data 
                    FROM poi_data p 
                    JOIN poi_index i ON p.id = i.id 
                    WHERE p.id > ? AND p.data LIKE '%name=%' 
                    ORDER BY p.id ASC 
                    LIMIT ?;
                """.trimIndent(), arrayOf(lastId.toString(), chunkSize.toString()))

                var chunkCount = 0

                db.beginTransaction()
                try {
                    chunkCursor.use { cursor ->
                        while (cursor.moveToNext()) {
                            if (isCancelled()) break

                            val poiId = cursor.getLong(0)
                            lastId = poiId
                            chunkCount++

                            val latRaw = cursor.getDouble(1)
                            val lonRaw = cursor.getDouble(2)
                            val data = cursor.getString(3) ?: ""

                            var nameUk: String? = null
                            var nameEn: String? = null
                            var nameDef: String? = null
                            var streetTag = ""
                            var houseTag = ""
                            var cityTag = ""
                            val tagMap = mutableMapOf<String, String>()

                            for (entry in data.split("\r")) {
                                if (entry.contains("=")) {
                                    val kv = entry.split("=", limit = 2)
                                    val k = kv[0].trim().lowercase()
                                    val v = kv[1].trim()
                                    tagMap[k] = v
                                    when (k) {
                                        "name" -> nameDef = v
                                        "name:uk" -> nameUk = v
                                        "name:en" -> nameEn = v
                                        "addr:street" -> streetTag = v
                                        "addr:housenumber" -> houseTag = v
                                        "addr:city", "addr:town", "addr:village", "addr:place" -> if (cityTag.isBlank()) cityTag = v
                                    }
                                }
                            }

                            val chosenName = (nameUk?.takeIf { s -> s.isNotBlank() }
                                ?: nameDef?.takeIf { s -> s.isNotBlank() }
                                ?: nameEn?.takeIf { s -> s.isNotBlank() })?.trim()

                            if (chosenName.isNullOrBlank()) {
                                continue
                            }

                            val catId = PoiManager.resolveCategoryId(tagMap) ?: "other"
                            val catDef = catDefs[catId]
                            val catTitle = if (catDef != null) "${catDef.titleUk} ${catDef.titleEn}" else catId

                            val latMicro = kotlin.math.round(latRaw * 1_000_000.0).toInt()
                            val lonMicro = kotlin.math.round(lonRaw * 1_000_000.0).toInt()

                            var matchedStreetId: Long? = null
                            var matchedHouseNum: String? = null
                            var matchedCityName = cityTag
                            var matchedStreetName = streetTag

                            // 1. Check address tag match
                            if (streetTag.isNotBlank() && houseTag.isNotBlank()) {
                                val cacheKey = Pair(streetTag.lowercase(), houseTag.lowercase())
                                if (addrMatchCache.containsKey(cacheKey)) {
                                    val cached = addrMatchCache[cacheKey]
                                    if (cached != null) {
                                        matchedStreetId = cached.first
                                        matchedHouseNum = cached.second
                                    }
                                } else {
                                    val cHouse = db.rawQuery("""
                                        SELECT s.id, h.number 
                                        FROM street_names sn
                                        JOIN streets s ON s.name_id = sn.id
                                        JOIN houses h ON h.street_id = s.id
                                        WHERE LOWER(sn.name) = ? AND (h.number = ? OR LOWER(h.number) = ?)
                                        LIMIT 1;
                                    """.trimIndent(), arrayOf(streetTag.lowercase(), houseTag, houseTag.lowercase()))
                                    val found = cHouse.use { ch ->
                                        if (ch.moveToFirst()) Pair(ch.getLong(0), ch.getString(1)) else null
                                    }
                                    if (addrMatchCache.size > 5000) {
                                        addrMatchCache.clear()
                                    }
                                    addrMatchCache[cacheKey] = found
                                    if (found != null) {
                                        matchedStreetId = found.first
                                        matchedHouseNum = found.second
                                    }
                                }
                            }

                            // Insert POI place (autoincrement ID)
                            stmtInsertPoi.bindLong(1, poiId)
                            stmtInsertPoi.bindString(2, chosenName)
                            stmtInsertPoi.bindString(3, catId)
                            val sId = matchedStreetId
                            if (sId != null) {
                                stmtInsertPoi.bindLong(4, sId)
                            } else {
                                stmtInsertPoi.bindNull(4)
                            }
                            val hNum = matchedHouseNum
                            if (hNum != null) {
                                stmtInsertPoi.bindString(5, hNum)
                            } else {
                                stmtInsertPoi.bindNull(5)
                            }
                            stmtInsertPoi.bindLong(6, latMicro.toLong())
                            stmtInsertPoi.bindLong(7, lonMicro.toLong())
                            stmtInsertPoi.bindString(8, countryCode)
                            val rowId = stmtInsertPoi.executeInsert()

                            if (rowId > 0) {
                                if (rowId < minInsertedId) minInsertedId = rowId
                                if (rowId > maxInsertedId) maxInsertedId = rowId

                                if (stmtInsertPoiFts != null) {
                                    stmtInsertPoiFts.bindLong(1, rowId)
                                    stmtInsertPoiFts.bindString(2, chosenName)
                                    stmtInsertPoiFts.bindString(3, catTitle)
                                    stmtInsertPoiFts.bindString(4, matchedCityName)
                                    stmtInsertPoiFts.bindString(5, matchedStreetName)
                                    try {
                                        stmtInsertPoiFts.executeInsert()
                                    } catch (_: Exception) {}
                                }
                                count++
                            }
                        }
                    }

                    if (!isCancelled()) {
                        db.setTransactionSuccessful()
                    }
                } finally {
                    try {
                        if (db.inTransaction()) {
                            db.endTransaction()
                        }
                    } catch (_: Exception) {}
                }

                if (chunkCount < chunkSize) {
                    hasMore = false
                }

                val ratio = if (totalNamedPois > 0) (count.toDouble() / totalNamedPois).coerceIn(0.0, 1.0) else 0.5
                onProgress(ratio, "${country.nameUk}: Додано $count точок інтересу (POI)")
            }

            if (!isCancelled() && count > 0 && minInsertedId <= maxInsertedId) {
                db.execSQL("""
                    INSERT OR REPLACE INTO poi_country_ranges (country_code, min_id, max_id, total_count) 
                    VALUES (?, ?, ?, ?);
                """.trimIndent(), arrayOf(countryCode, minInsertedId, maxInsertedId, count))
            }

            try {
                db.execSQL("INSERT INTO poi_fts(poi_fts) VALUES('optimize');")
            } catch (_: Exception) {}
            try {
                db.rawQuery("PRAGMA wal_checkpoint(PASSIVE);", null).use { it.moveToFirst() }
            } catch (_: Exception) {}

            onProgress(1.0, "${country.nameUk}: Завершено імпорт POI ($count точок)")
            AppLogger.log(TAG, "importPoiLocationsToAddressDb", true, "Imported $count POI points for ${country.code}")
            return count
        } catch (e: Exception) {
            AppLogger.log(TAG, "importPoiLocationsToAddressDb", false, "Error importing POI: ${e.message}")
            return 0
        } finally {
            try { stmtInsertPoi?.close() } catch (_: Exception) {}
            try { stmtInsertPoiFts?.close() } catch (_: Exception) {}
            try { poiDb?.close() } catch (_: Exception) {}
        }
    }

    fun ingestCountryAddresses(
        context: Context,
        country: MapCountry,
        onProgress: (progressRatio: Double, statusText: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val db = AddressDb.openDatabase(context, writable = true) ?: return false
        AddressDb.ensureTablesExist(db)
        val ingestor = AddressIngestor(db, country.code, country.nameUk)
        var stmtCheckCoord: android.database.sqlite.SQLiteStatement? = null

        try {
            onProgress(0.02, "${country.nameUk}: Пошук регіонів...")

            val regions = fetchOverpassRegions(
                country = country,
                logFn = { detail ->
                    AppLogger.log(TAG, "fetchRegions", true, detail)
                },
                isCancelled = isCancelled,
                maxPasses = 3,
                db = db,
                countryId = ingestor.countryId
            )

            if (regions.isEmpty() || isCancelled()) {
                AppLogger.log(TAG, "ingestCountryAddresses", false, "No regions could be obtained for ${country.code}. Aborting.")
                return false
            }

            val totalRegions = regions.size
            var pending = regions.mapIndexed { idx, reg -> Pair(idx + 1, reg) }
            val maxPasses = 5

            // Пауза 3.5 сек, щоб сервер звільнив слот після запиту регіонів перед стартом завантаження
            if (!isCancelled()) {
                client.interruptibleSleep(3500L, isCancelled)
            }

            // Створення тимчасового індексу для надшвидкої дедуплікації по базі без завантаження в Java Heap
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_temp_import_coords ON houses(lat, lon);")
            } catch (_: Exception) {}
            stmtCheckCoord = db.compileStatement("SELECT count(*) FROM houses WHERE lat = ? AND lon = ?;")

            val loadedOsmIds = mutableSetOf<Long>()
            val loadedNames = mutableSetOf<String>()
            try {
                val pCur = db.rawQuery("""
                    SELECT DISTINCT ap.osm_id, ap.name
                    FROM streets s
                    JOIN admin_places ap ON s.region_id = ap.id
                    WHERE s.country_id = ?
                      AND EXISTS (SELECT 1 FROM houses h WHERE h.street_id = s.id LIMIT 1);
                """.trimIndent(), arrayOf(ingestor.countryId.toString()))
                pCur.use {
                    while (it.moveToNext()) {
                        val osmId = it.getLong(0)
                        val name = it.getString(1) ?: ""
                        if (osmId > 0) loadedOsmIds.add(osmId)
                        if (name.isNotBlank()) loadedNames.add(name.trim().lowercase())
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "preflight", false, "Preflight loaded regions warning: ${e.message}")
            }

            fun isRegionAlreadyLoaded(reg: RegionTarget): Boolean {
                if (reg.relId > 0 && loadedOsmIds.contains(reg.relId)) return true
                val clean = reg.name.trim().lowercase()
                if (loadedNames.contains(clean)) return true
                val stem = clean.replace(" область", "")
                    .replace(" обл.", "")
                    .replace(" автономна республіка", "")
                    .replace(" ар ", "")
                    .trim()
                if (stem.length >= 4 && loadedNames.any { loadedName ->
                    val lStem = loadedName.replace(" область", "").replace(" обл.", "").trim()
                    lStem == stem || loadedName.contains(stem) || clean.contains(lStem)
                }) {
                    return true
                }
                return false
            }

            for (pass in 1..maxPasses) {
                if (pending.isEmpty() || isCancelled()) break

                val failed = mutableListOf<Pair<Int, RegionTarget>>()

                for ((idx, region) in pending) {
                    if (isCancelled()) break

                    val statusMsg = "${country.nameUk} ($totalRegions регіонів) • Регіон $idx/$totalRegions: ${region.name}"

                    if (pass == 1 && isRegionAlreadyLoaded(region)) {
                        onProgress((idx.toDouble() / totalRegions.toDouble()) * 0.85, statusMsg)
                        AppLogger.log(TAG, "ingest", true, "Регіон $idx/$totalRegions (${region.name}) вже завантажено в базі, миттєво пропускаємо")
                        continue
                    }

                    val curRatio = (idx.toDouble() / totalRegions.toDouble()) * 0.85
                    onProgress(curRatio, statusMsg)

                    val logCallback: (String) -> Unit = { detail ->
                        AppLogger.log(TAG, "fetchAddressData", true, "$statusMsg • $detail")
                    }

                    var curRegion = region
                    if (!curRegion.isBbox && curRegion.relId > 0) {
                        try {
                            val bq = "[out:json][timeout:15]; relation(${curRegion.relId}); out bb;"
                            val bRaw = client.query(bq, timeoutSec = 20, logFn = logCallback, isCancelled = isCancelled, maxCycles = 1)
                            if (!bRaw.isNullOrBlank()) {
                                val bObj = JSONObject(bRaw).optJSONArray("elements")?.optJSONObject(0)?.optJSONObject("bounds")
                                if (bObj != null) {
                                    curRegion = RegionTarget(
                                        relId = curRegion.relId,
                                        name = curRegion.name,
                                        minLat = bObj.optDouble("minlat"),
                                        minLon = bObj.optDouble("minlon"),
                                        maxLat = bObj.optDouble("maxlat"),
                                        maxLon = bObj.optDouble("maxlon")
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    val assocStreets = fetchAssociatedStreets(curRegion, country.code, logCallback, isCancelled)
                    if (isCancelled()) break
                    client.interruptibleSleep(1500L, isCancelled)
                    val settlementIndex = fetchRegionSettlements(curRegion, country.code, curRegion.name, logCallback, isCancelled)
                    if (isCancelled()) break
                    client.interruptibleSleep(1500L, isCancelled)

                    // Спосіб 1: Точна полігональна зона (area)
                    val areaClause = if (curRegion.relId > 0) {
                        "area(${3600000000L + curRegion.relId})->.a;"
                    } else {
                        "area[\"ISO3166-1\"=\"${country.code.uppercase()}\"][admin_level=2]->.a;"
                    }
                    val qArea = """
                        [out:csv(::id, ::type, ::lat, ::lon, "addr:city", "addr:town", "addr:village", "addr:hamlet", "addr:suburb", "addr:place", "addr:district", "addr:county", "addr:subdistrict", "addr:municipality", "addr:street", "contact:street", "addr:parentstreet", "is_in:street", "addr:housenumber", "addr:housename", "addr:conscriptionnumber", "is_in:city", "is_in:town", "is_in:village", "is_in:municipality", "addr:country", "is_in:country"; false; ";")][timeout:300];
                        $areaClause
                        (
                          node["addr:housenumber"](area.a);
                          way["addr:housenumber"](area.a);
                          relation["addr:housenumber"](area.a);
                        );
                        out center;
                    """.trimIndent()

                    val tempFile = File(context.cacheDir, "temp_houses_${curRegion.relId}_${System.currentTimeMillis()}.tsv")
                    try {
                        var hasHouses = client.queryToFile(qArea, tempFile, timeoutSec = 310, logFn = logCallback, isCancelled = isCancelled, maxCycles = 1)

                        val houseCountInArea = if (hasHouses && tempFile.exists()) {
                            var count = 0
                            tempFile.bufferedReader(Charsets.UTF_8).use { reader ->
                                while (count < 10) {
                                    if (reader.readLine()?.isNotBlank() == true) count++ else break
                                }
                            }
                            count
                        } else 0

                        // Спосіб 2: Якщо через area сервер нічого не повернув (відсутній шар area на цьому сервері) або помилка — резервний запит через географічну рамку (bbox)
                        if (houseCountInArea < 10 && curRegion.isBbox && !isCancelled()) {
                            tempFile.delete()
                            logCallback("Сервер не повернув будинки через area для ${curRegion.name}. Перехід на географічну рамку (bbox)...")
                            val bboxStr = "(${curRegion.minLat}, ${curRegion.minLon}, ${curRegion.maxLat}, ${curRegion.maxLon})"
                            val qBbox = """
                                [out:csv(::id, ::type, ::lat, ::lon, "addr:city", "addr:town", "addr:village", "addr:hamlet", "addr:suburb", "addr:place", "addr:district", "addr:county", "addr:subdistrict", "addr:municipality", "addr:street", "contact:street", "addr:parentstreet", "is_in:street", "addr:housenumber", "addr:housename", "addr:conscriptionnumber", "is_in:city", "is_in:town", "is_in:village", "is_in:municipality", "addr:country", "is_in:country"; false; ";")][timeout:300];
                                (
                                  node["addr:housenumber"]$bboxStr;
                                  way["addr:housenumber"]$bboxStr;
                                  relation["addr:housenumber"]$bboxStr;
                                );
                                out center;
                            """.trimIndent()
                            hasHouses = client.queryToFile(qBbox, tempFile, timeoutSec = 310, logFn = logCallback, isCancelled = isCancelled, maxCycles = 2)
                        }

                        if (!hasHouses || !tempFile.exists() || tempFile.length() == 0L) {
                            failed.add(Pair(idx, curRegion))
                            continue
                        }

                        db.beginTransaction()
                        try {
                            val regPlaceId = ingestor.getOrCreatePlace(ingestor.countryId, curRegion.name, level = 4, osmId = if (curRegion.relId > 0) curRegion.relId else null)

                            tempFile.bufferedReader(Charsets.UTF_8).use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val trimmed = line!!.trim()
                                    if (trimmed.isBlank()) continue
                                    val parts = if (trimmed.contains(";")) trimmed.split(";") else trimmed.split("\t")
                                    if (parts.size < 6) continue

                                    // Універсальний фільтр за тегом країни (якщо вказано явний чужий код ISO)
                                    val rowCountry = (parts.getOrNull(25)?.trim() ?: "").ifBlank { parts.getOrNull(26)?.trim() ?: "" }
                                    if (rowCountry.isNotBlank() && !rowCountry.equals(country.code, ignoreCase = true)) {
                                        continue
                                    }

                                    val osmId = parts[0].trim().toLongOrNull() ?: 0L
                                    val osmType = parts[1].trim()
                                    val lat = parts[2].trim().toDoubleOrNull() ?: continue
                                    val lon = parts[3].trim().toDoubleOrNull() ?: continue

                                    val rowCity = parts.getOrNull(4)?.trim() ?: ""
                                    val rowTown = parts.getOrNull(5)?.trim() ?: ""
                                    val rowVillage = parts.getOrNull(6)?.trim() ?: ""
                                    val rowHamlet = parts.getOrNull(7)?.trim() ?: ""
                                    val rowSuburb = parts.getOrNull(8)?.trim() ?: ""
                                    val rowPlace = parts.getOrNull(9)?.trim() ?: ""

                                    val district = (parts.getOrNull(10)?.trim() ?: "").ifBlank { parts.getOrNull(11)?.trim() ?: "" }
                                    val subdistrict = (parts.getOrNull(12)?.trim() ?: "").ifBlank { parts.getOrNull(13)?.trim() ?: "" }

                                    val rowStreet = parts.getOrNull(14)?.trim() ?: ""
                                    val contactStreet = parts.getOrNull(15)?.trim() ?: ""
                                    val parentStreet = parts.getOrNull(16)?.trim() ?: ""
                                    val isInStreet = parts.getOrNull(17)?.trim() ?: ""

                                    val houseNumber = (parts.getOrNull(18)?.trim() ?: "")
                                        .ifBlank { (parts.getOrNull(19)?.trim() ?: "")
                                            .ifBlank { parts.getOrNull(20)?.trim() ?: "" } }

                                    val isInCity = parts.getOrNull(21)?.trim() ?: ""
                                    val isInTown = parts.getOrNull(22)?.trim() ?: ""
                                    val isInVillage = parts.getOrNull(23)?.trim() ?: ""
                                    val isInMuni = parts.getOrNull(24)?.trim() ?: ""

                                    var street = rowStreet.ifBlank { contactStreet.ifBlank { parentStreet.ifBlank { isInStreet } } }
                                    if (street.isBlank() && osmId > 0) {
                                        street = assocStreets[Pair(osmType, osmId)] ?: ""
                                    }
                                    if (street.isBlank() && rowPlace.isNotBlank() && houseNumber.isNotBlank()) {
                                        street = rowPlace
                                    }

                                    if (houseNumber.isBlank() && street.isBlank()) continue

                                    val latI = if (kotlin.math.abs(lat) > 1000.0) (lat / 10.0).roundToInt() else (lat * 100_000.0).roundToInt()
                                    val lonI = if (kotlin.math.abs(lon) > 1000.0) (lon / 10.0).roundToInt() else (lon * 100_000.0).roundToInt()

                                    // Дедуплікація через тимчасовий індекс SQLite
                                    if (stmtCheckCoord != null) {
                                        stmtCheckCoord.bindLong(1, latI.toLong())
                                        stmtCheckCoord.bindLong(2, lonI.toLong())
                                        if (stmtCheckCoord.simpleQueryForLong() > 0L) {
                                            continue
                                        }
                                    }

                                    var city = rowCity.ifBlank { rowTown.ifBlank { rowVillage.ifBlank { rowHamlet.ifBlank { rowSuburb.ifBlank { isInCity.ifBlank { isInTown.ifBlank { isInVillage.ifBlank { isInMuni.ifBlank { rowPlace } } } } } } } } }
                                    if (city.isBlank()) {
                                        city = settlementIndex.resolve(lat, lon)
                                    }

                                    var curParent = regPlaceId
                                    if (district.isNotBlank()) curParent = ingestor.getOrCreatePlace(curParent, district, level = 6)
                                    if (subdistrict.isNotBlank()) curParent = ingestor.getOrCreatePlace(curParent, subdistrict, level = 7)

                                    val cityPlaceId = ingestor.getOrCreatePlace(curParent, city, level = 8)
                                    val streetId = ingestor.getOrCreateStreet(cityPlaceId, street, region.name, city, regPlaceId)

                                    ingestor.addHouse(streetId, houseNumber, lat, lon)
                                }
                            }

                            ingestor.flush()
                            db.setTransactionSuccessful()
                            if (region.relId > 0) loadedOsmIds.add(region.relId)
                            loadedNames.add(region.name.trim().lowercase())
                        } finally {
                            db.endTransaction()
                        }
                    } finally {
                        tempFile.delete()
                    }
                    onProgress((idx.toDouble() / totalRegions.toDouble()) * 0.85, statusMsg)
                    if (!isCancelled()) {
                        client.interruptibleSleep(3000L, isCancelled)
                    }
                }

                pending = failed
                if (pending.isNotEmpty() && pass < maxPasses && !isCancelled()) {
                    client.interruptibleSleep(3000L, isCancelled)
                }
            }

            if (isCancelled()) return false

            val totalHouses = getCountryHouseCount(context, country.code)
            if (totalHouses < 50L) {
                AppLogger.log(TAG, "ingestCountryAddresses", false, "Failure: only $totalHouses houses ingested for ${country.code}. Aborting to avoid empty 12KB database.")
                return false
            }

            // Extract from POI file
            val candidatePoiFiles = listOf(
                File(MushroomStorageManager.poiDir, country.poiFileName),
                File(File(context.filesDir, "poi"), country.poiFileName)
            )
            for (pFil in candidatePoiFiles) {
                if (pFil.exists() && pFil.length() > 1024L && !isCancelled()) {
                    onProgress(0.88, "${country.nameUk}: Експорт POI адрес (${pFil.name})")
                    extractAddressesFromPoiFile(pFil, country, ingestor, isCancelled)
                    break
                }
            }

            if (isCancelled()) return false

            onProgress(0.95, "${country.nameUk}: Оптимізація індексу FTS")

            // Final FTS optimize, WAL checkpoint and VACUUM
            AddressDb.optimizeAndVacuum(db)
            val finalHouses = getCountryHouseCount(context, country.code)
            onProgress(1.0, "${country.nameUk}: Готово ($finalHouses адрес)")
            return true

        } catch (e: Exception) {
            AppLogger.log(TAG, "ingestCountryAddresses", false, "Ingest failed: ${e.message}")
            return false
        } finally {
            try { stmtCheckCoord?.close() } catch (_: Exception) {}
            try { db.execSQL("DROP INDEX IF EXISTS idx_temp_import_coords;") } catch (_: Exception) {}
            ingestor.close()
            try {
                if (db.inTransaction()) {
                    db.endTransaction()
                }
                db.rawQuery("PRAGMA wal_checkpoint(FULL);", null).use { it.moveToFirst() }
            } catch (_: Exception) {}
            AddressDb.closeDatabase()
        }
    }
    fun needsPoiUpdate(context: Context, country: MapCountry): Boolean {
        val dbFile = AddressDb.getDatabaseFile(context)
        if (!dbFile.exists() || dbFile.length() < 1024L) return false
        if (!hasAddressesForCountry(context, country.code)) return false

        val candidatePoiFiles = listOf(
            File(MushroomStorageManager.poiDir, country.poiFileName),
            File(File(context.filesDir, "poi"), country.poiFileName)
        )
        val hasLocalPoi = candidatePoiFiles.any { it.exists() && it.length() > 1024L }
        if (!hasLocalPoi) return false

        val db = AddressDb.openDatabase(context, writable = false) ?: return false
        return !AddressDb.hasPoiDataForCountry(db, country.code)
    }
}
