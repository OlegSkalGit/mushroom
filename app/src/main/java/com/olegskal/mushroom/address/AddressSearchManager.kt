package com.olegskal.mushroom.address

import android.content.Context
import android.location.Location
import com.olegskal.mushroom.map.PoiManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.LocationUtils
import java.util.regex.Pattern

data class AddressSearchResult(
    val countryCode: String,
    val regionName: String,
    val cityName: String,
    val streetName: String,
    val houseNumber: String,
    val lat: Double,
    val lon: Double,
    val poiName: String? = null,
    val poiCategory: String? = null,
    val poiIcon: String? = null,
    val isPoiOnly: Boolean = false
) {
    fun getFullTitle(): String {
        if (isPoiOnly) {
            val iconPrefix = if (!poiIcon.isNullOrBlank()) "$poiIcon " else "📍 "
            val catSuffix = if (!poiCategory.isNullOrBlank()) " • $poiCategory" else ""
            val streetPart = if (streetName.isNotBlank()) {
                if (houseNumber.isNotBlank()) ", $streetName, $houseNumber" else ", $streetName"
            } else ""
            val cityPart = if (cityName.isNotBlank()) " ($cityName$streetPart)" else if (streetPart.isNotBlank()) " (${streetPart.removePrefix(", ")})" else ""
            return "$iconPrefix${poiName ?: ""}$catSuffix$cityPart"
        }

        val parts = mutableListOf<String>()
        if (streetName.isNotBlank()) parts.add(streetName)
        if (houseNumber.isNotBlank()) parts.add(houseNumber)
        if (cityName.isNotBlank() && cityName != streetName) parts.add(cityName)
        if (regionName.isNotBlank() && regionName != cityName) parts.add(regionName)
        val baseAddr = parts.joinToString(", ")

        return if (!poiName.isNullOrBlank()) {
            val iconPrefix = if (!poiIcon.isNullOrBlank()) "$poiIcon " else ""
            "$baseAddr • $iconPrefix$poiName"
        } else {
            baseAddr
        }
    }

    fun getShortDisplay(): String {
        if (isPoiOnly) {
            val iconPrefix = if (!poiIcon.isNullOrBlank()) "$poiIcon " else "📍 "
            val cityPart = if (cityName.isNotBlank()) cityName else regionName
            return if (cityPart.isNotBlank()) "$iconPrefix${poiName ?: ""} ($cityPart)" else "$iconPrefix${poiName ?: ""}"
        }

        val streetPart = if (houseNumber.isNotBlank()) "$streetName, $houseNumber" else streetName
        val cityPart = if (cityName.isNotBlank()) cityName else regionName
        val base = if (streetPart.isNotBlank() && cityPart.isNotBlank()) "$streetPart ($cityPart)" else streetPart.ifBlank { cityPart }
        return if (!poiName.isNullOrBlank()) {
            val iconPrefix = if (!poiIcon.isNullOrBlank()) "$poiIcon " else ""
            "$base • $iconPrefix$poiName"
        } else {
            base
        }
    }
}

object AddressSearchManager {

    private const val TAG = "AddressSearchManager"
    private val houseNumberPattern = Pattern.compile("^\\d+[\\w\\dа-яА-ЯіІїЇєЄґҐ/-]*$")
    private val wordPattern = Pattern.compile("[\\w\\dа-яА-ЯіІїЇєЄґҐ]+")

    fun search(
        context: Context,
        rawQuery: String,
        limit: Int = 30,
        userLocation: Location? = null
    ): List<AddressSearchResult> {
        val db = AddressDb.openDatabase(context, writable = false) ?: return emptyList()
        val query = rawQuery.trim()
        if (query.isBlank()) return emptyList()

        val userLoc = userLocation ?: LocationUtils.getUserLocation(context)
        val uLat = if (userLoc != null) kotlin.math.round(userLoc.latitude * 1_000_000.0).toLong() else 0L
        val uLon = if (userLoc != null) kotlin.math.round(userLoc.longitude * 1_000_000.0).toLong() else 0L

        val orderByClause = if (userLoc != null) {
            """
                ORDER BY
                  (CASE WHEN h.lat IS NULL OR h.lat = 0 THEN 1 ELSE 0 END) ASC,
                  ((h.lat * (CASE WHEN abs(h.lat) < 10000000 THEN 10 ELSE 1 END) - $uLat) *
                   (h.lat * (CASE WHEN abs(h.lat) < 10000000 THEN 10 ELSE 1 END) - $uLat) +
                   (h.lon * (CASE WHEN abs(h.lon) < 10000000 THEN 10 ELSE 1 END) - $uLon) *
                   (h.lon * (CASE WHEN abs(h.lon) < 10000000 THEN 10 ELSE 1 END) - $uLon)) ASC,
                  CAST(h.number AS INTEGER), h.number
            """.trimIndent()
        } else {
            "ORDER BY CAST(h.number AS INTEGER), h.number"
        }

        val poiOrderByClause = if (userLoc != null) {
            """
                ORDER BY
                  ((pp.lat - $uLat) * (pp.lat - $uLat) +
                   (pp.lon - $uLon) * (pp.lon - $uLon)) ASC,
                  pp.name ASC
            """.trimIndent()
        } else {
            "ORDER BY pp.name ASC"
        }

        val cleanQ = query.replace("\"", " ")
            .replace("'", " ")
            .replace("`", " ")
            .replace("’", " ")
            .replace(",", " ")
            .replace(".", " ")

        val tokens = cleanQ.split(" ").filter { it.isNotBlank() }
        var houseNum: String? = null
        val textTokens = mutableListOf<String>()

        for (t in tokens) {
            if (houseNumberPattern.matcher(t).matches()) {
                houseNum = t
            } else {
                val matcher = wordPattern.matcher(t)
                while (matcher.find()) {
                    val w = matcher.group()
                    if (w.isNotBlank()) {
                        val low = w.lowercase()
                        val stem = if (low.length >= 4) {
                            low.replace(Regex("[аеиіоуєюя]+$"), "")
                        } else {
                            low
                        }
                        if (stem.length >= 3) {
                            textTokens.add("$stem*")
                        } else {
                            textTokens.add("$low*")
                        }
                    }
                }
            }
        }

        val ftsQuery = textTokens.joinToString(" ")
        val isUk = AppPrefs.isUk(context)
        val catDefs = PoiManager.DEFAULT_CATEGORIES.associateBy { it.id }

        fun getCategoryTitle(catId: String?): String? {
            if (catId.isNullOrBlank()) return null
            val def = catDefs[catId] ?: return catId
            return if (isUk) def.titleUk else def.titleEn
        }

        fun getCategoryIcon(catId: String?): String {
            if (catId.isNullOrBlank()) return "📍"
            return catDefs[catId]?.icon ?: "📍"
        }

        if (ftsQuery.isBlank()) return emptyList()

        val results = mutableListOf<AddressSearchResult>()
        val hasPoi = AddressDb.hasPoiTables(db)
        val hasPoiFts = hasPoi && AddressDb.hasTable(db, "poi_fts")
        val seenCoords = mutableSetOf<Pair<Int, Int>>()

        val hVarsList: List<String>
        val inPlaceholders: String
        val hUp: String
        val hLow: String

        if (houseNum != null) {
            hUp = houseNum.uppercase().trim()
            hLow = houseNum.lowercase().trim()
            val hVars = mutableSetOf(
                hUp, hLow,
                hUp.replace(" ", ""), hLow.replace(" ", ""),
                hUp.replace("-", ""), hLow.replace("-", "")
            )

            val mNum = Pattern.compile("^([\\d/]+)[-\\s]?([^\\d\\s/]+)$").matcher(houseNum)
            if (mNum.find()) {
                val numP = mNum.group(1) ?: ""
                val letP = mNum.group(2) ?: ""
                for (let in listOf(letP.uppercase(), letP.lowercase())) {
                    hVars.add("$numP$let")
                    hVars.add("$numP-$let")
                    hVars.add("$numP $let")
                    hVars.add("$numP/$let")
                }
            }

            hVarsList = hVars.toList()
            inPlaceholders = hVarsList.joinToString(",") { "?" }
        } else {
            hUp = ""
            hLow = ""
            hVarsList = emptyList()
            inPlaceholders = ""
        }

        try {
            // 1. ADDRESS SEARCH (with optional linked POI info)
            if (houseNum != null) {

                val sql = if (hasPoi) {
                    """
                        SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon,
                               COALESCE(GROUP_CONCAT(DISTINCT pp.name), ''),
                               COALESCE(MAX(pp.category_id), '')
                        FROM search_fts f
                        JOIN streets s ON s.id = f.rowid
                        JOIN street_names sn ON sn.id = s.name_id
                        JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id
                        JOIN houses h ON h.street_id = s.id
                        LEFT JOIN poi_places pp ON pp.street_id = h.street_id AND (pp.house_number = h.number OR LOWER(pp.house_number) = LOWER(h.number))
                        WHERE search_fts MATCH ?
                          AND (h.number IN ($inPlaceholders) OR h.number LIKE ? OR h.number LIKE ?)
                        GROUP BY h.street_id, h.number
                        $orderByClause
                        LIMIT ?;
                    """.trimIndent()
                } else {
                    """
                        SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon
                        FROM search_fts f
                        JOIN streets s ON s.id = f.rowid
                        JOIN street_names sn ON sn.id = s.name_id
                        JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id
                        JOIN houses h ON h.street_id = s.id
                        WHERE search_fts MATCH ?
                          AND (h.number IN ($inPlaceholders) OR h.number LIKE ? OR h.number LIKE ?)
                        $orderByClause
                        LIMIT ?;
                    """.trimIndent()
                }

                val args = mutableListOf<String>()
                args.add(ftsQuery)
                args.addAll(hVarsList)
                args.add("$hUp%")
                args.add("$hLow%")
                args.add(limit.toString())

                val cursor = db.rawQuery(sql, args.toTypedArray())
                cursor.use {
                    while (it.moveToNext()) {
                        val cCode = it.getString(0) ?: ""
                        val rName = it.getString(1) ?: ""
                        val cName = it.getString(2) ?: ""
                        val sName = it.getString(3) ?: ""
                        val hNum = it.getString(4) ?: ""
                        val latRaw = it.getInt(5)
                        val lonRaw = it.getInt(6)
                        val pName = if (hasPoi) it.getString(7)?.takeIf { s -> s.isNotBlank() } else null
                        val pCatId = if (hasPoi) it.getString(8)?.takeIf { s -> s.isNotBlank() } else null
                        val pCat = getCategoryTitle(pCatId)
                        val pIcon = if (pCatId != null) getCategoryIcon(pCatId) else null

                        val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                        val dLat = latRaw / div
                        val dLon = lonRaw / div

                        results.add(
                            AddressSearchResult(
                                countryCode = cCode,
                                regionName = rName,
                                cityName = cName,
                                streetName = sName,
                                houseNumber = hNum,
                                lat = dLat,
                                lon = dLon,
                                poiName = pName,
                                poiCategory = pCat,
                                poiIcon = pIcon,
                                isPoiOnly = false
                            )
                        )
                        seenCoords.add(Pair(kotlin.math.round(dLat * 1000.0).toInt(), kotlin.math.round(dLon * 1000.0).toInt()))
                    }
                }
            } else {
                val sql = if (hasPoi) {
                    """
                        SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon,
                               COALESCE(GROUP_CONCAT(DISTINCT pp.name), ''),
                               COALESCE(MAX(pp.category_id), '')
                        FROM search_fts f
                        JOIN streets s ON s.id = f.rowid
                        JOIN street_names sn ON sn.id = s.name_id
                        JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id
                        JOIN houses h ON h.street_id = s.id
                        LEFT JOIN poi_places pp ON pp.street_id = h.street_id AND (pp.house_number = h.number OR LOWER(pp.house_number) = LOWER(h.number))
                        WHERE search_fts MATCH ?
                        GROUP BY h.street_id, h.number
                        $orderByClause
                        LIMIT ?;
                    """.trimIndent()
                } else {
                    """
                        SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon
                        FROM search_fts f
                        JOIN streets s ON s.id = f.rowid
                        JOIN street_names sn ON sn.id = s.name_id
                        JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id
                        JOIN houses h ON h.street_id = s.id
                        WHERE search_fts MATCH ?
                        $orderByClause
                        LIMIT ?;
                    """.trimIndent()
                }

                val cursor = db.rawQuery(sql, arrayOf(ftsQuery, limit.toString()))
                cursor.use {
                    while (it.moveToNext()) {
                        val cCode = it.getString(0) ?: ""
                        val rName = it.getString(1) ?: ""
                        val cName = it.getString(2) ?: ""
                        val sName = it.getString(3) ?: ""
                        val hNum = it.getString(4) ?: ""
                        val latRaw = it.getInt(5)
                        val lonRaw = it.getInt(6)
                        val pName = if (hasPoi) it.getString(7)?.takeIf { s -> s.isNotBlank() } else null
                        val pCatId = if (hasPoi) it.getString(8)?.takeIf { s -> s.isNotBlank() } else null
                        val pCat = getCategoryTitle(pCatId)
                        val pIcon = if (pCatId != null) getCategoryIcon(pCatId) else null

                        val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                        val dLat = latRaw / div
                        val dLon = lonRaw / div

                        results.add(
                            AddressSearchResult(
                                countryCode = cCode,
                                regionName = rName,
                                cityName = cName,
                                streetName = sName,
                                houseNumber = hNum,
                                lat = dLat,
                                lon = dLon,
                                poiName = pName,
                                poiCategory = pCat,
                                poiIcon = pIcon,
                                isPoiOnly = false
                            )
                        )
                        seenCoords.add(Pair(kotlin.math.round(dLat * 1000.0).toInt(), kotlin.math.round(dLon * 1000.0).toInt()))
                    }
                }
            }

            // 2. POI SEARCH (standalone or query by POI name/category, filtered by house number if present)
            if (hasPoiFts && results.size < limit && ftsQuery.isNotBlank()) {
                try {
                    val remainingLimit = limit - results.size
                    val houseFilterClause = if (houseNum != null) {
                        "AND pp.house_number IN ($inPlaceholders)"
                    } else ""

                    val poiSql = """
                        SELECT 
                            pp.name, 
                            pp.category_id, 
                            COALESCE(pp.house_number, ''), 
                            pp.lat, 
                            pp.lon,
                            COALESCE(sn.name, ''),
                            COALESCE(city.name, ''),
                            COALESCE(reg.name, ''),
                            COALESCE(country.code, pp.country_code, '')
                        FROM poi_fts pf
                        JOIN poi_places pp ON pp.id = pf.rowid
                        LEFT JOIN streets s ON s.id = pp.street_id
                        LEFT JOIN street_names sn ON sn.id = s.name_id
                        LEFT JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id
                        WHERE poi_fts MATCH ?
                        $houseFilterClause
                        $poiOrderByClause
                        LIMIT ?;
                    """.trimIndent()

                    val poiArgs = mutableListOf<String>()
                    poiArgs.add(ftsQuery)
                    if (houseNum != null) {
                        poiArgs.addAll(hVarsList)
                    }
                    poiArgs.add(remainingLimit.toString())

                    val poiCursor = db.rawQuery(poiSql, poiArgs.toTypedArray())
                    poiCursor.use {
                        while (it.moveToNext()) {
                            val pName = it.getString(0) ?: ""
                            val pCatId = it.getString(1) ?: ""
                            val pCat = getCategoryTitle(pCatId) ?: pCatId
                            val pIcon = getCategoryIcon(pCatId)
                            val pHouse = it.getString(2) ?: ""
                            val pLat = it.getInt(3) / 1_000_000.0
                            val pLon = it.getInt(4) / 1_000_000.0
                            val pStreet = it.getString(5) ?: ""
                            val pCity = it.getString(6) ?: ""
                            val pReg = it.getString(7) ?: ""
                            val pCountry = it.getString(8) ?: ""

                            val coordKey = Pair(kotlin.math.round(pLat * 1000.0).toInt(), kotlin.math.round(pLon * 1000.0).toInt())
                            if (!seenCoords.contains(coordKey)) {
                                seenCoords.add(coordKey)
                                results.add(
                                    AddressSearchResult(
                                        countryCode = pCountry,
                                        regionName = pReg,
                                        cityName = pCity,
                                        streetName = pStreet,
                                        houseNumber = pHouse,
                                        lat = pLat,
                                        lon = pLon,
                                        poiName = pName,
                                        poiCategory = pCat,
                                        poiIcon = pIcon,
                                        isPoiOnly = true
                                    )
                                )
                            }
                        }
                    }
                } catch (pe: Exception) {
                    AppLogger.log(TAG, "search", false, "POI FTS search warning: ${pe.message}")
                }
            }

        } catch (e: Exception) {
            AppLogger.log(TAG, "search", false, "Address search query failed: ${e.message}")
            throw e
        }

        return results
    }
}
