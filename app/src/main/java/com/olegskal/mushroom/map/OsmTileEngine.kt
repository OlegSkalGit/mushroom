package com.olegskal.mushroom.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.LruCache
import com.olegskal.mushroom.storage.MushroomStorageManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

object OsmTileEngine {

    const val TILE_SIZE = 256

    var appContext: Context? = null

    private val ramCache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemory / 4).coerceIn(32768, 131072) // 32MB - 128MB
        ramCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    private val loadingKeys = ConcurrentHashMap.newKeySet<String>()
    private val failedTileCooldown = ConcurrentHashMap<String, Long>()
    private val activeNetworkDownloads = ConcurrentHashMap.newKeySet<String>()
    private val diskExecutor = Executors.newFixedThreadPool(4)
    private val networkExecutor = Executors.newFixedThreadPool(4)

    private var lastNetworkCheckTime = 0L
    private var lastNetworkState = false

    var onTileReadyListener: (() -> Unit)? = null

    fun isNetworkConnected(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNetworkCheckTime < 2000L) {
            return lastNetworkState
        }
        lastNetworkCheckTime = now
        val ctx = appContext ?: return true
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            lastNetworkState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
            lastNetworkState
        } catch (_: Exception) {
            true
        }
    }

    fun clearMissingTileCache() {
        failedTileCooldown.clear()
    }

    fun purgeBlockedTiles() {
        Thread {
            try {
                val tilesDir = MushroomStorageManager.tilesDir
                tilesDir.walkTopDown().forEach { f ->
                    if (f.isFile && f.extension.equals("png", true) && (f.length() == 6987L || f.length() == 0L)) {
                        f.delete()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

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
        val wp = latLonToWorldPixel(lat, lon, zoom)
        val tileX = (wp.first / TILE_SIZE).toInt()
        val tileY = (wp.second / TILE_SIZE).toInt()
        val maxTile = (1 shl zoom) - 1
        return Pair(tileX.coerceIn(0, maxTile), tileY.coerceIn(0, maxTile))
    }

    fun tileToLatLon(x: Int, y: Int, zoom: Int): Pair<Double, Double> {
        val wpX = (x * TILE_SIZE).toDouble()
        val wpY = (y * TILE_SIZE).toDouble()
        return worldPixelToLatLon(wpX, wpY, zoom)
    }

    fun getTile(zoom: Int, x: Int, y: Int): Bitmap? = getTileBitmap(zoom, x, y)

    fun getTileFromMemory(zoom: Int, x: Int, y: Int): Bitmap? = ramCache.get("$zoom/$x/$y")

    fun getTileBitmap(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"

        // 1. Check RAM Cache
        ramCache.get(key)?.let { return it }

        // 2. Check retry cooldown if previously failed
        val retryAfter = failedTileCooldown[key]
        if (retryAfter != null) {
            if (System.currentTimeMillis() < retryAfter) {
                return null
            } else {
                failedTileCooldown.remove(key)
            }
        }

        // 3. If already loading from disk or actively downloading from network, skip
        if (loadingKeys.contains(key) || activeNetworkDownloads.contains(key)) {
            return null
        }

        // 4. Asynchronously load from disk offline storage
        loadingKeys.add(key)
        diskExecutor.execute {
            try {
                val file = MushroomStorageManager.getTileFile(zoom, x, y)
                if (file.exists() && file.length() > 0L) {
                    if (file.length() == 6987L) {
                        file.delete()
                        handleMissingTile(zoom, x, y, key, file)
                    } else {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                        if (bmp != null) {
                            ramCache.put(key, bmp)
                            failedTileCooldown.remove(key)
                            onTileReadyListener?.invoke()
                        } else {
                            file.delete()
                            handleMissingTile(zoom, x, y, key, file)
                        }
                    }
                } else {
                    handleMissingTile(zoom, x, y, key, file)
                }
            } catch (_: Exception) {
                markTileFailed(key)
            } finally {
                loadingKeys.remove(key)
            }
        }
        return null
    }

    private fun handleMissingTile(zoom: Int, x: Int, y: Int, key: String, destFile: java.io.File) {
        if (!isNetworkConnected()) {
            return
        }
        if (activeNetworkDownloads.size >= 24) {
            // Concurrent slots are currently full; do not mark as failed.
            // On subsequent frame redraws, remaining visible tiles will be requested again.
            return
        }
        if (!activeNetworkDownloads.add(key)) {
            return
        }
        networkExecutor.execute {
            try {
                val ok = MapDownloadManager.downloadTile(zoom, x, y, destFile)
                if (ok) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeFile(destFile.absolutePath, opts)
                    if (bmp != null) {
                        ramCache.put(key, bmp)
                        failedTileCooldown.remove(key)
                        onTileReadyListener?.invoke()
                    } else {
                        destFile.delete()
                        markTileFailed(key)
                    }
                } else {
                    markTileFailed(key)
                }
            } catch (_: Exception) {
                markTileFailed(key)
            } finally {
                activeNetworkDownloads.remove(key)
            }
        }
    }

    private fun markTileFailed(key: String) {
        val now = System.currentTimeMillis()
        if (failedTileCooldown.size > 500) {
            val it = failedTileCooldown.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value < now) {
                    it.remove()
                }
            }
        }
        failedTileCooldown[key] = now + 15_000L
    }

    fun clearRamCache() {
        ramCache.evictAll()
        failedTileCooldown.clear()
        activeNetworkDownloads.clear()
    }
}
