package com.olegskal.mushroom.map

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.labels.TileBasedLabelStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.PointOfInterest
import org.mapsforge.map.layer.renderer.PolylineContainer
import org.mapsforge.map.rendertheme.RenderContext
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.core.graphics.Display
import org.mapsforge.core.graphics.Position
import org.mapsforge.core.model.Rectangle
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.mapsforge.core.util.MercatorProjection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.*

object OsmTileEngine {

    const val TILE_SIZE = 256
    private const val TAG = "OsmTileEngine"

    var appContext: Context? = null
        set(value) {
            field = value
            if (value != null) {
                try {
                    AndroidGraphicFactory.createInstance(value)
                    initMapsforge()
                } catch (e: Exception) {
                    AppLogger.log(TAG, "appContext", false, "Error initializing AndroidGraphicFactory: ${e.message}")
                }
            }
        }

    private val ramCache: LruCache<String, Bitmap>
    private val loadingKeys = ConcurrentHashMap.newKeySet<String>()
    private val unsupportedKeys = ConcurrentHashMap.newKeySet<String>()
    private val requestQueue = PriorityBlockingQueue<TileRenderRequest>()
    private val renderLock = Any()

    @Volatile var currentZoom: Int = 12
    @Volatile var currentCenterTileX: Int = 0
    @Volatile var currentCenterTileY: Int = 0

    private var multiMapStore: MultiMapDataStore? = null
    private var displayModel: DisplayModel? = null
    private var renderThemeFuture: RenderThemeFuture? = null
    private val loadedMapFiles = mutableListOf<String>()

    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 3)
    private val workerRenderers = arrayOfNulls<DatabaseRenderer>(workerCount)

    var onTileReadyListener: (() -> Unit)? = null

    data class TileRenderRequest(
        val zoom: Int,
        val x: Int,
        val y: Int,
        val key: String,
        val priority: Long
    ) : Comparable<TileRenderRequest> {
        override fun compareTo(other: TileRenderRequest): Int = priority.compareTo(other.priority)
    }

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemory / 4).coerceIn(32768, 131072) // 32MB - 128MB
        ramCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        for (workerId in 0 until workerCount) {
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val req = requestQueue.take()
                        if (req.zoom != currentZoom) {
                            loadingKeys.remove(req.key)
                            continue
                        }

                        var renderedBmp: Bitmap? = null
                        val store = multiMapStore
                        val renderer = workerRenderers[workerId]
                        val theme = renderThemeFuture
                        val model = displayModel

                        if (store != null && renderer != null && theme != null && model != null) {
                            val tile = org.mapsforge.core.model.Tile(req.x, req.y, req.zoom.toByte(), TILE_SIZE)
                            if (store.supportsTile(tile)) {
                                val job = RendererJob(tile, store, theme, model, 1.0f, false, false)
                                val tileBitmap = renderer.executeJob(job)
                                if (tileBitmap != null) {
                                    renderedBmp = AndroidGraphicFactory.getBitmap(tileBitmap)
                                }
                            } else {
                                unsupportedKeys.add(req.key)
                            }
                        }

                        loadingKeys.remove(req.key)
                        if (renderedBmp != null) {
                            ramCache.put(req.key, renderedBmp)
                            onTileReadyListener?.invoke()
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        AppLogger.log(TAG, "worker_$workerId", false, "Render exception: ${e.message}")
                    }
                }
            }.apply {
                name = "OsmTileRenderer-$workerId"
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }
    }

    class CleanDatabaseRenderer(
        mapDataStore: MapDataStore,
        graphicFactory: org.mapsforge.core.graphics.GraphicFactory,
        tileCache: org.mapsforge.map.layer.cache.TileCache,
        labelStore: org.mapsforge.map.layer.labels.LabelStore?,
        renderLabels: Boolean,
        cacheLabels: Boolean,
        hillsRenderConfig: org.mapsforge.map.layer.hills.HillsRenderConfig?
    ) : DatabaseRenderer(mapDataStore, graphicFactory, tileCache, labelStore, renderLabels, cacheLabels, hillsRenderConfig) {

        private val cleanCallback = CleanRenderCallback()

        override fun getRenderCallback(): MyRenderCallback = cleanCallback

        override fun renderPointOfInterest(
            renderContext: RenderContext?,
            pointOfInterest: PointOfInterest?
        ) {
            if (pointOfInterest == null || renderContext == null) return
            val isPlace = pointOfInterest.tags?.any { it.key == "place" } ?: false
            if (isPlace) {
                super.renderPointOfInterest(renderContext, pointOfInterest)
            }
        }

        override fun processReadMapData(
            renderContext: RenderContext,
            mapReadResult: MapReadResult?
        ) {
            if (mapReadResult == null) return
            for (poi in mapReadResult.pois) {
                renderPointOfInterest(renderContext, poi)
            }
            for (way in mapReadResult.ways) {
                renderWay(
                    renderContext,
                    PolylineContainer(way, renderContext.rendererJob.tile, renderContext.rendererJob.tile)
                )
            }
            if (mapReadResult.isWater) {
                renderWaterBackground(renderContext)
            }
        }

        inner class CleanRenderCallback : MyRenderCallback() {
            override fun renderAreaSymbol(
                renderContext: RenderContext?,
                display: Display?,
                priority: Int,
                symbol: org.mapsforge.core.graphics.Bitmap?,
                way: PolylineContainer?
            ) {
                // Suppressed: completely suppress automatic symbols (hospitals, cafes, fuel stations, etc.) on buildings/areas
            }

            override fun renderPointOfInterestSymbol(
                renderContext: RenderContext?,
                display: Display?,
                priority: Int,
                boundary: Rectangle?,
                symbol: org.mapsforge.core.graphics.Bitmap?,
                poi: PointOfInterest?
            ) {
                // Suppressed: completely suppress automatic point POI symbols
            }

            override fun renderPointOfInterestCircle(
                renderContext: RenderContext?,
                radius: Float,
                fill: org.mapsforge.core.graphics.Paint?,
                stroke: org.mapsforge.core.graphics.Paint?,
                level: Int,
                poi: PointOfInterest?
            ) {
                // Suppressed: completely suppress automatic POI circles
            }

            override fun renderPointOfInterestCaption(
                renderContext: RenderContext?,
                display: Display?,
                priority: Int,
                caption: String?,
                horizontalOffset: Float,
                verticalOffset: Float,
                fill: org.mapsforge.core.graphics.Paint?,
                stroke: org.mapsforge.core.graphics.Paint?,
                position: Position?,
                maxTextWidth: Int,
                poi: PointOfInterest?
            ) {
                if (poi != null) {
                    val isPoi = poi.tags?.any {
                        it.key in listOf("amenity", "shop", "tourism", "leisure", "healthcare", "craft", "office", "historic", "emergency")
                    } ?: false
                    if (isPoi) return
                }

                val placeType = poi?.tags?.firstOrNull { it.key == "place" }?.value
                if (placeType == null || caption.isNullOrEmpty()) {
                    super.renderPointOfInterestCaption(renderContext, display, priority, caption, horizontalOffset, verticalOffset, fill, stroke, position, maxTextWidth, poi)
                    return
                }

                val zoom = renderContext?.rendererJob?.tile?.zoomLevel?.toInt() ?: currentZoom
                val targetSize = when (placeType) {
                    "city" -> when {
                        zoom <= 9 -> 12f
                        zoom <= 11 -> 13f
                        else -> 14f
                    }
                    "town" -> if (zoom <= 10) 11f else 12f
                    "village" -> if (zoom <= 12) 10f else 11f
                    "suburb", "quarter" -> 10f
                    else -> 9.5f
                }

                val targetMaxWidth = when (placeType) {
                    "city", "town" -> 110
                    "village" -> 95
                    else -> 85
                }

                val targetFill = if (fill != null) {
                    AndroidGraphicFactory.INSTANCE.createPaint(fill).apply {
                        setTextSize(targetSize)
                    }
                } else null

                val targetStroke = if (stroke != null) {
                    AndroidGraphicFactory.INSTANCE.createPaint(stroke).apply {
                        setTextSize(targetSize)
                        setStrokeWidth(1.1f)
                    }
                } else null

                var hOffset = horizontalOffset
                var vOffset = verticalOffset
                val tile = renderContext?.rendererJob?.tile
                if (tile != null && poi.position != null) {
                    val px = MercatorProjection.getPixelRelativeToTile(poi.position, tile)
                    val rawWidth = (targetFill ?: fill)?.getTextWidth(caption) ?: (caption.length * 8)
                    val effectiveWidth = if (caption.contains(' ') || caption.contains('-')) {
                        min(rawWidth, targetMaxWidth)
                    } else {
                        rawWidth
                    }

                    val halfW = (effectiveWidth / 2f) + 4f
                    val minX = halfW
                    val maxX = TILE_SIZE.toFloat() - halfW
                    if (maxX > minX) {
                        if (px.x < minX) {
                            hOffset += (minX - px.x).toFloat()
                        } else if (px.x > maxX) {
                            hOffset -= (px.x - maxX).toFloat()
                        }
                    }

                    val halfH = targetSize + 4f
                    val minY = halfH
                    val maxY = TILE_SIZE.toFloat() - halfH
                    if (maxY > minY) {
                        if (px.y < minY) {
                            vOffset += (minY - px.y).toFloat()
                        } else if (px.y > maxY) {
                            vOffset -= (px.y - maxY).toFloat()
                        }
                    }
                }

                super.renderPointOfInterestCaption(
                    renderContext,
                    display,
                    priority,
                    caption,
                    hOffset,
                    vOffset,
                    targetFill ?: fill,
                    targetStroke ?: stroke,
                    position,
                    targetMaxWidth,
                    poi
                )
            }

            override fun renderAreaCaption(
                renderContext: RenderContext?,
                display: Display?,
                priority: Int,
                caption: String?,
                horizontalOffset: Float,
                verticalOffset: Float,
                fill: org.mapsforge.core.graphics.Paint?,
                stroke: org.mapsforge.core.graphics.Paint?,
                position: Position?,
                maxTextWidth: Int,
                way: PolylineContainer?
            ) {
                if (way != null) {
                    val tags = way.tags
                    val isPoi = tags?.any {
                        it.key in listOf("amenity", "shop", "tourism", "leisure", "healthcare", "craft", "office", "historic", "emergency")
                    } ?: false
                    if (isPoi) return
                }
                super.renderAreaCaption(renderContext, display, priority, caption, horizontalOffset, verticalOffset, fill, stroke, position, maxTextWidth, way)
            }
        }
    }

    @Synchronized
    fun initMapsforge(force: Boolean = false) {
        try {
            if (appContext == null) return
            val mapsDir = MushroomStorageManager.mapsDir
            val files = mapsDir.listFiles { _, name -> name.endsWith(".map") } ?: emptyArray()
            val currentPaths = files.map { it.absolutePath }.sorted()

            if (!force && currentPaths == loadedMapFiles && workerRenderers[0] != null) {
                return
            }

            synchronized(renderLock) {
                multiMapStore?.close()
                val newStore = MultiMapDataStore()

                org.mapsforge.map.reader.MapFile.wayFilterEnabled = true
                org.mapsforge.map.reader.MapFile.wayFilterDistance = 20
                org.mapsforge.core.util.Parameters.ANDROID_32BIT_COLOR = false

                for (f in files) {
                    try {
                        val mf = MapFile(f)
                        newStore.addMapDataStore(mf, false, false)
                    } catch (e: Exception) {
                        AppLogger.log(TAG, "initMapsforge", false, "Error adding map file ${f.name}: ${e.message}")
                    }
                }

                val dModel = DisplayModel()
                dModel.setFixedTileSize(TILE_SIZE)
                val rThemeFuture = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, MapsforgeThemes.DEFAULT, dModel)
                rThemeFuture.run()

                multiMapStore = newStore
                displayModel = dModel
                renderThemeFuture = rThemeFuture

                val tCache = InMemoryTileCache(256)
                val lStore = TileBasedLabelStore(256)
                for (i in 0 until workerCount) {
                    workerRenderers[i] = CleanDatabaseRenderer(newStore, AndroidGraphicFactory.INSTANCE, tCache, lStore, true, true, null)
                }

                loadedMapFiles.clear()
                loadedMapFiles.addAll(currentPaths)
            }

            clearRamCache()
            unsupportedKeys.clear()
            AppLogger.log(TAG, "initMapsforge", true, "Loaded ${files.size} vector map files with $workerCount workers")
        } catch (e: Exception) {
            AppLogger.log(TAG, "initMapsforge", false, "Error setting up Mapsforge engine: ${e.message}")
        }
    }

    @Synchronized
    fun reloadMaps() {
        initMapsforge(force = true)
        clearRamCache()
        onTileReadyListener?.invoke()
    }

    fun onViewportChanged(zoom: Int, lat: Double, lon: Double) {
        currentZoom = zoom
        val tile = latLonToTile(lat, lon, zoom)
        currentCenterTileX = tile.first
        currentCenterTileY = tile.second

        val it = requestQueue.iterator()
        while (it.hasNext()) {
            val req = it.next()
            if (req.zoom != zoom || abs(req.x - currentCenterTileX) > 4 || abs(req.y - currentCenterTileY) > 5) {
                it.remove()
                loadingKeys.remove(req.key)
            }
        }
    }

    fun hasMapCoverage(lat: Double, lon: Double): Boolean {
        initMapsforge()
        val store = multiMapStore ?: return false
        val bbox = store.boundingBox() ?: return false
        return lat in bbox.minLatitude..bbox.maxLatitude && lon in bbox.minLongitude..bbox.maxLongitude
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

    data class ParentTileInfo(
        val bitmap: Bitmap,
        val zoomDiff: Int,
        val subX: Int,
        val subY: Int
    )

    fun getTile(zoom: Int, x: Int, y: Int): Bitmap? = getTileBitmap(zoom, x, y)

    fun getTileBitmap(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"

        ramCache.get(key)?.let { return it }

        if (unsupportedKeys.contains(key)) return null

        if (multiMapStore == null) {
            initMapsforge()
            if (multiMapStore == null) return null
        }

        if (loadingKeys.add(key)) {
            if (requestQueue.size > 32) {
                val it = requestQueue.iterator()
                while (it.hasNext() && requestQueue.size > 20) {
                    val req = it.next()
                    if (req.zoom != currentZoom || abs(req.x - currentCenterTileX) > 3 || abs(req.y - currentCenterTileY) > 4) {
                        it.remove()
                        loadingKeys.remove(req.key)
                    }
                }
            }
            val dx = (x - currentCenterTileX).toLong()
            val dy = (y - currentCenterTileY).toLong()
            val distSq = dx * dx + dy * dy
            val zoomDiff = abs(zoom - currentZoom).toLong()
            val priority = zoomDiff * 100_000L + distSq
            requestQueue.offer(TileRenderRequest(zoom, x, y, key, priority))
        }
        return null
    }

    fun findParentTile(zoom: Int, x: Int, y: Int, minZoom: Int = 2, maxSearchDepth: Int = 8): ParentTileInfo? {
        val maxDiff = minOf(maxSearchDepth, zoom - minZoom)
        for (d in 1..maxDiff) {
            val pz = zoom - d
            val px = x shr d
            val py = y shr d
            val cached = ramCache.get("$pz/$px/$py")
            if (cached != null && !cached.isRecycled) {
                val mask = (1 shl d) - 1
                return ParentTileInfo(cached, d, x and mask, y and mask)
            }
        }
        return null
    }

    fun getChildTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val cached = ramCache.get("$zoom/$x/$y")
        if (cached != null && !cached.isRecycled) return cached
        return null
    }

    fun clearRamCache() {
        ramCache.evictAll()
        val it = requestQueue.iterator()
        while (it.hasNext()) {
            val req = it.next()
            it.remove()
            loadingKeys.remove(req.key)
        }
        loadingKeys.clear()
        unsupportedKeys.clear()
    }
}
