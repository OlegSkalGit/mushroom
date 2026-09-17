package com.olegskal.mushroom.network

import android.os.Handler
import android.os.Looper
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.map.MapRegion
import com.olegskal.mushroom.util.AppLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object OverpassSyncManager {

    private const val TAG = "OverpassSyncManager"

    private val MIRRORS = arrayOf(
        "https://overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val BUILTIN_COUNTRIES: List<MapCountry> = listOf(
        MapCountry("Україна", "UA"),
        MapCountry("Польща", "PL"),
        MapCountry("Німеччина", "DE"),
        MapCountry("Чехія", "CZ"),
        MapCountry("Словаччина", "SK"),
        MapCountry("Румунія", "RO"),
        MapCountry("Угорщина", "HU"),
        MapCountry("Молдова", "MD"),
        MapCountry("Австрія", "AT"),
        MapCountry("Італія", "IT"),
        MapCountry("Франція", "FR"),
        MapCountry("Іспанія", "ES"),
        MapCountry("Португалія", "PT"),
        MapCountry("Швейцарія", "CH"),
        MapCountry("Велика Британія", "GB"),
        MapCountry("Литва", "LT"),
        MapCountry("Латвія", "LV"),
        MapCountry("Естонія", "EE"),
        MapCountry("Фінляндія", "FI"),
        MapCountry("Швеція", "SE"),
        MapCountry("Норвегія", "NO"),
        MapCountry("Греція", "GR"),
        MapCountry("Болгарія", "BG"),
        MapCountry("Хорватія", "HR"),
        MapCountry("Словенія", "SI")
    )

    private val BUILTIN_UA_REGIONS: List<MapRegion> = listOf(
        MapRegion("ua_kyiv", "Київська область", 50.1, 51.5, 29.2, 32.1),
        MapRegion("ua_zhytomyr", "Житомирська область", 50.0, 51.7, 27.2, 29.8),
        MapRegion("ua_chernihiv", "Чернігівська область", 50.3, 52.4, 30.5, 33.5),
        MapRegion("ua_lviv", "Львівська область", 48.8, 50.6, 22.7, 25.4),
        MapRegion("ua_zakarpattia", "Закарпатська область", 47.9, 49.1, 22.1, 24.7),
        MapRegion("ua_ivano_frankivsk", "Івано-Франківська область", 47.7, 49.3, 23.6, 25.6),
        MapRegion("ua_volyn", "Волинська область", 50.3, 52.0, 23.6, 26.1),
        MapRegion("ua_rivne", "Рівненська область", 50.1, 52.0, 25.1, 27.5),
        MapRegion("ua_ternopil", "Тернопільська область", 48.5, 50.3, 24.7, 26.5),
        MapRegion("ua_khmelnytskyi", "Хмельницька область", 48.4, 50.6, 26.1, 27.9),
        MapRegion("ua_vinnytsia", "Вінницька область", 48.1, 49.9, 27.4, 30.1),
        MapRegion("ua_cherkasy", "Черкаська область", 48.5, 50.3, 30.9, 32.9),
        MapRegion("ua_poltava", "Полтавська область", 48.7, 50.5, 32.1, 35.5),
        MapRegion("ua_sumy", "Сумська область", 50.0, 52.4, 33.1, 35.7),
        MapRegion("ua_chernivtsi", "Чернівецька область", 47.7, 48.7, 24.9, 27.5),
        MapRegion("ua_dnipro", "Дніпропетровська область", 47.5, 49.2, 33.4, 36.9),
        MapRegion("ua_kharkiv", "Харківська область", 48.5, 50.5, 34.9, 38.0),
        MapRegion("ua_kirovohrad", "Кіровоградська область", 47.7, 49.2, 29.7, 33.6),
        MapRegion("ua_odesa", "Одеська область", 45.2, 48.2, 28.2, 31.3),
        MapRegion("ua_mykolaiv", "Миколаївська область", 46.4, 48.2, 30.7, 33.2),
        MapRegion("ua_kherson", "Херсонська область", 45.9, 47.6, 31.5, 35.0),
        MapRegion("ua_zaporizhia", "Запорізька область", 46.3, 48.2, 34.4, 37.3),
        MapRegion("ua_donetsk", "Донецька область", 46.8, 49.2, 36.6, 39.2),
        MapRegion("ua_luhansk", "Луганська область", 47.8, 50.1, 37.8, 40.2),
        MapRegion("ua_crimea", "АР Крим", 44.3, 46.2, 32.4, 36.7)
    )

    private val BUILTIN_PL_REGIONS: List<MapRegion> = listOf(
        MapRegion("pl_maz", "Мазовецьке (Mazowieckie)", 51.0, 53.5, 19.2, 23.1),
        MapRegion("pl_mlp", "Малопольське (Małopolskie)", 49.1, 50.5, 19.1, 21.4),
        MapRegion("pl_pkr", "Підкарпатське (Podkarpackie)", 49.0, 50.8, 21.1, 23.5),
        MapRegion("pl_sl", "Сілезьке (Śląskie)", 49.4, 51.1, 18.2, 19.9),
        MapRegion("pl_lub", "Люблінське (Lubelskie)", 50.2, 52.3, 21.6, 24.2),
        MapRegion("pl_pdl", "Підляське (Podlaskie)", 52.2, 54.4, 21.5, 23.9),
        MapRegion("pl_dls", "Нижньосілезьке (Dolnośląskie)", 50.1, 51.8, 14.8, 17.8),
        MapRegion("pl_wlp", "Великопольське (Wielkopolskie)", 51.4, 53.7, 15.8, 19.1),
        MapRegion("pl_zpm", "Західнопоморське (Zachodniopomorskie)", 52.6, 54.6, 14.1, 16.9),
        MapRegion("pl_pom", "Поморське (Pomorskie)", 53.5, 54.9, 16.7, 19.6),
        MapRegion("pl_wrm", "Вармінсько-Мазурське (Warmińsko-Mazurskie)", 53.1, 54.5, 19.2, 22.8)
    )

    fun fetchCountries(
        dbHelper: DatabaseHelper,
        onResult: (List<MapCountry>) -> Unit
    ) {
        var cached = dbHelper.getCachedCountries()
        if (cached.isEmpty()) {
            dbHelper.insertCountries(BUILTIN_COUNTRIES)
            cached = BUILTIN_COUNTRIES
        }
        onResult(cached)

        executor.execute {
            try {
                val query = """
                    [out:json][timeout:25];
                    relation["admin_level"="2"]["ISO3166-1"];
                    out tags;
                """.trimIndent()

                val jsonStr = executePostRequest(query)
                if (!jsonStr.isNullOrEmpty()) {
                    val parsed = parseCountriesJson(jsonStr)
                    if (parsed.isNotEmpty()) {
                        dbHelper.insertCountries(parsed)
                        AppLogger.log(TAG, "fetchCountries", true, "Fetched and cached ${parsed.size} countries from OSM.")
                        val all = dbHelper.getCachedCountries()
                        mainHandler.post { onResult(all) }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "fetchCountries", false, "Overpass error: ${e.message}")
            }
        }
    }

    fun fetchRegions(
        countryCode: String,
        dbHelper: DatabaseHelper,
        onResult: (List<MapRegion>) -> Unit
    ) {
        val cached = dbHelper.getCachedRegions(countryCode)
        if (cached.isNotEmpty()) {
            onResult(cached)
            return
        }

        val codeUpper = countryCode.uppercase().trim()
        if (codeUpper == "UA") {
            dbHelper.insertRegions("UA", BUILTIN_UA_REGIONS)
            onResult(BUILTIN_UA_REGIONS)
            return
        } else if (codeUpper == "PL") {
            dbHelper.insertRegions("PL", BUILTIN_PL_REGIONS)
            onResult(BUILTIN_PL_REGIONS)
            return
        }

        executor.execute {
            try {
                val query = """
                    [out:json][timeout:30];
                    area["ISO3166-1"="$codeUpper"]->.c;
                    (
                      relation["admin_level"="4"](area.c);
                    );
                    out tags bb;
                """.trimIndent()

                var jsonStr = executePostRequest(query)
                if (jsonStr.isNullOrEmpty()) {
                    val fallbackQuery = """
                        [out:json][timeout:30];
                        area["ISO3166-1:alpha2"="$codeUpper"]->.c;
                        (
                          relation["admin_level"="4"](area.c);
                        );
                        out tags bb;
                    """.trimIndent()
                    jsonStr = executePostRequest(fallbackQuery)
                }

                val regions = if (!jsonStr.isNullOrEmpty()) parseRegionsJson(jsonStr) else emptyList()
                if (regions.isNotEmpty()) {
                    dbHelper.insertRegions(codeUpper, regions)
                    AppLogger.log(TAG, "fetchRegions", true, "Fetched and cached ${regions.size} regions for $codeUpper from OSM.")
                    mainHandler.post { onResult(regions) }
                } else {
                    mainHandler.post { onResult(emptyList()) }
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "fetchRegions", false, "Error fetching regions for $codeUpper: ${e.message}")
                mainHandler.post { onResult(emptyList()) }
            }
        }
    }

    private fun executePostRequest(query: String): String? {
        val encodedBody = "data=" + URLEncoder.encode(query, "UTF-8")
        for (mirror in MIRRORS) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(mirror)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("User-Agent", "MushroomApp/1.0 (Android; Offline Forest Navigator)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { os ->
                    os.write(encodedBody.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                if (conn.responseCode == 200) {
                    return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "executePostRequest", false, "Mirror $mirror failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun parseCountriesJson(jsonStr: String): List<MapCountry> {
        val result = mutableMapOf<String, String>()
        try {
            val root = JSONObject(jsonStr)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val tags = el.optJSONObject("tags") ?: continue
                val code = tags.optString("ISO3166-1").ifEmpty { tags.optString("ISO3166-1:alpha2") }.uppercase().trim()
                if (code.length != 2) continue

                val nameUk = tags.optString("name:uk")
                val nameLocal = tags.optString("name")
                val nameEn = tags.optString("name:en")
                val name = nameUk.ifEmpty { nameLocal.ifEmpty { nameEn } }
                if (name.isNotEmpty()) {
                    val clean = name.replace(Regex("""\s*\(.*?\)\s*"""), "").trim()
                    if (!result.containsKey(code) || clean.length < result[code]!!.length) {
                        result[code] = clean
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "parseCountriesJson", false, "Error parsing countries: ${e.message}")
        }
        return result.map { MapCountry(it.value, it.key) }.sortedBy { it.name }
    }

    private fun parseRegionsJson(jsonStr: String): List<MapRegion> {
        val list = ArrayList<MapRegion>()
        try {
            val root = JSONObject(jsonStr)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val id = el.optLong("id")
                val bounds = el.optJSONObject("bounds") ?: continue
                val tags = el.optJSONObject("tags") ?: continue

                val nameUk = tags.optString("name:uk")
                val nameLocal = tags.optString("name")
                val nameEn = tags.optString("name:en")
                val name = nameUk.ifEmpty { nameLocal.ifEmpty { nameEn } }.trim()
                if (name.isEmpty()) continue

                val minLat = bounds.optDouble("minlat")
                val maxLat = bounds.optDouble("maxlat")
                val minLon = bounds.optDouble("minlon")
                val maxLon = bounds.optDouble("maxlon")
                if (minLat != 0.0 || maxLat != 0.0 || minLon != 0.0 || maxLon != 0.0) {
                    list.add(MapRegion("osm_$id", name, minLat, maxLat, minLon, maxLon))
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "parseRegionsJson", false, "Error parsing regions: ${e.message}")
        }
        return list.sortedBy { it.name }
    }
}
