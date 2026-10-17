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
        "обмін" to listOf("обмін*", "bureau_de_change*"),

        // Проживання
        "готель" to listOf("готел*", "отел*", "гостиниц*", "hotel*"),
        "готелі" to listOf("готел*", "отел*", "гостиниц*", "hotel*"),
        "хостел" to listOf("хостел*", "hostel*"),
        "мотель" to listOf("мотел*", "motel*"),

        // Вода та природа
        "джерело" to listOf("джерел*", "родник*", "spring*"),
        "джерела" to listOf("джерел*", "родник*", "spring*"),
        "криниця" to listOf("криниц*", "колодец*", "water_well*"),
        "колодязь" to listOf("колодяз*", "колодец*", "water_well*"),
        "вода" to listOf("вод*", "drinking_water*"),
        "водоспад" to listOf("водоспад*", "waterfall*"),
        "гора" to listOf("гор*", "вершин*", "peak*"),
        "вершина" to listOf("вершин*", "гор*", "peak*"),
        "печера" to listOf("печер*", "cave*"),
        "пляж" to listOf("пляж*", "beach*"),

        // Послуги
        "паркінг" to listOf("паркінг*", "парковк*", "parking*"),
        "парковка" to listOf("парковк*", "паркінг*", "parking*"),
        "автомийка" to listOf("автомийк*", "мийк*", "car_wash*"),
        "мийка" to listOf("мийк*", "автомийк*", "car_wash*"),
        "сто" to listOf("сто*", "автомайстерн*", "car_repair*"),
        "пошта" to listOf("пошт*", "post_office*"),
        "туалет" to listOf("туалет*", "вбиральн*", "toilets*"),

        // Транспорт
        "зупинка" to listOf("зупинк*", "bus_stop*"),
        "вокзал" to listOf("вокзал*", "станці*", "station*"),
        "метро" to listOf("метро*", "subway*"),
        "пором" to listOf("пором*", "ferry*"),

        // Додаткові сервіси, розваги, природа
        "піцерія" to listOf("піцері*", "pizza*"),
        "пекарня" to listOf("пекарн*", "bakery*"),
        "пляж" to listOf("пляж*", "beach*"),
        "замок" to listOf("замок*", "castle*", "fort*"),
        "фортеця" to listOf("фортец*", "fort*", "castle*"),
        "парк" to listOf("парк*", "park*"),
        "дитячий майданчик" to listOf("майданчик*", "playground*"),
        "майданчик" to listOf("майданчик*", "playground*", "pitch*"),
        "стадіон" to listOf("стадіон*", "stadium*"),
        "спортзал" to listOf("спортзал*", "фітнес*", "fitness*"),
        "фітнес" to listOf("фітнес*", "fitness*"),
        "басейн" to listOf("басейн*", "swimming_pool*"),
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
        "пожежна" to listOf("пожежн*", "fire_station*"),
        "швидка" to listOf("швидк*", "ambulance*"),
        "лісництво" to listOf("лісництв*", "ranger_station*")
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
                poiTokens.add("$t*")
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

                        val syns = tagSynonyms[low] ?: (if (stem.length >= 3) tagSynonyms[stem] else null)

                        if (syns != null && syns.isNotEmpty()) {
                            val orClause = "(" + syns.sorted().joinToString(" OR ") + ")"
                            poiTokens.add(orClause)
                        } else {
                            val tok = if (stem.length >= 3) "$stem*" else "$low*"
                            addressTokens.add(tok)
                            poiTokens.add(tok)
                        }
                    }
                }
            }
        }

        val addressFtsQuery = addressTokens.joinToString(" ")
        val poiFtsQuery = poiTokens.joinToString(" ")
        val catDefs = PoiManager.DEFAULT_CATEGORIES.associateBy { it.id }

        fun getPoiIcon(tag: String, groupCat: String): String {
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
                else -> catDefs[groupCat]?.icon ?: "📍"
            }
        }

        if (addressFtsQuery.isBlank() && poiFtsQuery.isBlank()) return emptyList()

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
                    args.add(addressFtsQuery)
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
                        val pDescLocal = if (hasPoi) it.getString(8)?.takeIf { s -> s.isNotBlank() } else null
                        val pGroupCat = if (hasPoi) it.getString(9) ?: "" else ""
                        val pTag = if (hasPoi) it.getString(10) ?: "" else ""
                        val pCat = pDescLocal ?: pTag.takeIf { s -> s.isNotBlank() }
                        val pIcon = if (pTag.isNotBlank() || pGroupCat.isNotBlank()) getPoiIcon(pTag, pGroupCat) else null

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

                    val cursor = db.rawQuery(sql, arrayOf(addressFtsQuery, limit.toString()))
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
            }

            // 2. POI SEARCH (standalone or query by POI name/category, filtered by house number if present)
            if (hasPoiFts && results.size < limit && poiFtsQuery.isNotBlank()) {
                try {
                    val remainingLimit = limit - results.size
                    val houseFilterClause = if (houseNum != null) {
                        "AND (pp.house_number IS NULL OR pp.house_number IN ($inPlaceholders))"
                    } else ""

                    val poiSql = """
                        SELECT 
                            pp.name, 
                            COALESCE(pt.desc_local, pt.desc_en, ''),
                            COALESCE(pt.group_category, ''),
                            COALESCE(pt.tag, ''),
                            COALESCE(pp.house_number, ''), 
                            pp.lat, 
                            pp.lon,
                            COALESCE(sn.name, ''),
                            COALESCE(city.name, ''),
                            COALESCE(reg.name, ''),
                            COALESCE(country.code, pp.country_code, '')
                        FROM poi_fts pf
                        JOIN poi_places pp ON pp.id = pf.rowid
                        LEFT JOIN poi_types pt ON pt.id = pp.category_id
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
                    poiArgs.add(poiFtsQuery)
                    if (houseNum != null) {
                        poiArgs.addAll(hVarsList)
                    }
                    poiArgs.add(remainingLimit.toString())

                    val poiCursor = db.rawQuery(poiSql, poiArgs.toTypedArray())
                    poiCursor.use {
                        while (it.moveToNext()) {
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
