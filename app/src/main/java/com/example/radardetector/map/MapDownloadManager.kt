package com.example.radardetector.map

import com.example.radardetector.storage.MushroomStorageManager
import com.example.radardetector.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class MapRegion(
    val id: String,
    val name: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class MapCountry(
    val name: String,
    val code: String,
    val regions: List<MapRegion>
)

object MapDownloadManager {

    private const val TAG = "MapDownloadManager"
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val isDownloading = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)

    val countries: List<MapCountry> = listOf(
        MapCountry(
            name = "Україна",
            code = "UA",
            regions = listOf(
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
        ),
        MapCountry(
            name = "Польща",
            code = "PL",
            regions = listOf(
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
        )
    )

    fun calculateTileCount(regions: List<MapRegion>, minZoom: Int = 10, maxZoom: Int = 13): Int {
        var total = 0
        for (region in regions) {
            for (z in minZoom..maxZoom) {
                val p1 = OsmTileEngine.latLonToTile(region.maxLat, region.minLon, z)
                val p2 = OsmTileEngine.latLonToTile(region.minLat, region.maxLon, z)
                val minX = minOf(p1.first, p2.first)
                val maxX = maxOf(p1.first, p2.first)
                val minY = minOf(p1.second, p2.second)
                val maxY = maxOf(p1.second, p2.second)
                total += (maxX - minX + 1) * (maxY - minY + 1)
            }
        }
        return total
    }

    fun isCurrentlyDownloading(): Boolean = isDownloading.get()

    fun cancelDownload() {
        cancelFlag.set(true)
    }

    fun downloadRegions(
        regions: List<MapRegion>,
        minZoom: Int = 10,
        maxZoom: Int = 13,
        onProgress: (current: Int, total: Int, currentRegion: String) -> Unit,
        onFinished: (successCount: Int, skippedCount: Int, failCount: Int) -> Unit
    ) {
        if (!isDownloading.compareAndSet(false, true)) return
        cancelFlag.set(false)

        downloadExecutor.execute {
            val totalTiles = calculateTileCount(regions, minZoom, maxZoom)
            var current = 0
            var success = 0
            var skipped = 0
            var failed = 0

            AppLogger.log(TAG, "downloadRegions", true, "Starting download for ${regions.size} regions, total tiles: $totalTiles")

            for (region in regions) {
                if (cancelFlag.get()) break

                for (z in minZoom..maxZoom) {
                    if (cancelFlag.get()) break

                    val p1 = OsmTileEngine.latLonToTile(region.maxLat, region.minLon, z)
                    val p2 = OsmTileEngine.latLonToTile(region.minLat, region.maxLon, z)
                    val minX = minOf(p1.first, p2.first)
                    val maxX = maxOf(p1.first, p2.first)
                    val minY = minOf(p1.second, p2.second)
                    val maxY = maxOf(p1.second, p2.second)

                    for (x in minX..maxX) {
                        if (cancelFlag.get()) break
                        for (y in minY..maxY) {
                            if (cancelFlag.get()) break
                            current++

                            val file = MushroomStorageManager.getTileFile(z, x, y)
                            if (file.exists() && file.length() > 0) {
                                skipped++
                                onProgress(current, totalTiles, region.name)
                                continue
                            }

                            val ok = downloadTile(z, x, y, file)
                            if (ok) {
                                success++
                            } else {
                                failed++
                            }
                            onProgress(current, totalTiles, region.name)

                            // Polite delay between tile downloads (35 ms)
                            try {
                                Thread.sleep(35L)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            isDownloading.set(false)
            AppLogger.log(TAG, "downloadRegions", true, "Finished download: success=$success, skipped=$skipped, failed=$failed")
            onFinished(success, skipped, failed)
        }
    }

    private fun downloadTile(z: Int, x: Int, y: Int, destFile: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 10000
                setRequestProperty("User-Agent", "MushroomApp/1.0 (Android; Offline Forest Navigator)")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tmp.exists() && tmp.length() > 0) {
                    tmp.renameTo(destFile)
                    true
                } else false
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
