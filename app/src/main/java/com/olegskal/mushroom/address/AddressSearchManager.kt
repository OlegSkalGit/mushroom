package com.olegskal.mushroom.address

import android.content.Context
import com.olegskal.mushroom.util.AppLogger
import java.util.regex.Pattern

data class AddressSearchResult(
    val countryCode: String,
    val regionName: String,
    val cityName: String,
    val streetName: String,
    val houseNumber: String,
    val lat: Double,
    val lon: Double
) {
    fun getFullTitle(): String {
        val parts = mutableListOf<String>()
        if (streetName.isNotBlank()) parts.add(streetName)
        if (houseNumber.isNotBlank()) parts.add(houseNumber)
        if (cityName.isNotBlank() && cityName != streetName) parts.add(cityName)
        if (regionName.isNotBlank() && regionName != cityName) parts.add(regionName)
        return parts.joinToString(", ")
    }

    fun getShortDisplay(): String {
        val streetPart = if (houseNumber.isNotBlank()) "$streetName, $houseNumber" else streetName
        val cityPart = if (cityName.isNotBlank()) cityName else regionName
        return if (streetPart.isNotBlank() && cityPart.isNotBlank()) "$streetPart ($cityPart)" else streetPart.ifBlank { cityPart }
    }
}

object AddressSearchManager {

    private const val TAG = "AddressSearchManager"
    private val houseNumberPattern = Pattern.compile("^\\d+[\\w\\dа-яА-ЯіІїЇєЄґҐ/-]*$")
    private val wordPattern = Pattern.compile("[\\w\\dа-яА-ЯіІїЇєЄґҐ]+")

    fun search(context: Context, rawQuery: String, limit: Int = 30): List<AddressSearchResult> {
        val db = AddressDb.openDatabase(context, writable = false) ?: return emptyList()
        val query = rawQuery.trim()
        if (query.isBlank()) return emptyList()

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
                        textTokens.add("\"$w\"*")
                    }
                }
            }
        }

        val ftsQuery = textTokens.joinToString(" ")
        if (ftsQuery.isBlank() && houseNum == null) return emptyList()
        if (ftsQuery.isBlank() && houseNum != null) return emptyList() // Need city/street context

        val results = mutableListOf<AddressSearchResult>()

        try {
            if (houseNum != null) {
                val hUp = houseNum.uppercase().trim()
                val hLow = houseNum.lowercase().trim()
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

                val varsList = hVars.toList()
                val inPlaceholders = varsList.joinToString(",") { "?" }

                val sql = """
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
                    LIMIT ?;
                """.trimIndent()

                val args = mutableListOf<String>()
                args.add(ftsQuery)
                args.addAll(varsList)
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
                        val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                        results.add(
                            AddressSearchResult(
                                countryCode = cCode,
                                regionName = rName,
                                cityName = cName,
                                streetName = sName,
                                houseNumber = hNum,
                                lat = latRaw / div,
                                lon = lonRaw / div
                            )
                        )
                    }
                }
            } else {
                val sql = """
                    SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon
                    FROM search_fts f
                    JOIN streets s ON s.id = f.rowid
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    LEFT JOIN admin_places country ON country.id = s.country_id
                    JOIN houses h ON h.street_id = s.id
                    WHERE search_fts MATCH ?
                    LIMIT ?;
                """.trimIndent()

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
                        val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                        results.add(
                            AddressSearchResult(
                                countryCode = cCode,
                                regionName = rName,
                                cityName = cName,
                                streetName = sName,
                                houseNumber = hNum,
                                lat = latRaw / div,
                                lon = lonRaw / div
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "search", false, "Address search query failed: ${e.message}")
            throw e
        }

        return results
    }
}
