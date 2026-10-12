package com.olegskal.mushroom.address

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
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

            // Стратегія 1: Швидкий запит за ISO3166-2 (за алгоритмом скрипта)
            val q1 = """
                [out:csv(::id, "name:uk", "name", "name:en"; false; ";")][timeout:30];
                relation["admin_level"="4"]["ISO3166-2"~"^$code"];
                out tags;
            """.trimIndent()
            var raw = client.query(q1, timeoutSec = 35, logFn = logFn, isCancelled = isCancelled, maxCycles = 2)

            // Стратегія 2: Резервний запит через межі країни (за алгоритмом скрипта)
            if (raw.isNullOrBlank() && !isCancelled()) {
                val q2 = """
                    [out:csv(::id, "name:uk", "name", "name:en"; false; ";")][timeout:45];
                    area["ISO3166-1"="$code"][admin_level=2]->.c;
                    rel(area.c)["admin_level"="4"];
                    out tags;
                """.trimIndent()
                raw = client.query(q2, timeoutSec = 50, logFn = logFn, isCancelled = isCancelled, maxCycles = 2)
            }

            if (!raw.isNullOrBlank()) {
                val seenNames = mutableSetOf<String>()
                for (line in raw.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) continue
                    val parts = trimmed.split(";")
                    val relId = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
                    val nameUk = parts.getOrNull(1)?.trim() ?: ""
                    val nameLocal = parts.getOrNull(2)?.trim() ?: ""
                    val nameEn = parts.getOrNull(3)?.trim() ?: ""
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
                    regions.add(RegionTarget(relId, name))
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
        val q = if (region.isBbox) {
            val bboxStr = "(${region.minLat}, ${region.minLon}, ${region.maxLat}, ${region.maxLon})"
            """
                [out:json][timeout:60];
                relation["type"="associatedStreet"]$bboxStr;
                out body;
            """.trimIndent()
        } else {
            val areaClause = if (region.relId > 0) {
                "area(${3600000000L + region.relId})->.a;"
            } else {
                "area[\"ISO3166-1\"=\"${countryCode.uppercase()}\"][admin_level=2]->.a;"
            }
            """
                [out:json][timeout:60];
                $areaClause
                relation["type"="associatedStreet"](area.a);
                out body;
            """.trimIndent()
        }
        val raw = client.query(q, timeoutSec = 65, logFn = logFn, isCancelled = isCancelled, maxCycles = 2) ?: return emptyMap()
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
        val q = if (region.isBbox) {
            val bboxStr = "(${region.minLat}, ${region.minLon}, ${region.maxLat}, ${region.maxLon})"
            """
                [out:csv(::id, ::type, ::lat, ::lon, "name:uk", "name", "name:en", "place", "admin_level"; false; ";")][timeout:60];
                (
                  node["place"~"city|town|village|hamlet|suburb|isolated_dwelling"]$bboxStr;
                  relation["place"~"city|town|village|municipality"]$bboxStr;
                );
                out center;
            """.trimIndent()
        } else {
            val areaClause = if (region.relId > 0) {
                "area(${3600000000L + region.relId})->.a;"
            } else {
                "area[\"ISO3166-1\"=\"${countryCode.uppercase()}\"][admin_level=2]->.a;"
            }
            """
                [out:csv(::id, ::type, ::lat, ::lon, "name:uk", "name", "name:en", "place", "admin_level"; false; ";")][timeout:60];
                $areaClause
                (
                  node["place"~"city|town|village|hamlet|suburb|isolated_dwelling"](area.a);
                  relation["place"~"city|town|village|municipality"](area.a);
                );
                out center;
            """.trimIndent()
        }
        val rawCsv = client.query(q, timeoutSec = 65, logFn = logFn, isCancelled = isCancelled, maxCycles = 2)
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

    fun ingestCountryAddresses(
        context: Context,
        country: MapCountry,
        onProgress: (progressRatio: Double, statusText: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val db = AddressDb.openDatabase(context, writable = true) ?: return false
        AddressDb.ensureTablesExist(db)
        val ingestor = AddressIngestor(db, country.code, country.nameUk)

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

            // Префлайт-перевірка: миттєво за 1 швидкий SQL-запит зчитуємо всі регіони, що вже мають будинки в базі
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

                    val assocStreets = fetchAssociatedStreets(region, country.code, logCallback, isCancelled)
                    if (isCancelled()) break
                    client.interruptibleSleep(1500L, isCancelled)
                    val settlementIndex = fetchRegionSettlements(region, country.code, region.name, logCallback, isCancelled)
                    if (isCancelled()) break
                    client.interruptibleSleep(1500L, isCancelled)

                    val q = if (region.isBbox) {
                        val bboxStr = "(${region.minLat}, ${region.minLon}, ${region.maxLat}, ${region.maxLon})"
                        """
                            [out:csv(::id, ::type, ::lat, ::lon, "addr:city", "addr:town", "addr:village", "addr:hamlet", "addr:suburb", "addr:place", "addr:district", "addr:county", "addr:subdistrict", "addr:municipality", "addr:street", "contact:street", "addr:parentstreet", "is_in:street", "addr:housenumber", "addr:housename", "addr:conscriptionnumber", "is_in:city", "is_in:town", "is_in:village", "is_in:municipality"; false; ";")][timeout:300];
                            (
                              node["addr:housenumber"]$bboxStr;
                              way["addr:housenumber"]$bboxStr;
                              relation["addr:housenumber"]$bboxStr;
                            );
                            out center;
                        """.trimIndent()
                    } else {
                        val areaClause = if (region.relId > 0) {
                            "area(${3600000000L + region.relId})->.a;"
                        } else {
                            "area[\"ISO3166-1\"=\"${country.code.uppercase()}\"][admin_level=2]->.a;"
                        }
                        """
                            [out:csv(::id, ::type, ::lat, ::lon, "addr:city", "addr:town", "addr:village", "addr:hamlet", "addr:suburb", "addr:place", "addr:district", "addr:county", "addr:subdistrict", "addr:municipality", "addr:street", "contact:street", "addr:parentstreet", "is_in:street", "addr:housenumber", "addr:housename", "addr:conscriptionnumber", "is_in:city", "is_in:town", "is_in:village", "is_in:municipality"; false; ";")][timeout:300];
                            $areaClause
                            (
                              node["addr:housenumber"](area.a);
                              way["addr:housenumber"](area.a);
                              relation["addr:housenumber"](area.a);
                            );
                            out center;
                        """.trimIndent()
                    }

                    val tsvData = client.query(q, timeoutSec = 310, logFn = logCallback, isCancelled = isCancelled, maxCycles = 3)
                    if (tsvData.isNullOrBlank()) {
                        failed.add(Pair(idx, region))
                        continue
                    }

                    db.beginTransaction()
                    try {
                        val regPlaceId = ingestor.getOrCreatePlace(ingestor.countryId, region.name, level = 4, osmId = if (region.relId > 0) region.relId else null)

                        for (line in tsvData.lineSequence()) {
                            val trimmed = line.trim()
                            if (trimmed.isBlank()) continue
                            val parts = if (trimmed.contains(";")) trimmed.split(";") else trimmed.split("\t")
                            if (parts.size < 6) continue

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

                        ingestor.flush()
                        db.setTransactionSuccessful()
                        if (region.relId > 0) loadedOsmIds.add(region.relId)
                        loadedNames.add(region.name.trim().lowercase())
                    } finally {
                        db.endTransaction()
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
            ingestor.close()
        }
    }
}
