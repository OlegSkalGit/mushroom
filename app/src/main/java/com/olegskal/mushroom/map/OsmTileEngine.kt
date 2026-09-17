package com.olegskal.mushroom.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

object OsmTileEngine {

    private const val TAG = "OsmTileEngine"
    const val TILE_SIZE = 256

    private const val OSM_TILE_URL = "https://tile.openstreetmap.org"

    private val ramCache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemory / 8).coerceIn(4096, 16384) // 4MB - 16MB
        ramCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    private val loadingKeys = ConcurrentHashMap.newKeySet<String>()
    private val diskExecutor = Executors.newFixedThreadPool(2)
    private val netExecutor = Executors.newFixedThreadPool(2)

    var onTileReadyListener: (() -> Unit)? = null

    fun latLonToWorldPixel(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val clampedLon = lon.coerceIn(-180.0, 180.0)
        val mapSize = (TILE_SIZE.toLong() shl zoom).toDouble()

        val x = ((clampedLon + 180.0) / 360.0) * mapSize
        val sinLat = sin(Math.toRadians(clampedLat))
        val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * mapSize
        return Pair(x, y)
    }

    fun worldPixelToLatLon(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
        val mapSize = (TILE_SIZE.toLong() shl zoom).toDouble()
        val lon = (x / mapSize) * 360.0 - 180.0
        val y2 = 0.5 - (y / mapSize)
        val lat = 90.0 - 360.0 * atan(exp(-y2 * 2.0 * Math.PI)) / Math.PI
        return Pair(lat.coerceIn(-85.05112878, 85.05112878), lon.coerceIn(-180.0, 180.0))
    }

    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val clampedLon = lon.coerceIn(-180.0, 180.0)
        val x = floor((clampedLon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(clampedLat)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return Pair(x, y)
    }

    fun getTile(zoom: Int, x: Int, y: Int, allowNetworkDownload: Boolean = true): Bitmap? {
        val key = "$zoom/$x/$y"
        val cached = ramCache.get(key)
        if (cached != null) {
            return cached
        }

        if (loadingKeys.contains(key)) {
            return null
        }

        // Asynchronously load from disk or network
        loadingKeys.add(key)
        diskExecutor.execute {
            try {
                val file = MushroomStorageManager.getTileFile(zoom, x, y)
                if (file.exists() && file.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        ramCache.put(key, bmp)
                        loadingKeys.remove(key)
                        onTileReadyListener?.invoke()
                        return@execute
                    }
                }

                if (allowNetworkDownload) {
                    netExecutor.execute {
                        downloadAndCacheTile(zoom, x, y, key, file)
                    }
                } else {
                    loadingKeys.remove(key)
                }
            } catch (e: Exception) {
                loadingKeys.remove(key)
            }
        }
        return null
    }

    private fun downloadAndCacheTile(zoom: Int, x: Int, y: Int, key: String, destFile: File) {
        var conn: HttpURLConnection? = null
        try {
            val urlStr = "$OSM_TILE_URL/$zoom/$x/$y.png"
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MushroomApp/1.0 (Android; Offline Forest Navigator)")
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(destFile)
                    val bmp = BitmapFactory.decodeFile(destFile.absolutePath)
                    if (bmp != null) {
                        ramCache.put(key, bmp)
                        onTileReadyListener?.invoke()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
            loadingKeys.remove(key)
        }
    }

    fun clearRamCache() {
        ramCache.evictAll()
    }
}
