package com.olegskal.mushroom.address

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import org.json.JSONObject
import java.io.File

object AddressDownloadManager {

    private const val TAG = "AddressDownloadManager"
    private val client = OverpassAddressClient()

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
        val expected = getCountryRegionCount(countryCode)
        val loaded = getLoadedRegionCount(context, countryCode)
        if (expected <= 1) {
            return getCountryHouseCount(context, countryCode) > 100L
        }
        return loaded >= expected
    }

    fun getCountryHouseCount(context: Context, countryCode: String): Long {
        val db = AddressDb.openDatabase(context, writable = false) ?: return 0L
        return try {
            val cursor = db.rawQuery("""
                SELECT count(*) FROM houses h
                JOIN streets s ON h.street_id = s.id
                JOIN admin_places ap ON s.country_id = ap.id
                WHERE ap.level = 2 AND ap.code = ?;
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
                WHERE s.region_id = (SELECT id FROM admin_places WHERE level = 4 AND name = ? LIMIT 1);
            """.trimIndent(), arrayOf(regionName))
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun fetchOverpassRegions(countryCode: String, isCancelled: () -> Boolean): List<Pair<Long, String>> {
        val q1 = """
            [out:csv(::id, "name:uk", "name", "name:en"; false; ";")][timeout:30];
            relation["admin_level"="4"]["ISO3166-2"~"^$countryCode"];
            out tags;
        """.trimIndent()
        var raw = client.query(q1, timeoutSec = 35, isCancelled = isCancelled)
        if (raw.isNullOrBlank()) {
            val qFallback = """
                [out:csv(::id, "name:uk", "name", "name:en"; false; ";")][timeout:45];
                area["ISO3166-1"="$countryCode"][admin_level=2]->.c;
                rel(area.c)["admin_level"="4"];
                out tags;
            """.trimIndent()
            raw = client.query(qFallback, timeoutSec = 50, isCancelled = isCancelled)
        }

        val regions = mutableListOf<Pair<Long, String>>()
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
                val name = nameUk.ifBlank { nameLocal.ifBlank { nameEn } }
                if (name.isBlank()) continue
                val nameLow = name.lowercase()
                if (nameLow in seenNames) continue
                seenNames.add(nameLow)
                regions.add(Pair(relId, name))
            }
        }
        if (regions.isNotEmpty()) {
            cachedRegionCounts[countryCode.uppercase()] = regions.size
        }
        if (regions.size < 25 && countryCode.equals("UA", ignoreCase = true)) {
            cachedRegionCounts["UA"] = UKRAINE_REGIONS.size
            return UKRAINE_REGIONS
        }
        return regions
    }

    val cachedRegionCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun getCountryRegionCount(countryCode: String): Int {
        val cached = cachedRegionCounts[countryCode.uppercase()]
        if (cached != null && cached > 0) return cached
        return getEstimatedRegionCount(countryCode)
    }

    fun getEstimatedRegionCount(countryCode: String): Int {
        return when (countryCode.uppercase()) {
            "UA" -> 25
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

    private val UKRAINE_REGIONS = listOf(
        Pair(71248L, "Автономна Республіка Крим"),
        Pair(71249L, "Вінницька область"),
        Pair(71244L, "Волинська область"),
        Pair(71250L, "Дніпропетровська область"),
        Pair(71251L, "Донецька область"),
        Pair(71245L, "Житомирська область"),
        Pair(71243L, "Закарпатська область"),
        Pair(71252L, "Запорізька область"),
        Pair(71242L, "Івано-Франківська область"),
        Pair(71246L, "Київська область"),
        Pair(71253L, "Кіровоградська область"),
        Pair(71254L, "Луганська область"),
        Pair(71241L, "Львівська область"),
        Pair(71255L, "Миколаївська область"),
        Pair(71256L, "Одеська область"),
        Pair(71257L, "Полтавська область"),
        Pair(71247L, "Рівненська область"),
        Pair(71258L, "Сумська область"),
        Pair(71240L, "Тернопільська область"),
        Pair(71259L, "Харківська область"),
        Pair(71260L, "Херсонська область"),
        Pair(71239L, "Хмельницька область"),
        Pair(71261L, "Черкаська область"),
        Pair(71238L, "Чернівецька область"),
        Pair(71262L, "Чернігівська область")
    )

    private fun fetchAssociatedStreets(
        areaClause: String,
        logFn: (String) -> Unit,
        isCancelled: () -> Boolean
    ): Map<Pair<String, Long>, String> {
        val q = """
            [out:json][timeout:60];
            $areaClause
            relation["type"="associatedStreet"](area.a);
            out body;
        """.trimIndent()
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
        areaClause: String,
        fallbackRegion: String,
        logFn: (String) -> Unit,
        isCancelled: () -> Boolean
    ): SpatialSettlementIndex {
        val q = """
            [out:csv(::id, ::type, ::lat, ::lon, "name:uk", "name", "name:en", "place", "admin_level"; false; ";")][timeout:60];
            $areaClause
            (
              node["place"~"city|town|village|hamlet|suburb|isolated_dwelling"](area.a);
              relation["place"~"city|town|village|municipality"](area.a);
              relation["boundary"="administrative"]["admin_level"~"7|8|9|10"](area.a);
            );
            out center;
        """.trimIndent()
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
        ingestor: AddressIngestor
    ): Int {
        if (!poiFile.exists() || poiFile.length() < 1024L) return 0
        var count = 0
        try {
            val poiDb = SQLiteDatabase.openDatabase(poiFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = poiDb.rawQuery("""
                SELECT i.lat, i.lon, p.data 
                FROM poi_data p 
                JOIN poi_index i ON p.id = i.id 
                WHERE p.data LIKE '%addr:%' OR p.data LIKE '%street%';
            """.trimIndent(), null)

            cursor.use {
                while (it.moveToNext()) {
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
                    }
                }
            }
            poiDb.close()
            ingestor.flush()
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
            var regions = fetchOverpassRegions(country.code, isCancelled)
            if (regions.isEmpty()) {
                regions = listOf(Pair(0L, country.nameUk))
            }

            val totalRegions = regions.size
            var pending = regions.mapIndexed { idx, pair -> Pair(idx + 1, pair) }
            val maxPasses = 5

            for (pass in 1..maxPasses) {
                if (pending.isEmpty() || isCancelled()) break

                val failed = mutableListOf<Pair<Int, Pair<Long, String>>>()

                for ((idx, rPair) in pending) {
                    if (isCancelled()) break
                    val (relId, regName) = rPair

                    val statusMsg = "${country.nameUk} ($totalRegions регіонів) • Регіон $idx/$totalRegions: $regName"
                    val existingCount = getRegionHouseCount(db, regName)
                    if (existingCount > 100L && pass == 1) {
                        onProgress((idx.toDouble() / totalRegions.toDouble()) * 0.85, statusMsg)
                        continue
                    }

                    val areaClause = if (relId > 0) {
                        "area(${3600000000L + relId})->.a;"
                    } else {
                        "area[\"ISO3166-1\"=\"${country.code}\"][admin_level=2]->.a;"
                    }

                    val logCallback: (String) -> Unit = { detail ->
                        onProgress((idx.toDouble() / totalRegions.toDouble()) * 0.85, "$statusMsg • $detail")
                    }

                    val assocStreets = fetchAssociatedStreets(areaClause, logCallback, isCancelled)
                    val settlementIndex = fetchRegionSettlements(areaClause, regName, logCallback, isCancelled)

                    val q = """
                        [out:csv(::id, ::type, ::lat, ::lon, "addr:city", "addr:town", "addr:village", "addr:hamlet", "addr:suburb", "addr:place", "addr:district", "addr:county", "addr:subdistrict", "addr:municipality", "addr:street", "contact:street", "addr:parentstreet", "is_in:street", "addr:housenumber", "addr:housename", "addr:conscriptionnumber", "is_in:city", "is_in:town", "is_in:village", "is_in:municipality"; false; ";")][timeout:300];
                        $areaClause
                        (
                          node["addr:housenumber"](area.a);
                          way["addr:housenumber"](area.a);
                          relation["addr:housenumber"](area.a);
                        );
                        out center;
                    """.trimIndent()

                    val tsvData = client.query(q, timeoutSec = 310, logFn = logCallback, isCancelled = isCancelled, maxCycles = 3)
                    if (tsvData.isNullOrBlank()) {
                        failed.add(Pair(idx, rPair))
                        continue
                    }

                    val regPlaceId = ingestor.getOrCreatePlace(ingestor.countryId, regName, level = 4, osmId = relId)

                    for (line in tsvData.lines()) {
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
                        val streetId = ingestor.getOrCreateStreet(cityPlaceId, street, regName, city, regPlaceId)

                        ingestor.addHouse(streetId, houseNumber, lat, lon)
                    }

                    ingestor.flush()
                    onProgress((idx.toDouble() / totalRegions.toDouble()) * 0.85, statusMsg)
                }

                pending = failed
                if (pending.isNotEmpty() && pass < maxPasses) {
                    try { Thread.sleep(3000L) } catch (_: Exception) {}
                }
            }

            // Extract from POI file
            val candidatePoiFiles = listOf(
                File(MushroomStorageManager.poiDir, country.poiFileName),
                File(File(context.filesDir, "poi"), country.poiFileName)
            )
            for (pFil in candidatePoiFiles) {
                if (pFil.exists() && pFil.length() > 1024L && !isCancelled()) {
                    onProgress(0.88, "${country.nameUk}: Експорт POI адрес (${pFil.name})")
                    extractAddressesFromPoiFile(pFil, country, ingestor)
                    break
                }
            }
            onProgress(0.95, "${country.nameUk}: Оптимізація індексу FTS")

            // Final FTS optimize, WAL checkpoint and VACUUM
            AddressDb.optimizeAndVacuum(db)
            onProgress(1.0, "${country.nameUk}: Готово")
            return true

        } catch (e: Exception) {
            AppLogger.log(TAG, "ingestCountryAddresses", false, "Ingest failed: ${e.message}")
            return false
        } finally {
            ingestor.close()
        }
    }
}
