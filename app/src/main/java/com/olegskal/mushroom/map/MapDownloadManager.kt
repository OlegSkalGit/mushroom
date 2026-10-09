package com.olegskal.mushroom.map

import android.content.Context
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class MapCountry(
    val code: String,
    val name: String,
    val nameUk: String = name,
    val continent: String = "europe",
    val mapFileName: String,
    val poiFileName: String,
    val mapUrl: String,
    val poiUrl: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean {
        return lat in minLat..maxLat && lon in minLon..maxLon
    }

    fun getRd5Segments(): List<String> {
        val list = mutableListOf<String>()
        val latMin = (floor(minLat / 5.0) * 5.0).toInt()
        val latMax = (floor(maxLat / 5.0) * 5.0).toInt()
        val lonMin = (floor(minLon / 5.0) * 5.0).toInt()
        val lonMax = (floor(maxLon / 5.0) * 5.0).toInt()
        for (lat in latMin..latMax step 5) {
            val latStr = if (lat >= 0) "N$lat" else "S${abs(lat)}"
            for (lon in lonMin..lonMax step 5) {
                val lonStr = if (lon >= 0) "E$lon" else "W${abs(lon)}"
                list.add("${lonStr}_${latStr}.rd5")
            }
        }
        return list
    }
}

enum class CountryStatus {
    NOT_DOWNLOADED,
    INCOMPLETE,
    READY,
    NEEDS_UPDATE
}

data class DownloadProgress(
    val countryName: String,
    val currentFileName: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Int
)

object MapDownloadManager {

    private const val TAG = "MapDownloadManager"
    private const val USER_AGENT = "Mushroom/2.0 (Android; https://github.com/OlegSkalGit/mushroom)"
    private const val BROUTER_SEGMENTS_URL = "https://brouter.de/brouter/segments4"
    const val WORLD_MAP_FILE_NAME = "world.map"
    private const val WORLD_MAP_URL = "https://download.mapsforge.org/maps/v5/world/world.map"

    private val executor = Executors.newSingleThreadExecutor()
    private val isDownloading = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)

    var currentProgress: DownloadProgress? = null
        private set
    var onProgressUpdate: ((DownloadProgress) -> Unit)? = null
    var onDownloadFinished: ((success: Boolean, message: String) -> Unit)? = null

    private val updateStatusCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val remoteFileSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun getRemoteFileSize(key: String): Long? = remoteFileSizes[key]

    fun setRemoteFileSize(key: String, size: Long) {
        if (size > 0L) remoteFileSizes[key] = size
    }

    fun fetchRemoteFileSizeAsync(urlStr: String, key: String, onResult: (Long) -> Unit = {}) {
        val cached = remoteFileSizes[key] ?: remoteFileSizes[urlStr]
        if (cached != null && cached > 0L) {
            onResult(cached)
            return
        }
        Thread {
            var conn: HttpURLConnection? = null
            try {
                var currentUrl = urlStr
                var redirects = 0
                while (redirects < 5) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 10000
                        readTimeout = 10000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location != null) {
                            currentUrl = location
                            redirects++
                            continue
                        }
                    }
                    break
                }
                if (conn != null && conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val len = conn.contentLengthLong
                    if (len > 0L) {
                        remoteFileSizes[key] = len
                        remoteFileSizes[urlStr] = len
                        onResult(len)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun def(code: String, name: String, nameUk: String, continent: String, slug: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        MapCountry(code, name, nameUk, continent, "$slug.map", "$slug.poi", "https://download.mapsforge.org/maps/v5/$continent/$slug.map", "https://download.mapsforge.org/pois/$continent/$slug.poi", minLat, maxLat, minLon, maxLon)

    private fun defSub(code: String, name: String, nameUk: String, continent: String, subMapPath: String, subPoiPath: String, slug: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        MapCountry(code, name, nameUk, continent, "$slug.map", "$slug.poi", "https://download.mapsforge.org/maps/v5/$subMapPath/$slug.map", "https://download.mapsforge.org/pois/$subPoiPath/$slug.poi", minLat, maxLat, minLon, maxLon)

    val countries: List<MapCountry> = listOf(
        // Europe
        def("UA", "Ukraine", "Україна", "europe", "ukraine", 44.3, 52.4, 22.1, 40.2),
        def("PL", "Poland", "Польща", "europe", "poland", 49.0, 54.9, 14.1, 24.2),
        def("SK", "Slovakia", "Словаччина", "europe", "slovakia", 47.7, 49.7, 16.8, 22.6),
        def("RO", "Romania", "Румунія", "europe", "romania", 43.6, 48.3, 20.2, 29.8),
        def("HU", "Hungary", "Угорщина", "europe", "hungary", 45.7, 48.6, 16.1, 22.9),
        def("MD", "Moldova", "Молдова", "europe", "moldova", 45.4, 48.5, 26.6, 30.2),
        def("CZ", "Czech Republic", "Чехія", "europe", "czech-republic", 48.5, 51.1, 12.0, 18.9),
        def("DE", "Germany", "Німеччина", "europe", "germany", 47.2, 55.1, 5.8, 15.1),
        def("AT", "Austria", "Австрія", "europe", "austria", 46.3, 49.1, 9.5, 17.2),
        def("CH", "Switzerland", "Швейцарія", "europe", "switzerland", 45.8, 47.8, 5.9, 10.5),
        def("FR", "France", "Франція", "europe", "france", 41.3, 51.1, -5.2, 9.6),
        def("IT", "Italy", "Італія", "europe", "italy", 35.5, 47.1, 6.6, 18.6),
        def("ES", "Spain", "Іспанія", "europe", "spain", 36.0, 43.8, -9.3, 4.4),
        def("PT", "Portugal", "Португалія", "europe", "portugal", 36.9, 42.2, -9.6, -6.1),
        def("GB", "Great Britain", "Велика Британія", "europe", "great-britain", 49.9, 60.9, -8.7, 1.8),
        def("IE", "Ireland", "Ірландія", "europe", "ireland-and-northern-ireland", 51.4, 55.4, -10.7, -5.9),
        def("NL", "Netherlands", "Нідерланди", "europe", "netherlands", 50.7, 53.6, 3.3, 7.3),
        def("BE", "Belgium", "Бельгія", "europe", "belgium", 49.4, 51.6, 2.5, 6.5),
        def("LU", "Luxembourg", "Люксембург", "europe", "luxembourg", 49.4, 50.2, 5.7, 6.6),
        def("SE", "Sweden", "Швеція", "europe", "sweden", 55.3, 69.1, 11.0, 24.2),
        def("NO", "Norway", "Норвегія", "europe", "norway", 57.9, 71.2, 4.5, 31.1),
        def("FI", "Finland", "Фінляндія", "europe", "finland", 59.8, 70.1, 20.5, 31.6),
        def("DK", "Denmark", "Данія", "europe", "denmark", 54.5, 57.8, 8.0, 12.7),
        def("EE", "Estonia", "Естонія", "europe", "estonia", 57.5, 59.7, 21.7, 28.3),
        def("LV", "Latvia", "Латвія", "europe", "latvia", 55.6, 58.1, 20.9, 28.3),
        def("LT", "Lithuania", "Литва", "europe", "lithuania", 53.8, 56.5, 20.9, 26.9),
        def("HR", "Croatia", "Хорватія", "europe", "croatia", 42.3, 46.6, 13.4, 19.5),
        def("SI", "Slovenia", "Словенія", "europe", "slovenia", 45.4, 46.9, 13.3, 16.6),
        def("BG", "Bulgaria", "Болгарія", "europe", "bulgaria", 41.2, 44.3, 22.3, 28.7),
        def("GR", "Greece", "Греція", "europe", "greece", 34.8, 41.8, 19.3, 29.7),
        def("IS", "Iceland", "Ісландія", "europe", "iceland", 63.2, 66.6, -24.6, -13.4),
        def("AL", "Albania", "Албанія", "europe", "albania", 39.6, 42.7, 19.2, 21.1),
        def("AD", "Andorra", "Андорра", "europe", "andorra", 42.4, 42.7, 1.4, 1.8),
        def("BA", "Bosnia and Herzegovina", "Боснія і Герцеговина", "europe", "bosnia-herzegovina", 42.5, 45.3, 15.7, 19.7),
        def("BY", "Belarus", "Білорусь", "europe", "belarus", 51.2, 56.2, 23.1, 32.8),
        def("CY", "Cyprus", "Кіпр", "europe", "cyprus", 34.5, 35.7, 32.2, 34.7),
        def("FO", "Faroe Islands", "Фарерські острови", "europe", "faroe-islands", 61.3, 62.4, -7.8, -6.2),
        def("GE", "Georgia", "Грузія", "europe", "georgia", 41.0, 43.6, 39.9, 46.8),
        def("IM", "Isle of Man", "Острів Мен", "europe", "isle-of-man", 54.0, 54.4, -4.8, -4.3),
        def("XK", "Kosovo", "Косово", "europe", "kosovo", 41.8, 43.3, 19.9, 21.8),
        def("LI", "Liechtenstein", "Ліхтенштейн", "europe", "liechtenstein", 47.0, 47.3, 9.4, 9.7),
        def("MK", "North Macedonia", "Північна Македонія", "europe", "macedonia", 40.8, 42.4, 20.4, 23.1),
        def("MT", "Malta", "Мальта", "europe", "malta", 35.7, 36.1, 14.1, 14.6),
        def("MC", "Monaco", "Монако", "europe", "monaco", 43.7, 43.8, 7.4, 7.5),
        def("ME", "Montenegro", "Чорногорія", "europe", "montenegro", 41.8, 43.6, 18.4, 20.4),
        def("RS", "Serbia", "Сербія", "europe", "serbia", 42.2, 46.2, 18.8, 23.1),
        def("TR", "Turkey", "Туреччина", "europe", "turkey", 35.8, 42.2, 25.6, 44.9),

        // Russia (Federal Districts - strictly excluding Crimea, Crimea is part of Ukraine)
        defSub("RU-CEN", "Russia (Central District)", "Росія (Центральний округ)", "europe", "russia", "russia", "central-fed-district", 50.0, 60.0, 31.0, 48.0),
        defSub("RU-NW", "Russia (Northwestern District)", "Росія (Північно-західний округ)", "europe", "russia", "russia", "northwestern-fed-district", 58.0, 70.0, 27.0, 66.0),
        defSub("RU-VOL", "Russia (Volga District)", "Росія (Приволзький округ)", "europe", "russia", "russia", "volga-fed-district", 50.0, 61.0, 42.0, 60.0),
        defSub("RU-SOU", "Russia (Southern District)", "Росія (Південний округ)", "europe", "russia", "russia", "south-fed-district", 43.0, 51.0, 37.0, 50.0),
        defSub("RU-NC", "Russia (North Caucasus)", "Росія (Північний Кавказ)", "europe", "russia", "russia", "north-caucasus-fed-district", 41.0, 45.5, 41.0, 48.5),
        defSub("RU-URA", "Russia (Ural District)", "Росія (Уральський округ)", "asia", "russia", "russia", "ural-fed-district", 54.0, 73.0, 57.0, 86.0),
        defSub("RU-SIB", "Russia (Siberian District)", "Росія (Сибірський округ)", "asia", "russia", "russia", "siberian-fed-district", 50.0, 75.0, 75.0, 110.0),
        defSub("RU-FE1", "Russia (Far East Part 1)", "Росія (Далекий Схід ч.1)", "asia", "russia", "russia", "far-eastern-fed-district-1", 42.0, 70.0, 115.0, 145.0),
        defSub("RU-FE2", "Russia (Far East Part 2)", "Росія (Далекий Схід ч.2)", "asia", "russia", "russia", "far-eastern-fed-district-2", 50.0, 70.0, 145.0, 175.0),
        defSub("RU-KGD", "Russia (Kaliningrad)", "Росія (Калінінград)", "europe", "russia", "russia", "kaliningrad", 54.3, 55.3, 19.6, 22.9),

        // Asia
        def("AF", "Afghanistan", "Афганістан", "asia", "afghanistan", 29.3, 38.5, 60.4, 74.9),
        def("AM", "Armenia", "Вірменія", "asia", "armenia", 38.8, 41.3, 43.4, 46.7),
        def("AZ", "Azerbaijan", "Азербайджан", "asia", "azerbaijan", 38.3, 41.9, 44.7, 50.9),
        def("BD", "Bangladesh", "Бангладеш", "asia", "bangladesh", 20.5, 26.7, 88.0, 92.7),
        def("BT", "Bhutan", "Бутан", "asia", "bhutan", 26.7, 28.3, 88.7, 92.2),
        def("KH", "Cambodia", "Камбоджа", "asia", "cambodia", 9.9, 14.7, 102.3, 107.7),
        def("TL", "East Timor", "Східний Тимор", "asia", "east-timor", -9.6, -8.1, 124.0, 127.4),
        def("GC", "GCC States", "Країни Перської затоки", "asia", "gcc-states", 16.3, 32.2, 34.5, 60.0),
        def("IN", "India", "Індія", "asia", "india", 6.7, 35.5, 68.1, 97.4),
        def("ID", "Indonesia", "Індонезія", "asia", "indonesia", -11.0, 6.1, 95.0, 141.1),
        def("IR", "Iran", "Іран", "asia", "iran", 25.0, 39.8, 44.0, 63.4),
        def("IQ", "Iraq", "Ірак", "asia", "iraq", 29.0, 37.4, 38.8, 48.6),
        def("IL", "Israel and Palestine", "Ізраїль і Палестина", "asia", "israel-and-palestine", 29.4, 33.3, 34.2, 35.9),
        def("JP", "Japan", "Японія", "asia", "japan", 24.0, 45.6, 122.9, 154.0),
        def("JO", "Jordan", "Йорданія", "asia", "jordan", 29.1, 33.4, 34.9, 39.3),
        def("KZ", "Kazakhstan", "Казахстан", "asia", "kazakhstan", 40.5, 55.5, 46.5, 87.4),
        def("KG", "Kyrgyzstan", "Киргизстан", "asia", "kyrgyzstan", 39.1, 43.3, 69.2, 80.3),
        def("LA", "Laos", "Лаос", "asia", "laos", 13.9, 22.5, 100.0, 107.7),
        def("LB", "Lebanon", "Ліван", "asia", "lebanon", 33.0, 34.7, 35.1, 36.7),
        def("MY", "Malaysia, Singapore & Brunei", "Малайзія, Сінгапур, Бруней", "asia", "malaysia-singapore-brunei", 0.8, 7.4, 99.6, 119.3),
        def("MV", "Maldives", "Мальдіви", "asia", "maldives", -0.7, 7.2, 72.5, 73.8),
        def("MN", "Mongolia", "Монголія", "asia", "mongolia", 41.5, 52.2, 87.7, 120.0),
        def("MM", "Myanmar", "М'янма", "asia", "myanmar", 9.5, 28.6, 92.1, 101.2),
        def("NP", "Nepal", "Непал", "asia", "nepal", 26.3, 30.5, 80.0, 88.2),
        def("KP", "North Korea", "Північна Корея", "asia", "north-korea", 37.6, 43.0, 124.1, 130.7),
        def("PK", "Pakistan", "Пакистан", "asia", "pakistan", 23.6, 37.1, 60.8, 77.8),
        def("PH", "Philippines", "Філіппіни", "asia", "philippines", 4.5, 21.2, 116.8, 126.7),
        def("KR", "South Korea", "Південна Корея", "asia", "south-korea", 33.1, 38.6, 125.0, 129.6),
        def("LK", "Sri Lanka", "Шрі-Ланка", "asia", "sri-lanka", 5.8, 9.9, 79.6, 81.9),
        def("SY", "Syria", "Сирія", "asia", "syria", 32.3, 37.4, 35.6, 42.4),
        def("TW", "Taiwan", "Тайвань", "asia", "taiwan", 21.8, 25.4, 119.9, 122.1),
        def("TJ", "Tajikistan", "Таджикистан", "asia", "tajikistan", 36.6, 41.1, 67.3, 75.2),
        def("TH", "Thailand", "Таїланд", "asia", "thailand", 5.6, 20.5, 97.3, 105.7),
        def("TM", "Turkmenistan", "Туркменістан", "asia", "turkmenistan", 35.1, 42.8, 52.4, 66.7),
        def("UZ", "Uzbekistan", "Узбекистан", "asia", "uzbekistan", 37.1, 45.6, 55.9, 73.2),
        def("VN", "Vietnam", "В'єтнам", "asia", "vietnam", 8.5, 23.4, 102.1, 109.5),
        def("YE", "Yemen", "Ємен", "asia", "yemen", 12.1, 19.0, 42.5, 54.6),

        // China (Provinces)
        defSub("CN-AH", "China (Anhui)", "Китай (Аньхой)", "asia", "asia/china", "asia/china", "anhui", 29.4, 34.6, 114.9, 119.6),
        defSub("CN-BJ", "China (Beijing)", "Китай (Пекін)", "asia", "asia/china", "asia/china", "beijing", 39.4, 41.1, 115.4, 117.5),
        defSub("CN-CQ", "China (Chongqing)", "Китай (Чунцін)", "asia", "asia/china", "asia/china", "chongqing", 28.2, 32.2, 105.3, 110.2),
        defSub("CN-FJ", "China (Fujian)", "Китай (Фуцзянь)", "asia", "asia/china", "asia/china", "fujian", 23.5, 28.3, 115.8, 120.7),
        defSub("CN-GS", "China (Gansu)", "Китай (Ганьсу)", "asia", "asia/china", "asia/china", "gansu", 32.5, 42.8, 92.3, 108.7),
        defSub("CN-GD", "China (Guangdong)", "Китай (Гуандун)", "asia", "asia/china", "asia/china", "guangdong", 20.2, 25.5, 109.6, 117.3),
        defSub("CN-GX", "China (Guangxi)", "Китай (Гуансі)", "asia", "asia/china", "asia/china", "guangxi", 20.9, 26.4, 104.4, 112.1),
        defSub("CN-GZ", "China (Guizhou)", "Китай (Гуйчжоу)", "asia", "asia/china", "asia/china", "guizhou", 24.6, 29.2, 103.6, 109.6),
        defSub("CN-HI", "China (Hainan)", "Китай (Хайнань)", "asia", "asia/china", "asia/china", "hainan", 18.1, 20.2, 108.6, 111.1),
        defSub("CN-HE", "China (Hebei)", "Китай (Хебей)", "asia", "asia/china", "asia/china", "hebei", 36.0, 42.7, 113.1, 119.8),
        defSub("CN-HL", "China (Heilongjiang)", "Китай (Хейлунцзян)", "asia", "asia/china", "asia/china", "heilongjiang", 43.4, 53.6, 121.2, 135.1),
        defSub("CN-HA", "China (Henan)", "Китай (Хенань)", "asia", "asia/china", "asia/china", "henan", 31.4, 36.4, 110.3, 116.6),
        defSub("CN-HK", "China (Hong Kong)", "Китай (Гонконг)", "asia", "asia/china", "asia/china", "hong-kong", 22.1, 22.6, 113.8, 114.4),
        defSub("CN-HB", "China (Hubei)", "Китай (Хубей)", "asia", "asia/china", "asia/china", "hubei", 29.0, 33.3, 108.4, 116.1),
        defSub("CN-HN", "China (Hunan)", "Китай (Хунань)", "asia", "asia/china", "asia/china", "hunan", 24.6, 30.1, 108.8, 114.3),
        defSub("CN-NM", "China (Inner Mongolia)", "Китай (Внутрішня Монголія)", "asia", "asia/china", "asia/china", "inner-mongolia", 37.4, 53.4, 97.2, 126.1),
        defSub("CN-JS", "China (Jiangsu)", "Китай (Цзянсу)", "asia", "asia/china", "asia/china", "jiangsu", 30.8, 35.1, 116.4, 121.9),
        defSub("CN-JX", "China (Jiangxi)", "Китай (Цзянсі)", "asia", "asia/china", "asia/china", "jiangxi", 24.5, 30.1, 113.6, 118.5),
        defSub("CN-JL", "China (Jilin)", "Китай (Цзілінь)", "asia", "asia/china", "asia/china", "jilin", 40.9, 46.3, 121.6, 131.3),
        defSub("CN-LN", "China (Liaoning)", "Китай (Ляонін)", "asia", "asia/china", "asia/china", "liaoning", 38.7, 43.5, 118.8, 125.8),
        defSub("CN-MO", "China (Macau)", "Китай (Макао)", "asia", "asia/china", "asia/china", "macau", 22.1, 22.2, 113.5, 113.6),
        defSub("CN-NX", "China (Ningxia)", "Китай (Нінся)", "asia", "asia/china", "asia/china", "ningxia", 35.2, 39.4, 104.3, 107.7),
        defSub("CN-QH", "China (Qinghai)", "Китай (Цінхай)", "asia", "asia/china", "asia/china", "qinghai", 31.6, 39.3, 89.4, 103.1),
        defSub("CN-SN", "China (Shaanxi)", "Китай (Шеньсі)", "asia", "asia/china", "asia/china", "shaanxi", 31.7, 39.6, 105.5, 111.2),
        defSub("CN-SD", "China (Shandong)", "Китай (Шаньдун)", "asia", "asia/china", "asia/china", "shandong", 34.4, 38.4, 114.8, 122.7),
        defSub("CN-SH", "China (Shanghai)", "Китай (Шанхай)", "asia", "asia/china", "asia/china", "shanghai", 30.7, 31.9, 120.9, 122.2),
        defSub("CN-SX", "China (Shanxi)", "Китай (Шаньсі)", "asia", "asia/china", "asia/china", "shanxi", 34.6, 40.7, 110.2, 114.6),
        defSub("CN-SC", "China (Sichuan)", "Китай (Сичуань)", "asia", "asia/china", "asia/china", "sichuan", 26.0, 34.3, 97.4, 108.5),
        defSub("CN-TJ", "China (Tianjin)", "Китай (Тяньцзінь)", "asia", "asia/china", "asia/china", "tianjin", 38.6, 40.3, 116.7, 118.1),
        defSub("CN-XZ", "China (Tibet)", "Китай (Тибет)", "asia", "asia/china", "asia/china", "tibet", 26.9, 36.5, 78.4, 99.1),
        defSub("CN-XJ", "China (Xinjiang)", "Китай (Сіньцзян)", "asia", "asia/china", "asia/china", "xinjiang", 34.3, 49.2, 73.6, 96.4),
        defSub("CN-YN", "China (Yunnan)", "Китай (Юньнань)", "asia", "asia/china", "asia/china", "yunnan", 21.1, 29.3, 97.5, 106.2),
        defSub("CN-ZJ", "China (Zhejiang)", "Китай (Чжецзян)", "asia", "asia/china", "asia/china", "zhejiang", 27.0, 31.2, 118.0, 123.0),

        // North America & Central America
        // Canada (Provinces & Territories)
        defSub("CA-AB", "Canada (Alberta)", "Канада (Альберта)", "north-america", "north-america/canada", "north-america/canada", "alberta", 49.0, 60.0, -120.0, -110.0),
        defSub("CA-BC", "Canada (British Columbia)", "Канада (Британська Колумбія)", "north-america", "north-america/canada", "north-america/canada", "british-columbia", 48.3, 60.0, -139.0, -114.0),
        defSub("CA-MB", "Canada (Manitoba)", "Канада (Манітоба)", "north-america", "north-america/canada", "north-america/canada", "manitoba", 49.0, 60.0, -102.0, -89.0),
        defSub("CA-NB", "Canada (New Brunswick)", "Канада (Нью-Брансвік)", "north-america", "north-america/canada", "north-america/canada", "new-brunswick", 44.6, 48.1, -69.1, -63.8),
        defSub("CA-NL", "Canada (Newfoundland & Labrador)", "Канада (Ньюфаундленд і Лабрадор)", "north-america", "north-america/canada", "north-america/canada", "newfoundland-and-labrador", 46.6, 60.4, -67.8, -52.6),
        defSub("CA-NS", "Canada (Nova Scotia)", "Канада (Нова Шотландія)", "north-america", "north-america/canada", "north-america/canada", "nova-scotia", 43.4, 47.0, -66.4, -59.7),
        defSub("CA-NT", "Canada (Northwest Territories)", "Канада (Північно-західні території)", "north-america", "north-america/canada", "north-america/canada", "northwest-territories", 60.0, 78.0, -136.0, -102.0),
        defSub("CA-NU", "Canada (Nunavut)", "Канада (Нунавут)", "north-america", "north-america/canada", "north-america/canada", "nunavut", 55.0, 75.0, -105.0, -75.0),
        defSub("CA-ON", "Canada (Ontario)", "Канада (Онтаріо)", "north-america", "north-america/canada", "north-america/canada", "ontario", 41.7, 56.9, -95.2, -74.3),
        defSub("CA-PE", "Canada (Prince Edward Island)", "Канада (Острів Принца Едварда)", "north-america", "north-america/canada", "north-america/canada", "prince-edward-island", 45.9, 47.1, -64.5, -62.0),
        defSub("CA-QC", "Canada (Quebec)", "Канада (Квебек)", "north-america", "north-america/canada", "north-america/canada", "quebec", 45.0, 62.6, -79.8, -57.1),
        defSub("CA-SK", "Canada (Saskatchewan)", "Канада (Саскачеван)", "north-america", "north-america/canada", "north-america/canada", "saskatchewan", 49.0, 60.0, -110.0, -101.9),
        defSub("CA-YT", "Canada (Yukon)", "Канада (Юкон)", "north-america", "north-america/canada", "north-america/canada", "yukon", 60.0, 69.7, -141.0, -123.8),

        def("MX", "Mexico", "Мексика", "north-america", "mexico", 14.5, 32.8, -118.4, -86.7),
        def("GL", "Greenland", "Гренландія", "north-america", "greenland", 59.7, 83.7, -73.1, -11.3),
        def("US", "USA (Midwest)", "США (Середній Захід)", "north-america", "us-midwest", 36.0, 49.4, -104.1, -80.5),
        def("US-NE", "USA (Northeast)", "США (Північний Схід)", "north-america", "us-northeast", 38.7, 47.5, -80.6, -66.9),
        def("US-SO", "USA (South)", "США (Південь)", "north-america", "us-south", 24.5, 39.2, -106.7, -75.4),
        def("US-WE", "USA (West)", "США (Захід)", "north-america", "us-west", 31.3, 49.0, -124.8, -102.0),
        def("BS", "Bahamas", "Багами", "central-america", "bahamas", 20.9, 27.3, -79.3, -72.7),
        def("BZ", "Belize", "Беліз", "central-america", "belize", 15.8, 18.5, -89.3, -87.7),
        def("CR", "Costa Rica", "Коста-Рика", "central-america", "costa-rica", 8.0, 11.3, -86.0, -82.5),
        def("CU", "Cuba", "Куба", "central-america", "cuba", 19.8, 23.3, -85.0, -74.1),
        def("SV", "El Salvador", "Сальвадор", "central-america", "el-salvador", 13.1, 14.5, -90.2, -87.6),
        def("GT", "Guatemala", "Гватемала", "central-america", "guatemala", 13.7, 17.9, -92.3, -88.2),
        def("HT", "Haiti & Dominican Republic", "Гаїті та Домінікана", "central-america", "haiti-and-domrep", 17.5, 20.1, -74.5, -68.3),
        def("HN", "Honduras", "Гондурас", "central-america", "honduras", 12.9, 16.6, -89.4, -83.1),
        def("JM", "Jamaica", "Ямайка", "central-america", "jamaica", 17.7, 18.6, -78.4, -76.1),
        def("NI", "Nicaragua", "Нікарагуа", "central-america", "nicaragua", 10.7, 15.1, -87.7, -82.9),
        def("PA", "Panama", "Панама", "central-america", "panama", 7.2, 9.7, -83.1, -77.1),

        // South America
        def("AR", "Argentina", "Аргентина", "south-america", "argentina", -55.1, -21.7, -73.6, -53.6),
        def("BO", "Bolivia", "Болівія", "south-america", "bolivia", -23.0, -9.6, -69.7, -57.4),
        def("BR", "Brazil", "Бразилія", "south-america", "brazil", -33.8, 5.3, -74.0, -34.7),
        def("CL", "Chile", "Чилі", "south-america", "chile", -56.0, -17.5, -75.7, -66.8),
        def("CO", "Colombia", "Колумбія", "south-america", "colombia", -4.3, 12.5, -79.1, -66.8),
        def("EC", "Ecuador", "Еквадор", "south-america", "ecuador", -5.1, 1.5, -91.7, -75.1),
        def("GY", "Guyana", "Гайана", "south-america", "guyana", 1.1, 8.6, -61.5, -56.4),
        def("PY", "Paraguay", "Парагвай", "south-america", "paraguay", -27.7, -19.2, -62.7, -54.2),
        def("PE", "Peru", "Перу", "south-america", "peru", -18.4, 0.0, -81.4, -68.6),
        def("SR", "Suriname", "Суринам", "south-america", "suriname", 1.8, 6.1, -58.1, -53.9),
        def("UY", "Uruguay", "Уругвай", "south-america", "uruguay", -35.0, -30.0, -58.5, -53.0),
        def("VE", "Venezuela", "Венесуела", "south-america", "venezuela", 0.6, 12.3, -73.4, -59.8),

        // Australia & Oceania
        def("AU", "Australia", "Австралія", "australia-oceania", "australia", -43.7, -10.0, 113.1, 153.7),
        def("CK", "Cook Islands", "Острови Кука", "australia-oceania", "cook-islands", -22.0, -8.8, -166.0, -157.0),
        def("FJ", "Fiji", "Фіджі", "australia-oceania", "fiji-1", -19.3, -15.7, 176.8, 180.0),
        def("KI", "Kiribati", "Кірибаті", "australia-oceania", "kiribati-1", -5.0, 5.0, 169.0, 177.0),
        def("MH", "Marshall Islands", "Маршаллові острови", "australia-oceania", "marshall-islands", 4.5, 14.8, 160.7, 172.2),
        def("FM", "Micronesia", "Мікронезія", "australia-oceania", "micronesia", 1.0, 10.1, 137.3, 163.1),
        def("NR", "Nauru", "Науру", "australia-oceania", "nauru", -0.6, -0.5, 166.9, 167.0),
        def("NC", "New Caledonia", "Нова Каледонія", "australia-oceania", "new-caledonia", -22.8, -19.5, 163.5, 168.2),
        def("NZ", "New Zealand", "Нова Зеландія", "australia-oceania", "new-zealand-1", -47.3, -34.4, 166.4, 178.6),
        def("NU", "Niue", "Ніуе", "australia-oceania", "niue", -19.2, -18.9, -170.0, -169.7),
        def("PW", "Palau", "Палау", "australia-oceania", "palau", 2.9, 8.2, 131.1, 134.8),
        def("PG", "Papua New Guinea", "Папуа-Нова Гвінея", "australia-oceania", "papua-new-guinea", -11.7, -1.3, 140.8, 156.1),
        def("WS", "Samoa", "Самоа", "australia-oceania", "samoa", -14.1, -13.4, -172.9, -171.4),
        def("SB", "Solomon Islands", "Соломонові острови", "australia-oceania", "solomon-islands", -11.9, -6.5, 155.4, 162.5),
        def("TO", "Tonga", "Тонга", "australia-oceania", "tonga", -21.5, -15.5, -175.7, -173.8),
        def("TV", "Tuvalu", "Тувалу", "australia-oceania", "tuvalu-1", -10.8, -5.6, 176.0, 179.9),
        def("VU", "Vanuatu", "Вануату", "australia-oceania", "vanuatu", -20.3, -13.0, 166.5, 170.3),

        // Africa
        def("DZ", "Algeria", "Алжир", "africa", "algeria", 18.9, 37.1, -8.7, 12.0),
        def("AO", "Angola", "Ангола", "africa", "angola", -18.1, -4.3, 11.6, 24.1),
        def("BJ", "Benin", "Бенін", "africa", "benin", 6.2, 12.5, 0.7, 3.9),
        def("BW", "Botswana", "Ботсвана", "africa", "botswana", -26.9, -17.7, 19.9, 29.4),
        def("BF", "Burkina Faso", "Буркіна-Фасо", "africa", "burkina-faso", 9.3, 15.1, -5.6, 2.5),
        def("BI", "Burundi", "Бурунді", "africa", "burundi", -4.5, -2.3, 28.9, 30.9),
        def("CM", "Cameroon", "Камерун", "africa", "cameroon", 1.6, 13.1, 8.4, 16.2),
        def("CV", "Cape Verde", "Кабо-Верде", "africa", "cape-verde", 14.8, 17.2, -25.4, -22.6),
        def("CF", "Central African Republic", "ЦАР", "africa", "central-african-republic", 2.2, 11.1, 14.4, 27.5),
        def("TD", "Chad", "Чад", "africa", "chad", 7.4, 23.5, 13.4, 24.0),
        def("KM", "Comoros", "Комори", "africa", "comores", -12.4, -11.3, 43.2, 44.6),
        def("CG", "Republic of the Congo", "Конго (Браззавіль)", "africa", "congo-brazzaville", -5.1, 3.7, 11.1, 18.7),
        def("CD", "DR Congo", "ДР Конго", "africa", "congo-democratic-republic", -13.5, 5.4, 12.2, 31.4),
        def("DJ", "Djibouti", "Джибуті", "africa", "djibouti", 10.9, 12.8, 41.7, 43.5),
        def("EG", "Egypt", "Єгипет", "africa", "egypt", 21.9, 31.7, 24.6, 36.9),
        def("GQ", "Equatorial Guinea", "Екваторіальна Гвінея", "africa", "equatorial-guinea", 0.9, 3.8, 8.5, 11.4),
        def("ER", "Eritrea", "Еритрея", "africa", "eritrea", 12.3, 18.1, 36.4, 43.2),
        def("ET", "Ethiopia", "Ефіопія", "africa", "ethiopia", 3.3, 14.9, 32.9, 48.0),
        def("GA", "Gabon", "Габон", "africa", "gabon", -4.0, 2.4, 8.6, 14.6),
        def("GH", "Ghana", "Гана", "africa", "ghana", 4.7, 11.2, -3.3, 1.2),
        def("GW", "Guinea-Bissau", "Гвінея-Бісау", "africa", "guinea-bissau", 10.8, 12.7, -16.8, -13.6),
        def("GN", "Guinea", "Гвінея", "africa", "guinea", 7.1, 12.7, -15.1, -7.7),
        def("CI", "Ivory Coast", "Кот-д'Івуар", "africa", "ivory-coast", 4.3, 10.8, -8.6, -2.5),
        def("KE", "Kenya", "Кенія", "africa", "kenya", -4.7, 4.7, 33.9, 41.9),
        def("LS", "Lesotho", "Лесото", "africa", "lesotho", -30.7, -28.5, 27.0, 29.5),
        def("LR", "Liberia", "Ліберія", "africa", "liberia", 4.3, 8.6, -11.6, -7.3),
        def("LY", "Libya", "Лівія", "africa", "libya", 19.4, 33.2, 9.3, 25.2),
        def("MG", "Madagascar", "Мадагаскар", "africa", "madagascar", -25.7, -11.9, 43.1, 50.5),
        def("MW", "Malawi", "Малаві", "africa", "malawi", -17.2, -9.3, 32.6, 36.0),
        def("ML", "Mali", "Малі", "africa", "mali", 10.1, 25.1, -12.3, 4.3),
        def("MR", "Mauritania", "Мавританія", "africa", "mauritania", 14.7, 27.3, -17.1, -4.8),
        def("MU", "Mauritius", "Маврикій", "africa", "mauritius", -20.6, -19.9, 57.3, 57.9),
        def("MA", "Morocco", "Марокко", "africa", "morocco", 27.6, 35.9, -13.2, -0.9),
        def("MZ", "Mozambique", "Мозамбік", "africa", "mozambique", -26.9, -10.4, 30.2, 40.9),
        def("NA", "Namibia", "Намібія", "africa", "namibia", -29.0, -16.9, 11.7, 25.3),
        def("NE", "Niger", "Нігер", "africa", "niger", 11.6, 23.6, 0.1, 16.0),
        def("NG", "Nigeria", "Нігерія", "africa", "nigeria", 4.2, 13.9, 2.6, 14.7),
        def("RW", "Rwanda", "Руанда", "africa", "rwanda", -2.9, -1.0, 28.8, 30.9),
        def("ST", "Sao Tome & Principe", "Сан-Томе і Принсіпі", "africa", "sao-tome-and-principe", -0.1, 1.8, 6.4, 7.5),
        def("SN", "Senegal & Gambia", "Сенегал і Гамбія", "africa", "senegal-and-gambia", 12.3, 16.7, -17.6, -11.3),
        def("SC", "Seychelles", "Сейшели", "africa", "seychelles", -10.0, -4.5, 46.0, 56.0),
        def("SL", "Sierra Leone", "Сьєрра-Леоне", "africa", "sierra-leone", 6.9, 10.0, -13.4, -10.2),
        def("SO", "Somalia", "Сомалі", "africa", "somalia", -1.7, 12.0, 40.9, 51.5),
        def("ZA", "South Africa", "ПАР", "africa", "south-africa-and-lesotho", -34.9, -22.1, 16.4, 33.0),
        def("SS", "South Sudan", "Південний Судан", "africa", "south-sudan", 3.4, 12.3, 23.4, 36.0),
        def("SD", "Sudan", "Судан", "africa", "sudan", 9.3, 22.3, 21.8, 38.7),
        def("SZ", "Eswatini", "Есватіні", "africa", "swaziland", -27.4, -25.7, 30.7, 32.2),
        def("TZ", "Tanzania", "Танзанія", "africa", "tanzania", -11.8, -0.9, 29.2, 40.5),
        def("TG", "Togo", "Того", "africa", "togo", 6.1, 11.2, -0.2, 1.9),
        def("TN", "Tunisia", "Туніс", "africa", "tunisia", 30.2, 37.6, 7.4, 11.6),
        def("UG", "Uganda", "Уганда", "africa", "uganda", -1.5, 4.3, 29.5, 35.1),
        def("ZM", "Zambia", "Замбія", "africa", "zambia", -18.1, -8.2, 21.9, 33.8),
        def("ZW", "Zimbabwe", "Зімбабве", "africa", "zimbabwe", -22.5, -15.6, 25.2, 33.1)
    )

    fun findCountryForLocation(lat: Double, lon: Double): MapCountry? {
        return countries.firstOrNull { it.contains(lat, lon) }
    }

    fun detectCurrentCountry(context: Context): MapCountry {
        val loc = com.olegskal.mushroom.util.LocationUtils.getLastKnownLocationCascade(context)
        if (loc != null) {
            val found = findCountryForLocation(loc.latitude, loc.longitude)
            if (found != null) return found
        }
        val simOrLocale = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val simIso = tm?.simCountryIso?.uppercase(java.util.Locale.US)
            val netIso = tm?.networkCountryIso?.uppercase(java.util.Locale.US)
            val localeIso = java.util.Locale.getDefault().country.uppercase(java.util.Locale.US)
            listOfNotNull(simIso, netIso, localeIso).firstOrNull { it.isNotBlank() }
        } catch (_: Exception) {
            java.util.Locale.getDefault().country.uppercase(java.util.Locale.US)
        }
        if (!simOrLocale.isNullOrBlank()) {
            val byCode = countries.find { it.code.equals(simOrLocale, ignoreCase = true) }
            if (byCode != null) return byCode
        }
        return countries.find { it.code == "UA" } ?: countries.first()
    }

    fun downloadWorldMap(
        onProgress: ((DownloadProgress) -> Unit)? = null,
        onFinished: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        if (!isDownloading.compareAndSet(false, true)) return
        cancelFlag.set(false)
        onProgressUpdate = onProgress
        onDownloadFinished = onFinished

        executor.execute {
            var success = false
            var errorMsg = ""
            try {
                val worldMapFile = File(MushroomStorageManager.mapsDir, WORLD_MAP_FILE_NAME)
                val ok = downloadFileWithProgress(
                    WORLD_MAP_URL,
                    worldMapFile,
                    "World Overview",
                    WORLD_MAP_FILE_NAME,
                    1,
                    1
                )
                if (ok) {
                    success = true
                } else {
                    errorMsg = if (cancelFlag.get()) "Cancelled" else "Failed to download world.map"
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown error"
            } finally {
                if (success) {
                    updateStatusCache.remove("WORLD")
                    OsmTileEngine.reloadMaps()
                }
                isDownloading.set(false)
                currentProgress = null
                val cb = onDownloadFinished
                onProgressUpdate = null
                onDownloadFinished = null
                cb?.invoke(success, errorMsg)
            }
        }
    }

    fun isCurrentlyDownloading(): Boolean = isDownloading.get()

    fun cancelDownload() {
        cancelFlag.set(true)
    }

    fun isWorldMapReady(): Boolean {
        val f = File(MushroomStorageManager.mapsDir, WORLD_MAP_FILE_NAME)
        return f.exists() && f.length() > 1024L
    }

    fun checkRemoteFileUpdate(urlStr: String, file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return true
        var conn: HttpURLConnection? = null
        return try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null) {
                        currentUrl = location
                        redirects++
                        continue
                    }
                }
                break
            }
            if (conn != null && conn.responseCode == HttpURLConnection.HTTP_OK) {
                val serverLastMod = conn.lastModified
                val serverLen = conn.contentLengthLong
                if (serverLen > 0L) {
                    remoteFileSizes[file.name] = serverLen
                    remoteFileSizes[urlStr] = serverLen
                }
                val isNewer = serverLastMod > 0L && serverLastMod > (file.lastModified() + 86400_000L)
                val isSizeChanged = serverLen > 0L && abs(serverLen - file.length()) > 1024L * 1024L
                isNewer || isSizeChanged
            } else false
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun checkRemoteUpdate(country: MapCountry): Boolean {
        val mapFile = File(MushroomStorageManager.mapsDir, country.mapFileName)
        return checkRemoteFileUpdate(country.mapUrl, mapFile)
    }

    fun checkPoiUpdate(country: MapCountry): Boolean {
        val poiFile = File(MushroomStorageManager.poiDir, country.poiFileName)
        return checkRemoteFileUpdate(country.poiUrl, poiFile)
    }

    fun isCountryMapUpdateAvailable(country: MapCountry): Boolean {
        val cached = updateStatusCache["MAP_${country.code}"]
        return if (cached != null && System.currentTimeMillis() - cached.first < 3600_000L) {
            cached.second
        } else {
            false
        }
    }

    fun isCountryPoiUpdateAvailable(country: MapCountry): Boolean {
        val cached = updateStatusCache["POI_${country.code}"]
        return if (cached != null && System.currentTimeMillis() - cached.first < 3600_000L) {
            cached.second
        } else {
            false
        }
    }

    fun checkWorldMapUpdate(): Boolean {
        val worldFile = File(MushroomStorageManager.mapsDir, WORLD_MAP_FILE_NAME)
        return checkRemoteFileUpdate(WORLD_MAP_URL, worldFile)
    }

    fun isWorldMapUpdateAvailable(): Boolean {
        val cached = updateStatusCache["WORLD"]
        return if (cached != null && System.currentTimeMillis() - cached.first < 3600_000L) {
            cached.second
        } else {
            false
        }
    }

    fun checkWorldMapUpdatesAsync(onResult: (Boolean) -> Unit) {
        val cached = updateStatusCache["WORLD"]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < 3600_000L) {
            onResult(cached.second)
            return
        }
        Thread {
            val hasUpdate = checkWorldMapUpdate()
            updateStatusCache["WORLD"] = Pair(now, hasUpdate)
            onResult(hasUpdate)
        }.start()
    }

    fun checkCountryUpdatesAsync(country: MapCountry, onResult: (Boolean) -> Unit) {
        val cached = updateStatusCache[country.code]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < 3600_000L) {
            onResult(cached.second)
            return
        }
        Thread {
            val mapUpd = checkRemoteUpdate(country)
            val poiUpd = checkPoiUpdate(country)
            val hasUpdate = mapUpd || poiUpd
            updateStatusCache["MAP_${country.code}"] = Pair(now, mapUpd)
            updateStatusCache["POI_${country.code}"] = Pair(now, poiUpd)
            updateStatusCache[country.code] = Pair(now, hasUpdate)
            onResult(hasUpdate)
        }.start()
    }

    fun getCountryStatus(country: MapCountry): CountryStatus {
        val mapFile = File(MushroomStorageManager.mapsDir, country.mapFileName)
        val poiFile = File(MushroomStorageManager.poiDir, country.poiFileName)
        val segments = country.getRd5Segments()

        val hasMap = mapFile.exists() && mapFile.length() > 1024L
        val hasPoi = poiFile.exists() && poiFile.length() > 1024L

        var existingSegments = 0
        for (seg in segments) {
            val segFile = File(MushroomStorageManager.navigationDir, seg)
            if (segFile.exists() && segFile.length() > 1024L) {
                existingSegments++
            }
        }

        if (!hasMap && !hasPoi && existingSegments == 0) {
            return CountryStatus.NOT_DOWNLOADED
        }

        if (hasMap && hasPoi && (segments.isEmpty() || existingSegments > 0)) {
            val cachedUpdate = updateStatusCache[country.code]
            if (cachedUpdate != null && System.currentTimeMillis() - cachedUpdate.first < 3600_000L) {
                if (cachedUpdate.second) return CountryStatus.NEEDS_UPDATE
            }
            return CountryStatus.READY
        }

        return CountryStatus.INCOMPLETE
    }

    fun downloadCountry(
        country: MapCountry,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        onFinished: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        if (!isDownloading.compareAndSet(false, true)) return
        cancelFlag.set(false)
        onProgressUpdate = onProgress
        onDownloadFinished = onFinished

        executor.execute {
            var success = false
            var errorMsg = ""
            try {
                data class DownloadItem(
                    val url: String,
                    val destFile: File,
                    val name: String,
                    val countryDisplay: String,
                    val isWorldOverview: Boolean = false
                )
                val items = mutableListOf<DownloadItem>()

                // Автоматично завантажуємо або оновлюємо оглядову карту світу (world.map)
                val worldMapFile = File(MushroomStorageManager.mapsDir, WORLD_MAP_FILE_NAME)
                val needsWorldDownload = !worldMapFile.exists() ||
                        worldMapFile.length() < 1024L ||
                        (isWorldMapUpdateAvailable() || checkRemoteFileUpdate(WORLD_MAP_URL, worldMapFile))

                if (needsWorldDownload) {
                    items.add(
                        DownloadItem(
                            WORLD_MAP_URL,
                            worldMapFile,
                            WORLD_MAP_FILE_NAME,
                            "Світ (Оглядова карта)",
                            isWorldOverview = true
                        )
                    )
                }

                // Векторна карта країни (.map) — завантажуємо якщо відсутня або оновилась
                val mapFile = File(MushroomStorageManager.mapsDir, country.mapFileName)
                val needsMap = !mapFile.exists() ||
                        mapFile.length() < 1024L ||
                        (isCountryMapUpdateAvailable(country) || checkRemoteFileUpdate(country.mapUrl, mapFile))
                if (needsMap) {
                    items.add(DownloadItem(country.mapUrl, mapFile, country.mapFileName, country.name))
                }

                // Точки інтересу (.poi) — завантажуємо якщо відсутні або оновились
                val poiFile = File(MushroomStorageManager.poiDir, country.poiFileName)
                val needsPoi = !poiFile.exists() ||
                        poiFile.length() < 1024L ||
                        (isCountryPoiUpdateAvailable(country) || checkRemoteFileUpdate(country.poiUrl, poiFile))
                if (needsPoi) {
                    items.add(DownloadItem(country.poiUrl, poiFile, country.poiFileName, country.name))
                }

                // Сегменти навігації (.rd5) — завантажуємо лише відсутні або пошкоджені
                val segments = country.getRd5Segments()
                for (seg in segments) {
                    val segFile = File(MushroomStorageManager.navigationDir, seg)
                    val needsSeg = !segFile.exists() || segFile.length() < 1024L
                    if (needsSeg) {
                        items.add(DownloadItem("$BROUTER_SEGMENTS_URL/$seg", segFile, seg, country.name))
                    }
                }

                if (items.isEmpty()) {
                    AppLogger.log(TAG, "downloadCountry", true, "Всі файли для ${country.name} вже актуальні")
                    success = true
                    return@execute
                }

                val totalFiles = items.size

                for (idx in items.indices) {
                    if (cancelFlag.get()) {
                        errorMsg = "Скасування користувачем"
                        break
                    }
                    val item = items[idx]
                    val ok = downloadFileWithProgress(
                        item.url,
                        item.destFile,
                        item.countryDisplay,
                        item.name,
                        idx + 1,
                        totalFiles
                    )
                    if (!ok && !cancelFlag.get()) {
                        if (item.isWorldOverview) {
                            AppLogger.log(TAG, "downloadCountry", false, "Не вдалося завантажити world.map, продовжуємо з картою країни")
                            continue
                        }
                        if (item.name.endsWith(".rd5")) {
                            AppLogger.log(TAG, "downloadCountry", true, "Сегмент ${item.name} відсутній на сервері (море/пустка), пропускаємо")
                            continue
                        }
                        errorMsg = "Помилка завантаження: ${item.name}"
                        break
                    }
                }

                success = !cancelFlag.get() && errorMsg.isEmpty()
            } catch (e: Exception) {
                AppLogger.log(TAG, "downloadCountry", false, "Error: ${e.message}")
                errorMsg = e.message ?: "Невідома помилка"
            } finally {
                if (success) {
                    updateStatusCache.remove(country.code)
                    updateStatusCache.remove("MAP_${country.code}")
                    updateStatusCache.remove("POI_${country.code}")
                    updateStatusCache.remove("WORLD")
                    OsmTileEngine.reloadMaps()
                    PoiManager.refreshPoiFiles()
                }
                isDownloading.set(false)
                currentProgress = null
                onDownloadFinished?.invoke(success, errorMsg)
            }
        }
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        destFile: File,
        countryName: String,
        fileName: String,
        fileIndex: Int,
        totalFiles: Int
    ): Boolean {
        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        val tmpFile = File(destFile.parentFile, "${destFile.name}.${System.currentTimeMillis()}.tmp")

        return try {
            var redirectUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                val url = URL(redirectUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null) {
                        redirectUrl = location
                        redirects++
                        continue
                    }
                }
                break
            }

            if (conn == null || conn.responseCode != HttpURLConnection.HTTP_OK) {
                AppLogger.log(TAG, "downloadFile", false, "HTTP ${conn?.responseCode} for $urlStr")
                return false
            }

            val totalBytes = conn.contentLengthLong
            var bytesRead = 0L

            destFile.parentFile?.mkdirs()
            inputStream = conn.inputStream
            FileOutputStream(tmpFile).use { out ->
                val buffer = ByteArray(16384)
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    if (cancelFlag.get()) {
                        tmpFile.delete()
                        return false
                    }
                    out.write(buffer, 0, len)
                    bytesRead += len
                    val pct = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
                    val p = DownloadProgress(countryName, fileName, fileIndex, totalFiles, bytesRead, totalBytes, pct)
                    currentProgress = p
                    onProgressUpdate?.invoke(p)
                }
            }

            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (destFile.exists()) destFile.delete()
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                val serverLastMod = conn.lastModified
                if (serverLastMod > 0L) {
                    try { destFile.setLastModified(serverLastMod) } catch (_: Exception) {}
                }
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "downloadFile", false, "Exception downloading $fileName: ${e.message}")
            tmpFile.delete()
            false
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
