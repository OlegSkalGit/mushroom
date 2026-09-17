package com.olegskal.mushroom.map

import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    val regions: List<MapRegion> = emptyList()
)

data class TileTask(
    val z: Int,
    val x: Int,
    val y: Int,
    val regionName: String
)

object MapDownloadManager {

    private const val TAG = "MapDownloadManager"
    private val CYCLOSM_SERVERS = arrayOf(
        "https://a.tile-cyclosm.openstreetmap.fr/cyclosm",
        "https://b.tile-cyclosm.openstreetmap.fr/cyclosm",
        "https://c.tile-cyclosm.openstreetmap.fr/cyclosm"
    )
    private const val USER_AGENT = "Mushroom/2.0 (Android; https://github.com/OlegSkalGit/mushroom)"
    private const val REFERER = "https://www.cyclosm.org/"
    private const val WORKER_COUNT = 3

    const val DEFAULT_MIN_ZOOM = 10
    const val DEFAULT_MAX_ZOOM = 15

    private val dispatcherExecutor = Executors.newSingleThreadExecutor()
    private val workerExecutor = Executors.newFixedThreadPool(WORKER_COUNT)

    private val isDownloading = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private val taskQueue = ConcurrentLinkedQueue<TileTask>()

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

    fun calculateTileCount(regions: List<MapRegion>, minZoom: Int = DEFAULT_MIN_ZOOM, maxZoom: Int = DEFAULT_MAX_ZOOM): Int {
        val seen = HashSet<Long>()
        for (region in regions) {
            for (z in minZoom..maxZoom) {
                val p1 = OsmTileEngine.latLonToTile(region.maxLat, region.minLon, z)
                val p2 = OsmTileEngine.latLonToTile(region.minLat, region.maxLon, z)
                val minX = minOf(p1.first, p2.first)
                val maxX = maxOf(p1.first, p2.first)
                val minY = minOf(p1.second, p2.second)
                val maxY = maxOf(p1.second, p2.second)
                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        val key = ((z.toLong() and 0x1FL) shl 40) or ((x.toLong() and 0xFFFFFL) shl 20) or (y.toLong() and 0xFFFFFL)
                        seen.add(key)
                    }
                }
            }
        }
        return seen.size
    }

    fun isCurrentlyDownloading(): Boolean = isDownloading.get()

    data class ProgressInfo(
        val current: Int,
        val total: Int,
        val currentZoom: Int,
        val currentRegion: String
    )

    var lastProgressInfo: ProgressInfo? = null
        private set
    var onProgressUpdate: ((ProgressInfo) -> Unit)? = null
    var onDownloadCompleted: ((successCount: Int, skippedCount: Int, failCount: Int) -> Unit)? = null

    fun cancelDownload() {
        cancelFlag.set(true)
        taskQueue.clear()
        lastProgressInfo = null
    }

    fun downloadRegions(
        regions: List<MapRegion>,
        minZoom: Int = DEFAULT_MIN_ZOOM,
        maxZoom: Int = DEFAULT_MAX_ZOOM,
        onProgress: ((ProgressInfo) -> Unit)? = null,
        onFinished: ((successCount: Int, skippedCount: Int, failCount: Int) -> Unit)? = null
    ) {
        if (!isDownloading.compareAndSet(false, true)) return
        cancelFlag.set(false)
        taskQueue.clear()
        this.onProgressUpdate = onProgress
        this.onDownloadCompleted = onFinished

        dispatcherExecutor.execute {
            try {
                val seen = HashSet<Long>()
                for (z in minZoom..maxZoom) {
                    if (cancelFlag.get()) break
                    for (region in regions) {
                        if (cancelFlag.get()) break
                        val p1 = OsmTileEngine.latLonToTile(region.maxLat, region.minLon, z)
                        val p2 = OsmTileEngine.latLonToTile(region.minLat, region.maxLon, z)
                        val minX = minOf(p1.first, p2.first)
                        val maxX = maxOf(p1.first, p2.first)
                        val minY = minOf(p1.second, p2.second)
                        val maxY = maxOf(p1.second, p2.second)

                        for (x in minX..maxX) {
                            for (y in minY..maxY) {
                                val key = ((z.toLong() and 0x1FL) shl 40) or ((x.toLong() and 0xFFFFFL) shl 20) or (y.toLong() and 0xFFFFFL)
                                if (seen.add(key)) {
                                    taskQueue.add(TileTask(z, x, y, region.name))
                                }
                            }
                        }
                    }
                }

                val totalTiles = taskQueue.size
                val current = AtomicInteger(0)
                val success = AtomicInteger(0)
                val skipped = AtomicInteger(0)
                val failed = AtomicInteger(0)

                AppLogger.log(TAG, "downloadRegions", true, "Starting download for ${regions.size} regions, deduplicated tiles: $totalTiles using $WORKER_COUNT workers")

                if (totalTiles == 0 || cancelFlag.get()) {
                    isDownloading.set(false)
                    lastProgressInfo = null
                    onDownloadCompleted?.invoke(0, 0, 0)
                    return@execute
                }

                val latch = CountDownLatch(WORKER_COUNT)

                for (workerId in 0 until WORKER_COUNT) {
                    workerExecutor.execute {
                        try {
                            while (!cancelFlag.get()) {
                                val task = taskQueue.poll() ?: break
                                val cur = current.incrementAndGet()

                                val file = MushroomStorageManager.getTileFile(task.z, task.x, task.y)
                                if (file.exists() && file.length() > 0) {
                                    if (file.length() == 6987L) {
                                        file.delete()
                                    } else {
                                        skipped.incrementAndGet()
                                        val info = ProgressInfo(cur, totalTiles, task.z, task.regionName)
                                        lastProgressInfo = info
                                        onProgressUpdate?.invoke(info)
                                        continue
                                    }
                                }

                                val ok = downloadTile(task.z, task.x, task.y, file)
                                if (ok) {
                                    success.incrementAndGet()
                                } else {
                                    failed.incrementAndGet()
                                }
                                val info = ProgressInfo(cur, totalTiles, task.z, task.regionName)
                                lastProgressInfo = info
                                onProgressUpdate?.invoke(info)
                            }
                        } catch (e: Exception) {
                            AppLogger.log(TAG, "worker_$workerId", false, "Worker error: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                latch.await()

                val s = success.get()
                val sk = skipped.get()
                val f = failed.get()

                AppLogger.log(TAG, "downloadRegions", true, "Finished download: success=$s, skipped=$sk, failed=$f")
                lastProgressInfo = null
                onDownloadCompleted?.invoke(s, sk, f)
            } catch (e: Exception) {
                AppLogger.log(TAG, "downloadRegions", false, "Dispatcher error: ${e.message}")
                lastProgressInfo = null
                onDownloadCompleted?.invoke(0, 0, 0)
            } finally {
                taskQueue.clear()
                isDownloading.set(false)
            }
        }
    }

    fun downloadTile(z: Int, x: Int, y: Int, destFile: File): Boolean {
        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        return try {
            val serverIdx = kotlin.math.abs(x * 31 + y) % CYCLOSM_SERVERS.size
            val url = URL("${CYCLOSM_SERVERS[serverIdx]}/$z/$x/$y.png")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 9000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", REFERER)
                setRequestProperty("Connection", "keep-alive")
            }
            val isBlocked = conn.getHeaderField("x-blocked") != null || conn.getHeaderField("X-Blocked") != null
            if (conn.responseCode == HttpURLConnection.HTTP_OK && !isBlocked && conn.contentLength != 6987) {
                val tmp = File(destFile.parentFile, "${destFile.name}.${Thread.currentThread().id}.tmp")
                inputStream = conn.inputStream
                FileOutputStream(tmp).use { output ->
                    inputStream?.copyTo(output)
                }
                if (tmp.exists() && tmp.length() > 0 && tmp.length() != 6987L) {
                    tmp.renameTo(destFile)
                    true
                } else {
                    tmp.delete()
                    false
                }
            } else {
                conn.disconnect()
                false
            }
        } catch (_: Exception) {
            conn?.disconnect()
            false
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    fun getRegionDownloadStatus(region: MapRegion, minZoom: Int = DEFAULT_MIN_ZOOM, maxZoom: Int = DEFAULT_MAX_ZOOM): Pair<Int, Int> {
        var total = 0
        var existing = 0
        for (z in minZoom..maxZoom) {
            val p1 = OsmTileEngine.latLonToTile(region.maxLat, region.minLon, z)
            val p2 = OsmTileEngine.latLonToTile(region.minLat, region.maxLon, z)
            val minX = minOf(p1.first, p2.first)
            val maxX = maxOf(p1.first, p2.first)
            val minY = minOf(p1.second, p2.second)
            val maxY = maxOf(p1.second, p2.second)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    total++
                    val f = MushroomStorageManager.getTileFile(z, x, y)
                    if (f.exists() && f.length() > 0 && f.length() != 6987L) {
                        existing++
                    }
                }
            }
        }
        return Pair(existing, total)
    }
}
