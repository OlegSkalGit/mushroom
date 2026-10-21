package com.olegskal.mushroom.map

import android.content.Context
import com.olegskal.mushroom.math.GeoMath
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.*

enum class TransportType {
    CAR,
    FOOT,
    BICYCLE
}

data class RouteSegment(
    val id: String,
    val legIndex: Int,
    var spanIndex: Int,
    val variantIndex: Int,
    val points: List<Pair<Double, Double>>,
    val distanceMeters: Float,
    val advantage: String,
    var isSelected: Boolean = false
)

data class RouteCalculationResult(
    val segments: List<RouteSegment>,
    val effectiveWaypoints: List<Pair<Double, Double>>
)

object BRouterEngine {

    private const val TAG = "BRouterEngine"

    fun getProfilesDir(context: Context? = null): File {
        val ctx = context ?: OsmTileEngine.appContext
        val baseDir = ctx?.let { it.noBackupFilesDir ?: it.filesDir } ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return File(baseDir, "brouter_profiles").apply { if (!exists()) mkdirs() }
    }

    val profilesDir: File
        get() = getProfilesDir()

    fun ensureProfilesExtracted(context: Context) {
        try {
            // Видаляємо стару папку з зовнішнього сховища /sdcard/mushroom/navigation/profiles, якщо вона залишилась
            val oldExternalProfilesDir = File(MushroomStorageManager.navigationDir, "profiles")
            if (oldExternalProfilesDir.exists()) {
                try {
                    oldExternalProfilesDir.deleteRecursively()
                } catch (_: Exception) {}
            }

            val targetDir = getProfilesDir(context)
            val assetNames = context.assets.list("brouter") ?: emptyArray()
            for (name in assetNames) {
                val dest = File(targetDir, name)
                val assetBytes = context.assets.open("brouter/$name").use { it.readBytes() }
                if (!dest.exists() || dest.length() != assetBytes.size.toLong()) {
                    dest.writeBytes(assetBytes)
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "ensureProfilesExtracted", false, "Error extracting profiles: ${e.message}")
        }
    }

    fun getSegmentFileName(lat: Double, lon: Double): String {
        val ilonDegree = (floor(lon) + 180.0).toInt()
        val ilatDegree = (floor(lat) + 90.0).toInt()
        val ilonrem = ((ilonDegree % 5) + 5) % 5
        val ilatrem = ((ilatDegree % 5) + 5) % 5
        val lonIdx = ilonDegree - 180 - ilonrem
        val lonStr = if (lonIdx < 0) "W${-lonIdx}" else "E$lonIdx"
        val latIdx = ilatDegree - 90 - ilatrem
        val latStr = if (latIdx < 0) "S${-latIdx}" else "N$latIdx"
        return "${lonStr}_${latStr}.rd5"
    }

    fun hasNavigationDataFor(lat: Double, lon: Double): Boolean {
        val file = File(MushroomStorageManager.navigationDir, getSegmentFileName(lat, lon))
        return file.exists() && file.length() > 1024L
    }

    fun hasNavigationDataForWaypoints(waypoints: List<Pair<Double, Double>>): Boolean {
        return waypoints.any { hasNavigationDataFor(it.first, it.second) }
    }

    fun buildRouteSegmentsBetweenWaypoints(
        waypoints: List<Pair<Double, Double>>,
        transport: TransportType,
        isUk: Boolean,
        onInitialRouteReady: ((RouteCalculationResult) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null
    ): RouteCalculationResult {
        if (waypoints.size < 2) {
            val emptyRes = RouteCalculationResult(emptyList(), waypoints)
            onInitialRouteReady?.invoke(emptyRes)
            onProgress?.invoke(100)
            return emptyRes
        }

        OsmTileEngine.appContext?.let { ensureProfilesExtracted(it) }

        onProgress?.invoke(5)

        val totalLegs = waypoints.size - 1
        val profileOptions = getProfileOptions(transport)
        val totalSteps = totalLegs * profileOptions.size
        var completedSteps = 0

        val onStep: () -> Unit = {
            completedSteps++
            val pct = 5 + ((completedSteps.toFloat() / totalSteps.toFloat()) * 85f).toInt()
            onProgress?.invoke(pct.coerceIn(5, 90))
        }

        // Phase 1: Compute first route (profile 0, or fallback profile if profile 0 fails) for each leg and emit immediately
        val primaryAltsPerLeg = ArrayList<RawAlternative?>(totalLegs)
        val initialSegments = mutableListOf<RouteSegment>()

        for (i in 0 until totalLegs) {
            val start = waypoints[i]
            val end = waypoints[i + 1]
            var computedAlt: RawAlternative? = null
            for (pIdx in profileOptions.indices) {
                val cand = calculateSingleAlternative(start.first, start.second, end.first, end.second, profileOptions[pIdx], pIdx, isUk)
                if (cand != null && cand.points.size > 2) {
                    computedAlt = cand
                    break
                } else if (cand != null && computedAlt == null) {
                    computedAlt = cand
                }
            }
            val displayAlt = computedAlt ?: createStraightLineAlternative(start.first, start.second, end.first, end.second, isUk)
            primaryAltsPerLeg.add(computedAlt?.takeIf { it.points.size > 2 })
            initialSegments.add(
                RouteSegment(
                    id = "seg_${i}_0",
                    legIndex = i,
                    spanIndex = i,
                    variantIndex = 0,
                    points = displayAlt.points,
                    distanceMeters = displayAlt.distanceMeters,
                    advantage = displayAlt.advantage,
                    isSelected = true
                )
            )
            onStep()
        }

        onInitialRouteReady?.invoke(RouteCalculationResult(initialSegments, waypoints))

        // Phase 2: Compute remaining alternatives and intersection splits in background
        val allSpans = mutableListOf<Pair<Int, List<RawAlternative>>>()
        val allWaypoints = mutableListOf<Pair<Double, Double>>()
        allWaypoints.add(waypoints.first())

        for (i in 0 until totalLegs) {
            val start = waypoints[i]
            val end = waypoints[i + 1]

            val spanResult = processSpanWithIntersectionSplits(
                start, end, transport, isUk, depth = 0,
                onStep = onStep,
                initialAlternative = primaryAltsPerLeg[i]
            )
            for (span in spanResult.spans) {
                allSpans.add(Pair(i, span))
            }
            for (p in spanResult.waypoints.drop(1)) {
                allWaypoints.add(p)
            }
        }

        onProgress?.invoke(95)

        val resultSegments = mutableListOf<RouteSegment>()
        for (spanIdx in allSpans.indices) {
            val (legIdx, variants) = allSpans[spanIdx]
            for (vIdx in variants.indices) {
                val v = variants[vIdx]
                resultSegments.add(
                    RouteSegment(
                        id = "seg_${spanIdx}_$vIdx",
                        legIndex = legIdx,
                        spanIndex = spanIdx,
                        variantIndex = vIdx,
                        points = v.points,
                        distanceMeters = v.distanceMeters,
                        advantage = v.advantage,
                        isSelected = v.isPrimarySelected
                    )
                )
            }
        }

        onProgress?.invoke(100)
        return RouteCalculationResult(resultSegments, allWaypoints)
    }

    private data class RawAlternative(
        val variantIndex: Int,
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Float,
        val advantage: String,
        val isPrimarySelected: Boolean = false
    )

    private data class ProfileOption(
        val filename: String,
        val altIdx: Int,
        val titleUk: String,
        val titleEn: String,
        val params: Map<String, String> = emptyMap()
    )

    private fun getProfileOptions(transport: TransportType): List<ProfileOption> = when (transport) {
        TransportType.CAR -> listOf(
            ProfileOption(
                "car-fast.brf", 0,
                "🚗 Швидкий автошлях",
                "🚗 Fast driving route",
                mapOf("drivestyle" to "3", "fastprofile" to "1", "avoid_unpaved" to "1", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "car-fast.brf", 1,
                "🛣️ Альтернативний автошлях",
                "🛣️ Alternative driving route",
                mapOf("drivestyle" to "3", "fastprofile" to "1", "avoid_unpaved" to "1", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "car-eco.brf", 0,
                "🌿 Економічний автошлях",
                "🌿 Eco driving route",
                mapOf("drivestyle" to "1", "avoid_unpaved" to "1", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "car-fast.brf", 0,
                "⚡ Регіональний об'їзд (без магістралей)",
                "⚡ Regional bypass (avoid motorways)",
                mapOf("avoid_motorways" to "1", "avoid_unpaved" to "1", "drivestyle" to "2", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "car-fast.brf", 0,
                "🚙 Коротший проїзд (включно з ґрунтовками)",
                "🚙 Shortest route (including unpaved)",
                mapOf("avoid_unpaved" to "0", "drivestyle" to "0", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            )
        )
        TransportType.FOOT -> listOf(
            ProfileOption(
                "hiking-mountain.brf", 0,
                "🌲 Стежка лісом (мальовнича)",
                "🌲 Scenic forest trail",
                mapOf("path_preference" to "20.0", "consider_forest" to "1", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "trekking.brf", 0,
                "🚶 Зручний пішохідний трекінг",
                "🚶 Convenient walking trek",
                mapOf("add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "shortest.brf", 0,
                "⚡ Найкоротший прямий маршрут пішки",
                "⚡ Shortest direct walking route",
                mapOf("add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "hiking-mountain.brf", 1,
                "🌿 Альтернативна пішохідна стежка",
                "🌿 Alternative walking trail",
                mapOf("path_preference" to "0.0", "add_beeline" to "1", "waypointCatchingRange" to "2500")
            )
        )
        TransportType.BICYCLE -> listOf(
            ProfileOption(
                "fastbike.brf", 0,
                "🚴 Швидкісний веломаршрут з якісним покриттям",
                "🚴 Fast cycling route with paved surface",
                mapOf("add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "trekking.brf", 0,
                "🌿 Зручний веломаршрут ґрунтовими та лісовими дорогами",
                "🌿 Convenient cycling path via trails",
                mapOf("add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "fastbike.brf", 1,
                "🛣️ Альтернативний велооб'їзд",
                "🛣️ Alternative cycling bypass",
                mapOf("add_beeline" to "1", "waypointCatchingRange" to "2500")
            ),
            ProfileOption(
                "shortest.brf", 0,
                "⚡ Найкоротший веломаршрут",
                "⚡ Shortest cycling route",
                mapOf("add_beeline" to "1", "waypointCatchingRange" to "2500")
            )
        )
    }

    private fun calculateSingleAlternative(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        opt: ProfileOption,
        idx: Int,
        isUk: Boolean
    ): RawAlternative? {
        val profFile = File(profilesDir, opt.filename)
        var computedPoints: List<Pair<Double, Double>>? = null
        var computedDist = 0f

        if (profFile.exists() && (hasNavigationDataFor(startLat, startLon) || hasNavigationDataFor(endLat, endLon))) {
            var rc: btools.router.RoutingContext? = null
            try {
                rc = btools.router.RoutingContext().apply {
                    localFunction = profFile.absolutePath
                    setAlternativeIdx(opt.altIdx)
                    keyValues = HashMap<String, String>().apply {
                        put("add_beeline", "1")
                        put("waypointCatchingRange", "2500")
                        opt.params.forEach { (k, v) -> put(k, v) }
                    }
                }
                btools.router.ProfileCache.parseProfile(rc)

                val startNode = btools.router.OsmNodeNamed().apply {
                    ilat = floor((startLat + 90.0) * 1e6 + 0.5).toInt()
                    ilon = floor((startLon + 180.0) * 1e6 + 0.5).toInt()
                    name = "from"
                }
                val endNode = btools.router.OsmNodeNamed().apply {
                    ilat = floor((endLat + 90.0) * 1e6 + 0.5).toInt()
                    ilon = floor((endLon + 180.0) * 1e6 + 0.5).toInt()
                    name = "to"
                }

                val engine = btools.router.RoutingEngine(
                    null,
                    null,
                    MushroomStorageManager.navigationDir,
                    listOf(startNode, endNode),
                    rc
                )
                engine.quite = true
                engine.doRun(15000L)

                val trk = engine.foundTrack
                if (trk != null && trk.nodes != null && trk.nodes.size >= 2) {
                    val pts = ArrayList<Pair<Double, Double>>(trk.nodes.size + 2)
                    val firstLat = (trk.nodes[0].getILat() - 90000000) / 1000000.0
                    val firstLon = (trk.nodes[0].getILon() - 180000000) / 1000000.0
                    if (GeoMath.calculateDistance(startLat, startLon, firstLat, firstLon) > 3f) {
                        pts.add(Pair(startLat, startLon))
                    }
                    for (node in trk.nodes) {
                        val lat = (node.getILat() - 90000000) / 1000000.0
                        val lon = (node.getILon() - 180000000) / 1000000.0
                        pts.add(Pair(lat, lon))
                    }
                    val last = pts.last()
                    if (GeoMath.calculateDistance(endLat, endLon, last.first, last.second) > 3f) {
                        pts.add(Pair(endLat, endLon))
                    }
                    computedPoints = pts
                    computedDist = computePolylineDistance(pts)
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "calculateSingleAlternative", false, "Routing error with ${opt.filename}: ${e.message}")
            } finally {
                rc?.let {
                    try { btools.router.ProfileCache.releaseProfile(it) } catch (_: Exception) {}
                }
            }
        }

        if (computedPoints != null && computedPoints.size >= 2) {
            val km = computedDist / 1000f
            val unit = if (isUk) "км" else "km"
            val title = if (isUk) opt.titleUk else opt.titleEn
            val fullAdvantage = "$title (${String.format(Locale.US, "%.2f", km)} $unit)"
            return RawAlternative(idx, computedPoints, computedDist, fullAdvantage)
        }
        return null
    }

    private fun createStraightLineAlternative(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        isUk: Boolean
    ): RawAlternative {
        val dist = GeoMath.calculateDistance(startLat, startLon, endLat, endLon)
        val km = dist / 1000f
        val unit = if (isUk) "км" else "km"
        val title = if (isUk) "Пряма лінія" else "Direct line"
        val advantage = "$title (${String.format(Locale.US, "%.2f", km)} $unit)"
        return RawAlternative(0, listOf(Pair(startLat, startLon), Pair(endLat, endLon)), dist, advantage)
    }

    private fun calculateRawAlternatives(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        transport: TransportType,
        isUk: Boolean,
        onStep: (() -> Unit)? = null,
        initialAlternative: RawAlternative? = null
    ): List<RawAlternative> {
        val profileOptions = getProfileOptions(transport)
        val alternatives = mutableListOf<RawAlternative>()

        val startIndex: Int
        if (initialAlternative != null && initialAlternative.points.size > 2) {
            alternatives.add(initialAlternative)
            startIndex = 1
        } else {
            startIndex = 0
        }

        for (idx in startIndex until profileOptions.size) {
            val opt = profileOptions[idx]
            val alt = calculateSingleAlternative(startLat, startLon, endLat, endLon, opt, idx, isUk)
            if (alt != null) {
                val isUnpavedOpt = opt.params["avoid_unpaved"] == "0"
                val shouldAdd = if (isUnpavedOpt && alternatives.isNotEmpty()) {
                    val primaryDist = alternatives.first().distanceMeters
                    alt.distanceMeters < primaryDist - 25f || alternatives.first().points.size <= 2
                } else {
                    true
                }
                if (shouldAdd) {
                    alternatives.add(alt)
                }
            }
            onStep?.invoke()
        }

        if (alternatives.isEmpty()) {
            alternatives.add(createStraightLineAlternative(startLat, startLon, endLat, endLon, isUk))
        }

        return alternatives
    }

    private fun computePolylineDistance(pts: List<Pair<Double, Double>>): Float {
        var dist = 0f
        for (i in 0 until pts.size - 1) {
            dist += GeoMath.calculateDistance(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        return dist
    }

    private fun filterDuplicates(alternatives: List<RawAlternative>): List<RawAlternative> {
        val valid = mutableListOf<RawAlternative>()

        for (alt in alternatives) {
            if (alt.points.size < 2) continue

            var dupIdx = -1
            for (eIdx in valid.indices) {
                val existing = valid[eIdx]
                val distDiffRatio = abs(alt.distanceMeters - existing.distanceMeters) / max(1f, existing.distanceMeters)
                if (distDiffRatio < 0.025f) {
                    var maxDivergence = 0f
                    val sampleCount = minOf(25, maxOf(2, alt.points.size))
                    for (s in 1 until sampleCount) {
                        val pt = alt.points[(alt.points.size * s) / sampleCount]
                        val minD = minDistToPath(pt, existing.points)
                        if (minD > maxDivergence) maxDivergence = minD
                    }
                    if (maxDivergence < 25f) {
                        dupIdx = eIdx
                        break
                    }
                }
            }
            if (dupIdx < 0) {
                valid.add(alt)
            } else if (alt.isPrimarySelected && !valid[dupIdx].isPrimarySelected) {
                valid[dupIdx] = valid[dupIdx].copy(isPrimarySelected = true)
            }
        }

        return if (valid.isNotEmpty()) valid else alternatives.take(1)
    }

    private data class IntermediateSpanResult(
        val spans: List<List<RawAlternative>>,
        val waypoints: List<Pair<Double, Double>>
    )

    private fun processSpanWithIntersectionSplits(
        start: Pair<Double, Double>,
        end: Pair<Double, Double>,
        transport: TransportType,
        isUk: Boolean,
        depth: Int,
        onStep: (() -> Unit)? = null,
        initialAlternative: RawAlternative? = null
    ): IntermediateSpanResult {
        val rawAlternatives = calculateRawAlternatives(
            start.first, start.second, end.first, end.second, transport, isUk, onStep,
            initialAlternative = if (depth == 0) initialAlternative else null
        )
        val filtered = filterDuplicates(rawAlternatives)

        if (filtered.size < 2) {
            val single = filtered.mapIndexed { idx, a -> a.copy(isPrimarySelected = (idx == 0)) }
            return IntermediateSpanResult(listOf(single), listOf(start, end))
        }

        val splitPoints = findAllIntersectionPoints(filtered, start, end)
        if (splitPoints.isEmpty()) {
            val marked = filtered.mapIndexed { idx, a -> a.copy(isPrimarySelected = (idx == 0)) }
            return IntermediateSpanResult(listOf(marked), listOf(start, end))
        }

        val unit = if (isUk) "км" else "km"
        val endNodeId = splitPoints.size + 1

        data class CutPoint(
            val nodeId: Int,
            val vertexIdx: Int,
            val coord: Pair<Double, Double>,
            val arcDist: Float
        )

        val spanBuckets = LinkedHashMap<Pair<Int, Int>, MutableList<RawAlternative>>()

        for (altIdx in filtered.indices) {
            val alt = filtered[altIdx]
            val pts = alt.points
            if (pts.size < 2) continue
            val isPrimary = (altIdx == 0)
            val baseTitle = alt.advantage.substringBefore(" (")

            val cumDist = FloatArray(pts.size)
            for (i in 1 until pts.size) {
                cumDist[i] = cumDist[i - 1] + GeoMath.calculateDistance(
                    pts[i - 1].first, pts[i - 1].second,
                    pts[i].first, pts[i].second
                )
            }
            val totalDist = cumDist[pts.size - 1]

            val candidateCuts = ArrayList<CutPoint>()
            if (pts.size >= 3 && totalDist >= 90f) {
                for (k in splitPoints.indices) {
                    val sp = splitPoints[k]
                    var closestIdx = -1
                    var minDist = Float.MAX_VALUE
                    for (i in 1 until pts.size - 1) {
                        val d = GeoMath.calculateDistance(pts[i].first, pts[i].second, sp.first, sp.second)
                        if (d < minDist) {
                            minDist = d
                            closestIdx = i
                        }
                    }
                    if (closestIdx in 1 until (pts.size - 1) && minDist <= 18f) {
                        val dFromStart = cumDist[closestIdx]
                        val dToEnd = totalDist - dFromStart
                        if (dFromStart >= 40f && dToEnd >= 40f) {
                            candidateCuts.add(CutPoint(k + 1, closestIdx, sp, dFromStart))
                        }
                    }
                }
            }

            candidateCuts.sortBy { it.vertexIdx }
            val acceptedCuts = ArrayList<CutPoint>(candidateCuts.size)
            for (c in candidateCuts) {
                if (acceptedCuts.isEmpty()) {
                    acceptedCuts.add(c)
                } else {
                    val prev = acceptedCuts.last()
                    if (c.vertexIdx > prev.vertexIdx && (c.arcDist - prev.arcDist) >= 40f) {
                        acceptedCuts.add(c)
                    }
                }
            }

            if (acceptedCuts.isEmpty()) {
                val key = Pair(0, endNodeId)
                spanBuckets.getOrPut(key) { ArrayList() }.add(
                    alt.copy(isPrimarySelected = isPrimary)
                )
            } else {
                val sliceCount = acceptedCuts.size + 1
                for (s in 0 until sliceCount) {
                    val fromNode = if (s == 0) 0 else acceptedCuts[s - 1].nodeId
                    val toNode = if (s == acceptedCuts.size) endNodeId else acceptedCuts[s].nodeId
                    val startVIdx = if (s == 0) 0 else acceptedCuts[s - 1].vertexIdx
                    val endVIdx = if (s == acceptedCuts.size) pts.size - 1 else acceptedCuts[s].vertexIdx

                    val slicePts = ArrayList<Pair<Double, Double>>(endVIdx - startVIdx + 3)
                    if (s > 0) {
                        val startCoord = acceptedCuts[s - 1].coord
                        slicePts.add(startCoord)
                    }
                    for (v in startVIdx..endVIdx) {
                        val pt = pts[v]
                        if (slicePts.isEmpty() || GeoMath.calculateDistance(slicePts.last().first, slicePts.last().second, pt.first, pt.second) >= 1.5f) {
                            slicePts.add(pt)
                        }
                    }
                    if (s < acceptedCuts.size) {
                        val endCoord = acceptedCuts[s].coord
                        if (slicePts.isEmpty() || GeoMath.calculateDistance(slicePts.last().first, slicePts.last().second, endCoord.first, endCoord.second) >= 1.5f) {
                            slicePts.add(endCoord)
                        } else {
                            slicePts[slicePts.size - 1] = endCoord
                        }
                    }

                    if (slicePts.size >= 2) {
                        val sDist = computePolylineDistance(slicePts)
                        val sKm = sDist / 1000f
                        val sAdv = "$baseTitle (${String.format(Locale.US, "%.2f", sKm)} $unit)"
                        val key = Pair(fromNode, toNode)
                        spanBuckets.getOrPut(key) { ArrayList() }.add(
                            RawAlternative(
                                variantIndex = alt.variantIndex,
                                points = slicePts,
                                distanceMeters = sDist,
                                advantage = sAdv,
                                isPrimarySelected = isPrimary
                            )
                        )
                    }
                }
            }
        }

        val sortedKeys = spanBuckets.keys.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val finalSpans = ArrayList<List<RawAlternative>>(sortedKeys.size)
        for (key in sortedKeys) {
            val rawList = spanBuckets[key] ?: continue
            val dedup = filterDuplicates(rawList)
            var primaryAssigned = false
            val normalized = dedup.mapIndexed { idx, item ->
                val sel = if (item.isPrimarySelected && !primaryAssigned) {
                    primaryAssigned = true
                    true
                } else {
                    false
                }
                item.copy(variantIndex = idx, isPrimarySelected = sel)
            }
            finalSpans.add(normalized)
        }

        val allWps = ArrayList<Pair<Double, Double>>(splitPoints.size + 2)
        allWps.add(start)
        allWps.addAll(splitPoints)
        allWps.add(end)

        return IntermediateSpanResult(finalSpans, allWps)
    }

    private fun pointAlongPath(
        path: List<Pair<Double, Double>>,
        fromIdx: Int,
        targetDistMeters: Float,
        direction: Int
    ): Pair<Double, Double> {
        var curIdx = fromIdx
        var accumulated = 0f
        while (true) {
            val nextIdx = curIdx + direction
            if (nextIdx < 0 || nextIdx >= path.size) return path[curIdx]
            val segD = GeoMath.calculateDistance(
                path[curIdx].first, path[curIdx].second,
                path[nextIdx].first, path[nextIdx].second
            )
            if (accumulated + segD >= targetDistMeters && segD > 0.1f) {
                val frac = ((targetDistMeters - accumulated) / segD).coerceIn(0f, 1f)
                val lat = path[curIdx].first + frac * (path[nextIdx].first - path[curIdx].first)
                val lon = path[curIdx].second + frac * (path[nextIdx].second - path[curIdx].second)
                return Pair(lat, lon)
            }
            accumulated += segD
            curIdx = nextIdx
        }
    }

    private fun minDistToPath(pt: Pair<Double, Double>, path: List<Pair<Double, Double>>): Float {
        if (path.isEmpty()) return Float.MAX_VALUE
        if (path.size == 1) return GeoMath.calculateDistance(pt.first, pt.second, path[0].first, path[0].second)
        var minD = Float.MAX_VALUE
        val cosLat = kotlin.math.cos(Math.toRadians(pt.first))
        val px = pt.second * cosLat * 111320.0
        val py = pt.first * 110574.0

        for (i in 0 until path.size - 1) {
            val p1 = path[i]
            val p2 = path[i + 1]
            val x1 = p1.second * cosLat * 111320.0
            val y1 = p1.first * 110574.0
            val x2 = p2.second * cosLat * 111320.0
            val y2 = p2.first * 110574.0
            val vx = x2 - x1
            val vy = y2 - y1
            val l2 = vx * vx + vy * vy
            val d = if (l2 < 1e-4) {
                kotlin.math.hypot(px - x1, py - y1).toFloat()
            } else {
                val t = (((px - x1) * vx + (py - y1) * vy) / l2).coerceIn(0.0, 1.0)
                val projX = x1 + t * vx
                val projY = y1 + t * vy
                kotlin.math.hypot(px - projX, py - projY).toFloat()
            }
            if (d < minD) minD = d
        }
        return minD
    }

    private fun findAllIntersectionPoints(
        alternatives: List<RawAlternative>,
        start: Pair<Double, Double>,
        end: Pair<Double, Double>
    ): List<Pair<Double, Double>> {
        val endpointThreshold = 40f
        val minSplitSpacing = 45f
        val maxSplits = 14

        data class Candidate(val coord: Pair<Double, Double>, val progressDist: Float)
        val rawCandidates = ArrayList<Candidate>()

        for (a in 0 until alternatives.size - 1) {
            val path1 = alternatives[a].points
            if (path1.size < 3) continue

            val cumDist1 = FloatArray(path1.size)
            for (i in 1 until path1.size) {
                cumDist1[i] = cumDist1[i - 1] + GeoMath.calculateDistance(
                    path1[i - 1].first, path1[i - 1].second,
                    path1[i].first, path1[i].second
                )
            }

            for (b in a + 1 until alternatives.size) {
                val path2 = alternatives[b].points
                if (path2.size < 3) continue

                for (i in 1 until path1.size - 1) {
                    val p1 = path1[i]
                    val distToStart = GeoMath.calculateDistance(start.first, start.second, p1.first, p1.second)
                    val distToEnd = GeoMath.calculateDistance(end.first, end.second, p1.first, p1.second)
                    if (distToStart < endpointThreshold || distToEnd < endpointThreshold) continue

                    // 1. Check divergence / convergence at shared road junction
                    val dToPath2 = minDistToPath(p1, path2)
                    if (dToPath2 <= 14f) {
                        val pBehind = pointAlongPath(path1, i, 35f, -1)
                        val pAhead = pointAlongPath(path1, i, 35f, +1)
                        val prevDist = minDistToPath(pBehind, path2)
                        val nextDist = minDistToPath(pAhead, path2)
                        val immPrevDist = minDistToPath(path1[i - 1], path2)
                        val immNextDist = minDistToPath(path1[i + 1], path2)

                        val isDivergence = nextDist > 22f && immNextDist > 3.5f
                        val isConvergence = prevDist > 22f && immPrevDist > 3.5f
                        if (isDivergence || isConvergence) {
                            rawCandidates.add(Candidate(p1, cumDist1[i]))
                        }
                    }

                    // 2. Check geometric segment intersection
                    if (i < path1.size - 2) {
                        val p2 = path1[i + 1]
                        val minLat1 = min(p1.first, p2.first) - 0.0005
                        val maxLat1 = max(p1.first, p2.first) + 0.0005
                        val minLon1 = min(p1.second, p2.second) - 0.0005
                        val maxLon1 = max(p1.second, p2.second) + 0.0005

                        for (j in 1 until path2.size - 2) {
                            val q1 = path2[j]
                            val q2 = path2[j + 1]
                            if (max(q1.first, q2.first) < minLat1 || min(q1.first, q2.first) > maxLat1) continue
                            if (max(q1.second, q2.second) < minLon1 || min(q1.second, q2.second) > maxLon1) continue

                            val inter = segmentIntersection(p1, p2, q1, q2)
                            if (inter != null) {
                                val dS = GeoMath.calculateDistance(start.first, start.second, inter.first, inter.second)
                                val dE = GeoMath.calculateDistance(end.first, end.second, inter.first, inter.second)
                                if (dS > endpointThreshold && dE > endpointThreshold) {
                                    rawCandidates.add(Candidate(inter, cumDist1[i]))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (rawCandidates.isEmpty()) return emptyList()
        rawCandidates.sortBy { it.progressDist }

        val deduplicated = ArrayList<Pair<Double, Double>>()
        for (cand in rawCandidates) {
            val tooClose = deduplicated.any { existing ->
                GeoMath.calculateDistance(cand.coord.first, cand.coord.second, existing.first, existing.second) < minSplitSpacing
            }
            if (!tooClose) {
                deduplicated.add(cand.coord)
                if (deduplicated.size >= maxSplits) break
            }
        }

        return deduplicated
    }

    private fun segmentIntersection(
        p1: Pair<Double, Double>, p2: Pair<Double, Double>,
        q1: Pair<Double, Double>, q2: Pair<Double, Double>
    ): Pair<Double, Double>? {
        val dx1 = p2.second - p1.second
        val dy1 = p2.first - p1.first
        val dx2 = q2.second - q1.second
        val dy2 = q2.first - q1.first

        val det = dx1 * dy2 - dy1 * dx2
        if (abs(det) < 1e-12) return null

        val t1 = ((q1.second - p1.second) * dy2 - (q1.first - p1.first) * dx2) / det
        val t2 = ((q1.second - p1.second) * dy1 - (q1.first - p1.first) * dx1) / det

        if (t1 in 0.05..0.95 && t2 in 0.05..0.95) {
            val lat = p1.first + t1 * dy1
            val lon = p1.second + t1 * dx1
            return Pair(lat, lon)
        }
        return null
    }
}
