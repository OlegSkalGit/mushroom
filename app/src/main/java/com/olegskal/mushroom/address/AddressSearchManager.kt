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
    val isPoiOnly: Boolean = false,
    val isSettlement: Boolean = false,
    val adminLevel: Int = 0
) {
    fun getFullTitle(): String {
        if (isSettlement) {
            val regSuffix = if (regionName.isNotBlank() && regionName != cityName) " ($regionName)" else ""
            return "🏙️ $cityName$regSuffix"
        }
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
        if (isSettlement) {
            return "🏙️ $cityName"
        }
        if (isPoiOnly) {
            val iconPrefix = if (!poiIcon.isNullOrBlank()) "$poiIcon " else "📍 "
            val cityPart = if (cityName.isNotBlank()) cityName else regionName
            return if (cityPart.isNotBlank()) "$iconPrefix${poiName ?: ""} ($cityPart)" else "$iconPrefix${poiName ?: ""}"
        }

        val streetPart = listOf(streetName, houseNumber).filter { it.isNotBlank() }.joinToString(", ")
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

    private val tagSynonyms: Map<String, List<String>> = mapOf(
        // Паливо vs зарядка
        "азс" to listOf("азс*", "заправк*", "fuel*", "gas*"),
        "заправка" to listOf("заправк*", "азс*", "fuel*", "gas*"),
        "заправки" to listOf("заправк*", "азс*", "fuel*", "gas*"),
        "автозаправка" to listOf("автозаправк*", "азс*", "fuel*"),
        "паливо" to listOf("палив*", "fuel*"),
        "бензин" to listOf("бензин*", "fuel*"),
        "дизель" to listOf("дизел*", "fuel*"),
        "газ" to listOf("газ*", "gas*", "fuel*"),
        "fuel" to listOf("fuel*", "азс*", "заправк*"),
        "gas" to listOf("gas*", "fuel*", "азс*"),

        "зарядка" to listOf("зарядк*", "зарядн*", "charging*"),
        "зарядки" to listOf("зарядк*", "зарядн*", "charging*"),
        "зарядна" to listOf("зарядк*", "зарядн*", "charging*"),
        "електрозарядка" to listOf("зарядк*", "зарядн*", "charging*"),
        "charging" to listOf("charging*", "зарядн*", "зарядк*"),

        // Медицина (розділено!)
        "лікарня" to listOf("лікарн*", "госпітал*", "больниц*", "hospital*"),
        "лікарні" to listOf("лікарн*", "госпітал*", "больниц*", "hospital*"),
        "госпіталь" to listOf("госпітал*", "лікарн*", "hospital*"),
        "больница" to listOf("больниц*", "лікарн*", "hospital*"),
        "поліклініка" to listOf("поліклінік*", "clinic*"),
        "клініка" to listOf("клінік*", "clinic*"),
        "стоматолог" to listOf("стоматолог*", "dentist*"),
        "стоматологія" to listOf("стоматолог*", "dentist*"),
        "ветеринар" to listOf("ветеринар*", "veterinary*"),
        "ветклініка" to listOf("ветеринар*", "veterinary*"),
        "аптека" to listOf("аптек*", "pharmacy*"),
        "аптеки" to listOf("аптек*", "pharmacy*"),
        "pharmacy" to listOf("pharmacy*", "аптек*"),
        "hospital" to listOf("hospital*", "лікарн*"),

        // Їжа та напої
        "кафе" to listOf("кафе*", "кав ярн*", "cafe*", "coffee*"),
        "кав'ярня" to listOf("кав ярн*", "кафе*", "cafe*", "coffee*"),
        "кавярня" to listOf("кав ярн*", "кафе*", "cafe*", "coffee*"),
        "ресторан" to listOf("ресторан*", "restaurant*"),
        "ресторани" to listOf("ресторан*", "restaurant*"),
        "піцерія" to listOf("піцері*", "pizza*"),
        "бар" to listOf("бар*", "bar*"),
        "паб" to listOf("паб*", "pub*"),
        "їдальня" to listOf("їдальн*", "canteen*"),

        // Магазини
        "магазин" to listOf("магазин*", "маркет*", "supermarket*", "shop*"),
        "магазини" to listOf("магазин*", "маркет*", "supermarket*", "shop*"),
        "супермаркет" to listOf("супермаркет*", "supermarket*"),
        "маркет" to listOf("маркет*", "supermarket*"),
        "продукти" to listOf("продукт*", "convenience*", "grocery*"),
        "пекарня" to listOf("пекарн*", "bakery*"),
        "м'ясо" to listOf("м яс*", "butcher*"),
        "одяг" to listOf("одяг*", "clothes*"),
        "взуття" to listOf("взутт*", "shoes*"),

        // Фінанси
        "банкомат" to listOf("банкомат*", "atm*"),
        "банкомати" to listOf("банкомат*", "atm*"),
        "atm" to listOf("atm*", "банкомат*"),
        "банк" to listOf("банк*", "bank*"),
        "банки" to listOf("банк*", "bank*"),
        "bank" to listOf("bank*", "банк*"),
        "обмін" to listOf("обмін*", "exchange*"),

        // Проживання
        "готель" to listOf("готел*", "отел*", "гостиниц*", "hotel*"),
        "готелі" to listOf("готел*", "отел*", "гостиниц*", "hotel*"),
        "хостел" to listOf("хостел*", "hostel*"),
        "мотель" to listOf("мотел*", "motel*"),

        // Вода та природа
        "джерело" to listOf("джерел*", "родник*", "spring*"),
        "джерела" to listOf("джерел*", "родник*", "spring*"),
        "криниця" to listOf("криниц*", "колодец*", "well*"),
        "колодязь" to listOf("колодяз*", "колодец*", "well*"),
        "вода" to listOf("вод*", "water*", "spring*"),
        "води" to listOf("вод*", "water*", "spring*"),
        "водоспад" to listOf("водоспад*", "waterfall*"),
        "гора" to listOf("гор*", "вершин*", "peak*"),
        "вершина" to listOf("вершин*", "гор*", "peak*"),
        "печера" to listOf("печер*", "cave*"),
        "пляж" to listOf("пляж*", "beach*"),
        "ліс" to listOf("ліс*", "wood*", "forest*"),
        "ліси" to listOf("ліс*", "wood*", "forest*"),

        // Послуги
        "паркінг" to listOf("паркінг*", "парковк*", "автостоянк*", "parking*"),
        "парковка" to listOf("парковк*", "паркінг*", "автостоянк*", "parking*"),
        "автостоянка" to listOf("автостоянк*", "парковк*", "паркінг*", "parking*"),
        "автомийка" to listOf("автомийк*", "мийк*", "wash*"),
        "мийка" to listOf("мийк*", "автомийк*", "wash*"),
        "сто" to listOf("сто*", "автомайстерн*", "repair*"),
        "шиномонтаж" to listOf("шиномонтаж*", "шин*", "вулканізац*", "tyres*"),
        "шини" to listOf("шин*", "шиномонтаж*", "tyres*"),
        "вулканізація" to listOf("вулканізац*", "шиномонтаж*", "tyres*"),
        "автомайстерня" to listOf("автомайстерн*", "сто*", "repair*"),
        "автосервіс" to listOf("автосервіс*", "сто*", "repair*"),
        "автозапчастини" to listOf("автозапчастин*", "запчастин*", "parts*"),
        "запчастини" to listOf("запчастин*", "автозапчастин*", "parts*"),
        "пошта" to listOf("пошт*", "post*"),
        "туалет" to listOf("туалет*", "вбиральн*", "toilets*"),

        // Транспорт
        "зупинка" to listOf("зупинк*", "stop*"),
        "вокзал" to listOf("вокзал*", "станці*", "station*"),
        "метро" to listOf("метро*", "subway*"),
        "пором" to listOf("пором*", "ferry*"),

        // Додаткові сервіси, розваги, природа
        "піцерія" to listOf("піцері*", "pizza*"),
        "пекарня" to listOf("пекарн*", "bakery*"),
        "замок" to listOf("замок*", "castle*", "fort*"),
        "фортеця" to listOf("фортец*", "fort*", "castle*"),
        "парк" to listOf("парк*"),
        "дитячий майданчик" to listOf("майданчик*", "playground*"),
        "майданчик" to listOf("майданчик*", "playground*", "pitch*"),
        "стадіон" to listOf("стадіон*", "stadium*"),
        "спортзал" to listOf("спортзал*", "фітнес*", "fitness*"),
        "фітнес" to listOf("фітнес*", "fitness*"),
        "басейн" to listOf("басейн*", "pool*"),
        "сауна" to listOf("саун*", "лазн*", "sauna*"),
        "лазня" to listOf("лазн*", "саун*", "sauna*"),
        "кіно" to listOf("кіно*", "cinema*"),
        "кінотеатр" to listOf("кінотеатр*", "cinema*"),
        "театр" to listOf("театр*", "theatre*"),
        "музей" to listOf("музей*", "museum*"),
        "книги" to listOf("книг*", "книжк*", "book*"),
        "квіти" to listOf("квіт*", "florist*"),
        "перукарня" to listOf("перукарн*", "hairdresser*"),
        "барбершоп" to listOf("барбер*", "hairdresser*"),
        "ательє" to listOf("ательє*", "кравець*", "tailor*"),
        "поліція" to listOf("поліці*", "police*"),
        "пожежна" to listOf("пожежн*", "fire*"),
        "швидка" to listOf("швидк*", "ambulance*"),
        "лісництво" to listOf("лісництв*", "ranger*"),
        "сад" to listOf("сад*", "garden*"),
        "гідрант" to listOf("гідрант*", "hydrant*"),
        "кладовище" to listOf("кладовищ*", "цвинтар*", "grave*"),
        "цвинтар" to listOf("цвинтар*", "кладовищ*", "grave*"),
        "трек" to listOf("трек*", "доріжк*", "track*"),
        "овочі" to listOf("овоч*", "фрукт*", "greengrocer*"),
        "фрукти" to listOf("фрукт*", "овоч*", "greengrocer*"),
        "радар" to listOf("радар*", "камер*", "speed*"),
        "камера" to listOf("камер*", "радар*", "speed*")
    )

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
        val addressTokens = mutableListOf<String>()
        val poiTokens = mutableListOf<String>()

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
                            low.replace(Regex("[аеиіоуєюяьй]+$"), "")
                        } else {
                            low
                        }

                        val syns = tagSynonyms[low]
                            ?: (if (stem.length >= 3) tagSynonyms[stem] else null)
                            ?: (if (low.length >= 3) {
                                val matching = tagSynonyms.entries.filter { entry ->
                                    entry.key.startsWith(low) || (stem.length >= 3 && entry.key.startsWith(stem))
                                }
                                if (matching.isNotEmpty()) {
                                    matching.flatMap { it.value }.distinct()
                                } else null
                            } else null)

                        val tok = if (stem.length >= 3) "$stem*" else "$low*"
                        addressTokens.add(tok)

                        if (syns != null && syns.isNotEmpty()) {
                            val orClause = "(" + syns.sorted().joinToString(" OR ") + ")"
                            poiTokens.add(orClause)
                        } else {
                            poiTokens.add(tok)
                        }
                    }
                }
            }
        }

        val addressFtsQuery = addressTokens.joinToString(" ")
        val streetFtsQuery = if (houseNum != null && addressTokens.isNotEmpty()) {
            (addressTokens + listOf("${houseNum.lowercase()}*")).joinToString(" ")
        } else {
            addressFtsQuery
        }
        val poiFtsQuery = poiTokens.joinToString(" ")

        if (addressFtsQuery.isBlank() && poiFtsQuery.isBlank() && tokens.isEmpty()) return emptyList()

        val blockLimit = 15
        val streetResults = mutableListOf<AddressSearchResult>()
        val addressResults = mutableListOf<AddressSearchResult>()
        val poiResults = mutableListOf<AddressSearchResult>()
        val settlementResults = mutableListOf<AddressSearchResult>()
        val hasPoi = AddressDb.hasPoiTables(db)
        val hasPoiFts = hasPoi && AddressDb.hasTable(db, "poi_fts")

        val streetOrderByClause = if (userLoc != null) {
            """
                ORDER BY
                  (CASE WHEN h.lat IS NULL OR h.lat = 0 THEN 1 ELSE 0 END) ASC,
                  ((h.lat * (CASE WHEN abs(h.lat) < 10000000 THEN 10 ELSE 1 END) - $uLat) *
                   (h.lat * (CASE WHEN abs(h.lat) < 10000000 THEN 10 ELSE 1 END) - $uLat) +
                   (h.lon * (CASE WHEN abs(h.lon) < 10000000 THEN 10 ELSE 1 END) - $uLon) *
                   (h.lon * (CASE WHEN abs(h.lon) < 10000000 THEN 10 ELSE 1 END) - $uLon)) ASC,
                  sn.name ASC
            """.trimIndent()
        } else {
            "ORDER BY sn.name ASC"
        }

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
            // 0. STREET SEARCH (h.number = '')
            if (streetFtsQuery.isNotBlank()) {
                val streetSql = """
                    SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.lat, h.lon
                    FROM search_fts f
                    JOIN streets s ON s.id = f.rowid
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    LEFT JOIN admin_places country ON country.id = s.country_id
                    JOIN houses h ON h.street_id = s.id
                    WHERE search_fts MATCH ?
                      AND h.number = ''
                      AND sn.name != ''
                    GROUP BY s.id
                    $streetOrderByClause
                    LIMIT ?;
                """.trimIndent()

                val streetCursor = db.rawQuery(streetSql, arrayOf(streetFtsQuery, blockLimit.toString()))
                streetCursor.use {
                    while (it.moveToNext()) {
                        val cCode = it.getString(0) ?: ""
                        val rName = it.getString(1) ?: ""
                        val cName = it.getString(2) ?: ""
                        val sName = it.getString(3) ?: ""
                        val latRaw = it.getInt(4)
                        val lonRaw = it.getInt(5)

                        val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                        val dLat = latRaw / div
                        val dLon = lonRaw / div

                        streetResults.add(
                            AddressSearchResult(
                                countryCode = cCode,
                                regionName = rName,
                                cityName = cName,
                                streetName = sName,
                                houseNumber = "",
                                lat = dLat,
                                lon = dLon,
                                isPoiOnly = false,
                                isSettlement = false
                            )
                        )
                    }
                }
            }

            // 1. ADDRESS SEARCH (houses with h.number != '' and optional linked POI info)
            if (addressFtsQuery.isNotBlank()) {
                if (houseNum != null) {

                    val sql = if (hasPoi) {
                        """
                            SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon,
                                   COALESCE(GROUP_CONCAT(DISTINCT pp.name), ''),
                                   COALESCE(MAX(pt.desc_local), MAX(pt.desc_en), ''),
                                   COALESCE(MAX(pt.group_category), ''),
                                   COALESCE(MAX(pt.tag), '')
                            FROM search_fts f
                            JOIN streets s ON s.id = f.rowid
                            JOIN street_names sn ON sn.id = s.name_id
                            JOIN admin_places city ON city.id = s.place_id
                            LEFT JOIN admin_places reg ON reg.id = s.region_id
                            LEFT JOIN admin_places country ON country.id = s.country_id
                            JOIN houses h ON h.street_id = s.id
                            LEFT JOIN poi_places pp ON pp.street_id = h.street_id AND (pp.house_number = h.number OR LOWER(pp.house_number) = LOWER(h.number))
                            LEFT JOIN poi_types pt ON pt.id = pp.category_id
                            WHERE search_fts MATCH ?
                              AND h.number != ''
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
                              AND h.number != ''
                              AND (h.number IN ($inPlaceholders) OR h.number LIKE ? OR h.number LIKE ?)
                            $orderByClause
                            LIMIT ?;
                        """.trimIndent()
                    }

                    val args = mutableListOf<String>()
                    args.add(addressFtsQuery)
                    args.addAll(hVarsList)
                    args.add("$hUp%")
                    args.add("$hLow%")
                    args.add(blockLimit.toString())

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
                        val pDescLocal = if (hasPoi) it.getString(8)?.takeIf { s -> s.isNotBlank() } else null
                        val pGroupCat = if (hasPoi) it.getString(9) ?: "" else ""
                        val pTag = if (hasPoi) it.getString(10) ?: "" else ""
                        val pCat = pDescLocal ?: pTag.takeIf { s -> s.isNotBlank() }
                        val pIcon = if (pTag.isNotBlank() || pGroupCat.isNotBlank()) getPoiIcon(pTag, pGroupCat) else null

                        val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                        val dLat = latRaw / div
                        val dLon = lonRaw / div

                        addressResults.add(
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
                    }
                }
            } else {
                val sql = if (hasPoi) {
                    """
                        SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon,
                               COALESCE(GROUP_CONCAT(DISTINCT pp.name), ''),
                               COALESCE(MAX(pt.desc_local), MAX(pt.desc_en), ''),
                               COALESCE(MAX(pt.group_category), ''),
                               COALESCE(MAX(pt.tag), '')
                        FROM search_fts f
                        JOIN streets s ON s.id = f.rowid
                        JOIN street_names sn ON sn.id = s.name_id
                        JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id
                        JOIN houses h ON h.street_id = s.id
                        LEFT JOIN poi_places pp ON pp.street_id = h.street_id AND (pp.house_number = h.number OR LOWER(pp.house_number) = LOWER(h.number))
                        LEFT JOIN poi_types pt ON pt.id = pp.category_id
                        WHERE search_fts MATCH ?
                          AND h.number != ''
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
                          AND h.number != ''
                        $orderByClause
                        LIMIT ?;
                    """.trimIndent()
                }

                    val cursor = db.rawQuery(sql, arrayOf(addressFtsQuery, blockLimit.toString()))
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
                            val pDescLocal = if (hasPoi) it.getString(8)?.takeIf { s -> s.isNotBlank() } else null
                            val pGroupCat = if (hasPoi) it.getString(9) ?: "" else ""
                            val pTag = if (hasPoi) it.getString(10) ?: "" else ""
                            val pCat = pDescLocal ?: pTag.takeIf { s -> s.isNotBlank() }
                            val pIcon = if (pTag.isNotBlank() || pGroupCat.isNotBlank()) getPoiIcon(pTag, pGroupCat) else null

                            val div = if (kotlin.math.abs(latRaw) < 10_000_000) 100_000.0 else 1_000_000.0
                            val dLat = latRaw / div
                            val dLon = lonRaw / div

                            addressResults.add(
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
                        }
                    }
                }
            }

            // 2. POI SEARCH (standalone or query by POI name/category, filtered by house number if present)
            if (hasPoiFts && poiFtsQuery.isNotBlank()) {
                try {
                    val houseFilterClause = if (houseNum != null) {
                        "AND (pp.house_number IN ($inPlaceholders) OR pp.name LIKE ? OR pp.name LIKE ?)"
                    } else ""

                    val poiSql = """
                        SELECT 
                            tp.name, 
                            COALESCE(pt.desc_local, pt.desc_en, ''),
                            COALESCE(pt.group_category, ''),
                            COALESCE(pt.tag, ''),
                            COALESCE(tp.house_number, ''), 
                            tp.lat, 
                            tp.lon,
                            COALESCE(sn.name, ''),
                            COALESCE(city.name, ''),
                            COALESCE(reg.name, ''),
                            COALESCE(country.code, tp.country_code, '')
                        FROM (
                            SELECT pp.id, pp.street_id, pp.category_id, pp.house_number, pp.lat, pp.lon, pp.country_code, pp.name
                            FROM poi_fts pf
                            JOIN poi_places pp ON pp.id = pf.rowid
                            WHERE poi_fts MATCH ?
                            $houseFilterClause
                            $poiOrderByClause
                            LIMIT ?
                        ) tp
                        LEFT JOIN poi_types pt ON pt.id = tp.category_id
                        LEFT JOIN streets s ON s.id = tp.street_id
                        LEFT JOIN street_names sn ON sn.id = s.name_id
                        LEFT JOIN admin_places city ON city.id = s.place_id
                        LEFT JOIN admin_places reg ON reg.id = s.region_id
                        LEFT JOIN admin_places country ON country.id = s.country_id;
                    """.trimIndent()

                    val poiArgs = mutableListOf<String>()
                    poiArgs.add(poiFtsQuery)
                    if (houseNum != null) {
                        poiArgs.addAll(hVarsList)
                        poiArgs.add("%$hUp%")
                        poiArgs.add("%$hLow%")
                    }
                    poiArgs.add((blockLimit * 2).toString())

                    val addressPoiKeys = addressResults.mapNotNull { addr ->
                        val name = addr.poiName?.trim()?.lowercase()
                        if (!name.isNullOrBlank()) {
                            val latK = kotlin.math.round(addr.lat * 10_000.0).toInt()
                            val lonK = kotlin.math.round(addr.lon * 10_000.0).toInt()
                            "$name|$latK|$lonK"
                        } else null
                    }.toSet()

                    val seenPoiCoords = mutableSetOf<Pair<Long, Long>>()

                    val poiCursor = db.rawQuery(poiSql, poiArgs.toTypedArray())
                    poiCursor.use {
                        while (it.moveToNext()) {
                            if (poiResults.size >= blockLimit) break
                            val pName = it.getString(0) ?: ""
                            val pDescLocal = it.getString(1) ?: ""
                            val pGroupCat = it.getString(2) ?: ""
                            val pTag = it.getString(3) ?: ""
                            val pHouse = it.getString(4) ?: ""
                            val pLat = it.getInt(5) / 1_000_000.0
                            val pLon = it.getInt(6) / 1_000_000.0
                            val pStreet = it.getString(7) ?: ""
                            val pCity = it.getString(8) ?: ""
                            val pReg = it.getString(9) ?: ""
                            val pCountry = it.getString(10) ?: ""

                            val pIcon = getPoiIcon(pTag, pGroupCat)
                            val pCat = pDescLocal.ifBlank { pTag }

                            val coordKey = Pair(kotlin.math.round(pLat * 100_000.0).toLong(), kotlin.math.round(pLon * 100_000.0).toLong())
                            if (seenPoiCoords.contains(coordKey)) {
                                continue
                            }
                            seenPoiCoords.add(coordKey)

                            val latK = kotlin.math.round(pLat * 10_000.0).toInt()
                            val lonK = kotlin.math.round(pLon * 10_000.0).toInt()
                            val poiKey = "${pName.trim().lowercase()}|$latK|$lonK"
                            if (pName.isNotBlank() && addressPoiKeys.contains(poiKey)) {
                                continue
                            }

                            poiResults.add(
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
                } catch (pe: Exception) {
                    AppLogger.log(TAG, "search", false, "POI FTS search warning: ${pe.message}")
                }
            }

            // 3. SETTLEMENT / REGION PREFIX SEARCH (admin_places levels 4, 6, 7, 8)
            // If the query contains a house number, it is strictly an address/POI search - skip settlements.
            if (houseNum == null && tokens.isNotEmpty()) {
                val seenPlaces = mutableSetOf<Long>()
                val searchWords = tokens.filter { !houseNumberPattern.matcher(it).matches() && it.length >= 2 }
                if (searchWords.isNotEmpty()) {
                    // Strict AND matching: all query words must match either the settlement name or its region name
                    val whereClauses = mutableListOf<String>()
                    val setArgs = mutableListOf<String>()

                    for (w in searchWords) {
                        val low = w.lowercase()
                        val cap = w.replaceFirstChar { it.uppercase() }
                        whereClauses.add("(ap.name LIKE ? OR ap.name LIKE ? OR ap.name LIKE ? OR reg.name LIKE ? OR reg.name LIKE ?)")
                        setArgs.add("$cap%")
                        setArgs.add("$low%")
                        setArgs.add("%$low%")
                        setArgs.add("$cap%")
                        setArgs.add("%$low%")
                    }
                    setArgs.add("50")

                    val whereSql = whereClauses.joinToString(" AND ")
                    val setSql = """
                        SELECT ap.id, ap.name, COALESCE(reg.name, ''), ap.level,
                               (CASE WHEN ap.level = 4 THEN (SELECT avg(h.lat) FROM streets s JOIN houses h ON h.street_id = s.id WHERE s.region_id = ap.id)
                                     ELSE (SELECT avg(h.lat) FROM streets s JOIN houses h ON h.street_id = s.id WHERE s.place_id = ap.id) END),
                               (CASE WHEN ap.level = 4 THEN (SELECT avg(h.lon) FROM streets s JOIN houses h ON h.street_id = s.id WHERE s.region_id = ap.id)
                                     ELSE (SELECT avg(h.lon) FROM streets s JOIN houses h ON h.street_id = s.id WHERE s.place_id = ap.id) END)
                        FROM admin_places ap
                        LEFT JOIN admin_places reg ON reg.id = ap.parent_id
                        WHERE ap.level IN (4, 6, 7, 8)
                          AND $whereSql
                        LIMIT ?;
                    """.trimIndent()
                    try {
                        val rawSettlements = mutableListOf<AddressSearchResult>()
                        val cSet = db.rawQuery(setSql, setArgs.toTypedArray())
                        cSet.use {
                            while (it.moveToNext()) {
                                val apId = it.getLong(0)
                                if (seenPlaces.add(apId)) {
                                    val apName = it.getString(1) ?: ""
                                    val rName = it.getString(2) ?: ""
                                    val level = it.getInt(3)
                                    val latAvg = if (!it.isNull(4)) it.getDouble(4) else 0.0
                                    val lonAvg = if (!it.isNull(5)) it.getDouble(5) else 0.0
                                    val div = if (kotlin.math.abs(latAvg) < 10_000_000.0) 100_000.0 else 1_000_000.0
                                    val cLat = if (latAvg != 0.0) latAvg / div else 0.0
                                    val cLon = if (lonAvg != 0.0) lonAvg / div else 0.0

                                    rawSettlements.add(
                                        AddressSearchResult(
                                            countryCode = "UA",
                                            regionName = rName,
                                            cityName = apName,
                                            streetName = "",
                                            houseNumber = "",
                                            lat = cLat,
                                            lon = cLon,
                                            isPoiOnly = false,
                                            isSettlement = true,
                                            adminLevel = level
                                        )
                                    )
                                }
                            }
                        }
                        val sortedSettlements = if (userLoc != null) {
                            val uLatDeg = userLoc.latitude
                            val uLonDeg = userLoc.longitude
                            val cosLat = Math.cos(Math.toRadians(uLatDeg))
                            rawSettlements.sortedWith(
                                compareBy<AddressSearchResult> { res ->
                                    if (res.lat == 0.0 && res.lon == 0.0) 1 else 0
                                }.thenBy { res ->
                                    val dLat = res.lat - uLatDeg
                                    val dLon = (res.lon - uLonDeg) * cosLat
                                    dLat * dLat + dLon * dLon
                                }
                            )
                        } else {
                            rawSettlements
                        }
                        settlementResults.addAll(sortedSettlements.take(blockLimit))
                    } catch (se: Exception) {
                        AppLogger.log(TAG, "search", false, "Settlement search warning: ${se.message}")
                    }
                }
            }

        } catch (e: Exception) {
            AppLogger.log(TAG, "search", false, "Address search query failed: ${e.message}")
            throw e
        }

        // Strict max 15 items per category without dynamic substitution across blocks
        return settlementResults.take(blockLimit) +
            streetResults.take(blockLimit) +
            addressResults.take(blockLimit) +
            poiResults.take(blockLimit)
    }

    fun formatDistance(distMeters: Float): String {
        return if (distMeters < 1000f) {
            "${kotlin.math.round(distMeters).toInt()} м"
        } else {
            String.format(java.util.Locale.US, "%.1f км", distMeters / 1000f)
        }
    }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    data class MarkerLocationInfo(
        val markerLat: Double,
        val markerLon: Double,
        val regionName: String,
        val districtName: String,
        val communityName: String,
        val settlementName: String,
        val nearestHouseAddress: String?, // Only if distance < 30m
        val nearestHouseDistanceMeters: Float?,
        val nearestStreets: List<Pair<AddressSearchResult, Float>>, // up to 15 items
        val nearestAddresses: List<Pair<AddressSearchResult, Float>>, // up to 15 items
        val nearestPois: List<Pair<AddressSearchResult, Float>> // up to 15 items
    )

    fun findMarkerLocationInfo(context: Context, lat: Double, lon: Double): MarkerLocationInfo? {
        val db = AddressDb.openDatabase(context, writable = false) ?: return null

        val uLatH = kotlin.math.round(lat * 100_000.0).toLong()
        val uLonH = kotlin.math.round(lon * 100_000.0).toLong()
        val uLatMicro = kotlin.math.round(lat * 1_000_000.0).toLong()
        val uLonMicro = kotlin.math.round(lon * 1_000_000.0).toLong()

        var regionName = ""
        var districtName = ""
        var communityName = ""
        var settlementName = ""
        var bestAdminDist = Float.MAX_VALUE
        var nearestHouseAddress: String? = null
        var nearestHouseDist: Float? = null

        val nearestStreets = mutableListOf<Pair<AddressSearchResult, Float>>()
        val nearestAddresses = mutableListOf<Pair<AddressSearchResult, Float>>()
        val nearestPois = mutableListOf<Pair<AddressSearchResult, Float>>()

        try {
            val houseRadii = listOf(5000L, 25000L, 100000L)

            // 1. NEAREST 15 STREETS (h.number = '' AND sn.name != '')
            for (radius in houseRadii) {
                if (nearestStreets.isNotEmpty()) break
                val streetSql = """
                    SELECT h.street_id, h.lat, h.lon, sn.name, city.name, COALESCE(reg.name, ''),
                           COALESCE(dist.name, ''), COALESCE(subdist.name, '')
                    FROM houses h
                    JOIN streets s ON s.id = h.street_id
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places subdist ON subdist.id = city.parent_id AND subdist.level = 7
                    LEFT JOIN admin_places dist ON (dist.id = city.parent_id AND dist.level = 6) OR (dist.id = subdist.parent_id AND dist.level = 6)
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    WHERE h.lat BETWEEN ? AND ? AND h.lon BETWEEN ? AND ?
                      AND h.number = ''
                      AND sn.name != ''
                    ORDER BY ((h.lat - $uLatH) * (h.lat - $uLatH) + (h.lon - $uLonH) * (h.lon - $uLonH)) ASC
                    LIMIT 15;
                """.trimIndent()

                val streetCursor = db.rawQuery(
                    streetSql,
                    arrayOf((uLatH - radius).toString(), (uLatH + radius).toString(), (uLonH - radius).toString(), (uLonH + radius).toString())
                )
                streetCursor.use {
                    while (it.moveToNext()) {
                        val hLatI = it.getInt(1)
                        val hLonI = it.getInt(2)
                        val sName = it.getString(3) ?: ""
                        val cName = it.getString(4) ?: ""
                        val rName = it.getString(5) ?: ""
                        val dName = it.getString(6) ?: ""
                        val subName = it.getString(7) ?: ""

                        val div = if (kotlin.math.abs(hLatI) < 10_000_000) 100_000.0 else 1_000_000.0
                        val dLat = hLatI / div
                        val dLon = hLonI / div
                        val distM = calculateDistanceMeters(lat, lon, dLat, dLon)

                        if (distM < bestAdminDist && cName.isNotBlank()) {
                            bestAdminDist = distM
                            regionName = rName
                            districtName = dName
                            communityName = subName
                            settlementName = cName
                        }

                        val res = AddressSearchResult(
                            countryCode = "UA",
                            regionName = rName,
                            cityName = cName,
                            streetName = sName,
                            houseNumber = "",
                            lat = dLat,
                            lon = dLon,
                            isPoiOnly = false
                        )
                        nearestStreets.add(Pair(res, distM))
                    }
                }
            }

            // 2. NEAREST 15 HOUSES (h.number != '')
            for (radius in houseRadii) {
                if (nearestAddresses.isNotEmpty()) break
                val houseSql = """
                    SELECT h.street_id, h.number, h.lat, h.lon, sn.name, city.name, COALESCE(reg.name, ''),
                           COALESCE(dist.name, ''), COALESCE(subdist.name, '')
                    FROM houses h
                    JOIN streets s ON s.id = h.street_id
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places subdist ON subdist.id = city.parent_id AND subdist.level = 7
                    LEFT JOIN admin_places dist ON (dist.id = city.parent_id AND dist.level = 6) OR (dist.id = subdist.parent_id AND dist.level = 6)
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    WHERE h.lat BETWEEN ? AND ? AND h.lon BETWEEN ? AND ?
                      AND h.number != ''
                    ORDER BY ((h.lat - $uLatH) * (h.lat - $uLatH) + (h.lon - $uLonH) * (h.lon - $uLonH)) ASC
                    LIMIT 15;
                """.trimIndent()

                val cursor = db.rawQuery(
                    houseSql,
                    arrayOf((uLatH - radius).toString(), (uLatH + radius).toString(), (uLonH - radius).toString(), (uLonH + radius).toString())
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val hNum = it.getString(1) ?: ""
                        val hLatI = it.getInt(2)
                        val hLonI = it.getInt(3)
                        val sName = it.getString(4) ?: ""
                        val cName = it.getString(5) ?: ""
                        val rName = it.getString(6) ?: ""
                        val dName = it.getString(7) ?: ""
                        val subName = it.getString(8) ?: ""

                        val div = if (kotlin.math.abs(hLatI) < 10_000_000) 100_000.0 else 1_000_000.0
                        val dLat = hLatI / div
                        val dLon = hLonI / div
                        val distM = calculateDistanceMeters(lat, lon, dLat, dLon)

                        if (distM < bestAdminDist && cName.isNotBlank()) {
                            bestAdminDist = distM
                            regionName = rName
                            districtName = dName
                            communityName = subName
                            settlementName = cName
                        }

                        if (nearestAddresses.isEmpty() && distM < 30.0f) {
                            nearestHouseAddress = if (sName.isNotBlank()) "$sName, $hNum" else hNum
                            nearestHouseDist = distM
                        }

                        val res = AddressSearchResult(
                            countryCode = "UA",
                            regionName = rName,
                            cityName = cName,
                            streetName = sName,
                            houseNumber = hNum,
                            lat = dLat,
                            lon = dLon,
                            isPoiOnly = false
                        )
                        nearestAddresses.add(Pair(res, distM))
                    }
                }
            }

            // Fallback for admin hierarchy if no houses or streets found in range:
            if (settlementName.isBlank()) {
                val setSql = """
                    SELECT ap.name, COALESCE(reg.name, '')
                    FROM admin_places ap
                    LEFT JOIN admin_places reg ON reg.id = ap.parent_id
                    WHERE ap.level = 8
                    ORDER BY ap.id ASC
                    LIMIT 1;
                """.trimIndent()
                try {
                    val cSet = db.rawQuery(setSql, null)
                    cSet.use {
                        if (it.moveToFirst()) {
                            settlementName = it.getString(0) ?: ""
                            regionName = it.getString(1) ?: ""
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. NEAREST 15 POIS
            val poiRadii = listOf(50000L, 250000L, 1000000L)
            for (radius in poiRadii) {
                if (nearestPois.isNotEmpty()) break
                val poiSql = """
                    SELECT pp.id, pp.name, COALESCE(pt.desc_local, pt.desc_en, pt.tag, ''),
                           COALESCE(pt.group_category, ''), COALESCE(pt.tag, ''),
                           pp.lat, pp.lon, COALESCE(sn.name, ''), COALESCE(city.name, ''), COALESCE(reg.name, '')
                    FROM poi_places pp
                    LEFT JOIN poi_types pt ON pt.id = pp.category_id
                    LEFT JOIN streets s ON s.id = pp.street_id
                    LEFT JOIN street_names sn ON sn.id = s.name_id
                    LEFT JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    WHERE pp.lat BETWEEN ? AND ? AND pp.lon BETWEEN ? AND ?
                    ORDER BY ((pp.lat - $uLatMicro) * (pp.lat - $uLatMicro) + (pp.lon - $uLonMicro) * (pp.lon - $uLonMicro)) ASC
                    LIMIT 15;
                """.trimIndent()

                val cPoi = db.rawQuery(
                    poiSql,
                    arrayOf((uLatMicro - radius).toString(), (uLatMicro + radius).toString(), (uLonMicro - radius).toString(), (uLonMicro + radius).toString())
                )
                cPoi.use {
                    while (it.moveToNext()) {
                        val pName = it.getString(1) ?: ""
                        val pDesc = it.getString(2) ?: ""
                        val pGroup = it.getString(3) ?: ""
                        val pTag = it.getString(4) ?: ""
                        val pLat = it.getInt(5) / 1_000_000.0
                        val pLon = it.getInt(6) / 1_000_000.0
                        val sName = it.getString(7) ?: ""
                        val cName = it.getString(8) ?: ""
                        val rName = it.getString(9) ?: ""
                        val pIcon = getPoiIcon(pTag, pGroup)
                        val pCat = pDesc.ifBlank { pTag }

                        val distM = calculateDistanceMeters(lat, lon, pLat, pLon)

                        val res = AddressSearchResult(
                            countryCode = "UA",
                            regionName = rName,
                            cityName = cName,
                            streetName = sName,
                            houseNumber = "",
                            lat = pLat,
                            lon = pLon,
                            poiName = pName,
                            poiCategory = pCat,
                            poiIcon = pIcon,
                            isPoiOnly = true
                        )
                        nearestPois.add(Pair(res, distM))
                    }
                }
            }

        } catch (e: Exception) {
            AppLogger.log(TAG, "findMarkerLocationInfo", false, "Error: ${e.message}")
        }

        return MarkerLocationInfo(
            markerLat = lat,
            markerLon = lon,
            regionName = regionName,
            districtName = districtName,
            communityName = communityName,
            settlementName = settlementName,
            nearestHouseAddress = nearestHouseAddress,
            nearestHouseDistanceMeters = nearestHouseDist,
            nearestStreets = nearestStreets,
            nearestAddresses = nearestAddresses,
            nearestPois = nearestPois
        )
    }

    fun getPoiIcon(tag: String, groupCat: String = ""): String {
        return when (tag) {
            "pharmacy" -> "💊"
            "hospital", "clinic" -> "🏥"
            "dentist" -> "🦷"
            "veterinary" -> "🐾"
            "charging_station" -> "⚡"
            "fuel" -> "⛽"
            "car_wash" -> "🚿"
            "car_repair", "car_parts", "tyres", "locksmith" -> "🔧"
            "bicycle", "bicycle_parking", "bicycle_rental" -> "🚲"
            "cafe" -> "☕"
            "restaurant", "fast_food", "food_court", "canteen", "pizzeria" -> "🍽️"
            "ice_cream" -> "🍦"
            "bar", "pub", "biergarten", "alcohol" -> "🍺"
            "supermarket", "convenience", "grocery", "mall", "department_store" -> "🛒"
            "bakery", "confectionery", "pastry" -> "🥐"
            "butcher", "seafood" -> "🥩"
            "clothes", "shoes", "tailor" -> "👗"
            "beauty", "hairdresser", "cosmetics" -> "✂️"
            "hotel", "hostel", "motel", "guest_house", "chalet", "alpine_hut" -> "🏨"
            "camp_site", "caravan_site", "picnic_site", "shelter" -> "🏕️"
            "bank" -> "🏦"
            "atm" -> "🏧"
            "bureau_de_change" -> "💱"
            "post_office", "post_box" -> "📮"
            "school", "university", "college", "music_school", "driving_school" -> "🏫"
            "kindergarten" -> "🧒"
            "library" -> "📚"
            "park" -> "🌳"
            "playground" -> "🛝"
            "pitch", "stadium", "sports_centre", "fitness_centre", "fitness_club" -> "⚽"
            "swimming_pool", "water_park" -> "🏊"
            "spring", "drinking_water", "water_well" -> "💧"
            "fountain" -> "⛲"
            "waterfall" -> "🌊"
            "viewpoint" -> "🔭"
            "peak", "saddle" -> "⛰️"
            "cave_entrance" -> "🕳️"
            "beach" -> "🏖️"
            "church", "place_of_worship", "monastery", "wayside_cross", "wayside_shrine" -> "⛪"
            "castle", "fort", "historic_ruins", "ruins" -> "🏰"
            "monument", "memorial" -> "🏛️"
            "museum", "gallery", "artwork" -> "🎨"
            "zoo", "aquarium" -> "🦁"
            "theme_park" -> "🎡"
            "cinema" -> "🎬"
            "theatre" -> "🎭"
            "nightclub" -> "🪩"
            "bus_stop", "bus_station" -> "🚌"
            "tram_stop" -> "🚋"
            "station", "halt" -> "🚆"
            "subway_entrance" -> "🚇"
            "police", "prison" -> "👮"
            "fire_station" -> "🚒"
            "ambulance_station" -> "🚑"
            "townhall", "courthouse", "government" -> "🏛️"
            "toilets" -> "🚻"
            "ranger_station" -> "🌲"
            "garden" -> "🌳"
            "fire_hydrant" -> "🧯"
            "track" -> "🏃"
            "grave_yard" -> "🪦"
            "greengrocer" -> "🍏"
            "rest_area" -> "🅿️"
            "water_point" -> "💧"
            "speed_camera" -> "📷"
            "petroleum_well" -> "🛢️"
            else -> PoiManager.DEFAULT_CATEGORIES.firstOrNull { it.id == groupCat }?.icon ?: "📍"
        }
    }
}
