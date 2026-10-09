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
    val spanIndex: Int,
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

    val profilesDir: File
        get() = File(MushroomStorageManager.navigationDir, "profiles").apply { if (!exists()) mkdirs() }

    fun ensureProfilesExtracted(context: Context) {
        try {
            val assetNames = context.assets.list("brouter") ?: emptyArray()
            for (name in assetNames) {
                val dest = File(profilesDir, name)
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
        isUk: Boolean
    ): RouteCalculationResult {
        if (waypoints.size < 2) return RouteCalculationResult(emptyList(), waypoints)

        OsmTileEngine.appContext?.let { ensureProfilesExtracted(it) }

        val allSpans = mutableListOf<Pair<Int, List<RawAlternative>>>()
        val allWaypoints = mutableListOf<Pair<Double, Double>>()
        allWaypoints.add(waypoints.first())

        for (i in 0 until waypoints.size - 1) {
            val start = waypoints[i]
            val end = waypoints[i + 1]

            val spanResult = processSpanWithIntersectionSplits(start, end, transport, isUk, depth = 0)
            for (span in spanResult.spans) {
                allSpans.add(Pair(i, span))
            }
            for (p in spanResult.waypoints.drop(1)) {
                allWaypoints.add(p)
            }
        }

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
                        isSelected = (vIdx == 0)
                    )
                )
            }
        }

        return RouteCalculationResult(resultSegments, allWaypoints)
    }

    private data class RawAlternative(
        val variantIndex: Int,
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Float,
        val advantage: String
    )

    private data class ProfileOption(
        val filename: String,
        val altIdx: Int,
        val titleUk: String,
        val titleEn: String,
        val params: Map<String, String> = emptyMap()
    )

    private fun calculateRawAlternatives(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        transport: TransportType,
        isUk: Boolean
    ): List<RawAlternative> {
        val profileOptions = when (transport) {
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
                    mapOf("path_preference" to "20.0", "consider_forest" to "true", "add_beeline" to "1", "waypointCatchingRange" to "2500")
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

        val alternatives = mutableListOf<RawAlternative>()

        for (idx in profileOptions.indices) {
            val opt = profileOptions[idx]
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
                    AppLogger.log(TAG, "calculateRawAlternatives", false, "Routing error with ${opt.filename}: ${e.message}")
                } finally {
                    rc?.let {
                        try { btools.router.ProfileCache.releaseProfile(it) } catch (_: Exception) {}
                    }
                }
            }

            if (computedPoints != null && computedPoints.size >= 2) {
                val isUnpavedOpt = opt.params["avoid_unpaved"] == "0"
                val shouldAdd = if (isUnpavedOpt && alternatives.isNotEmpty()) {
                    val primaryDist = alternatives.first().distanceMeters
                    computedDist < primaryDist - 25f || alternatives.first().points.size <= 2
                } else {
                    true
                }

                if (shouldAdd) {
                    val km = computedDist / 1000f
                    val unit = if (isUk) "км" else "km"
                    val title = if (isUk) opt.titleUk else opt.titleEn
                    val fullAdvantage = "$title (${String.format(Locale.US, "%.2f", km)} $unit)"
                    alternatives.add(RawAlternative(idx, computedPoints, computedDist, fullAdvantage))
                }
            }
        }

        // If no routed paths could be found (off-grid point or no rd5 data), use direct straight line
        if (alternatives.isEmpty()) {
            val dist = GeoMath.calculateDistance(startLat, startLon, endLat, endLon)
            val km = dist / 1000f
            val unit = if (isUk) "км" else "km"
            val title = if (isUk) "Пряма лінія" else "Direct line"
            val advantage = "$title (${String.format(Locale.US, "%.2f", km)} $unit)"
            alternatives.add(RawAlternative(0, listOf(Pair(startLat, startLon), Pair(endLat, endLon)), dist, advantage))
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

            var isDuplicate = false
            for (existing in valid) {
                val distDiffRatio = abs(alt.distanceMeters - existing.distanceMeters) / max(1f, existing.distanceMeters)
                if (distDiffRatio < 0.015f) {
                    var maxDivergence = 0f
                    val sampleCount = 10
                    for (s in 1 until sampleCount) {
                        val pt = alt.points[(alt.points.size * s) / sampleCount]
                        val minD = existing.points.minOf { GeoMath.calculateDistance(pt.first, pt.second, it.first, it.second) }
                        if (minD > maxDivergence) maxDivergence = minD
                    }
                    if (maxDivergence < 30f) {
                        isDuplicate = true
                        break
                    }
                }
            }
            if (!isDuplicate) {
                valid.add(alt)
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
        depth: Int
    ): IntermediateSpanResult {
        val rawAlternatives = calculateRawAlternatives(start.first, start.second, end.first, end.second, transport, isUk)
        val filtered = filterDuplicates(rawAlternatives)

        if (depth >= 2 || filtered.size < 2) {
            return IntermediateSpanResult(listOf(filtered), listOf(start, end))
        }

        val interCoord = findFirstIntersection(filtered, start, end)
        if (interCoord == null) {
            return IntermediateSpanResult(listOf(filtered), listOf(start, end))
        }

        // Split existing alternatives at intersection point to preserve both alternative branches
        val (firstSpanAlts, secondSpanAlts) = splitAlternativesAtIntersection(filtered, interCoord, end, isUk)

        val firstHalf = if (depth < 1 && firstSpanAlts.size >= 2) {
            val subInter = findFirstIntersection(firstSpanAlts, start, interCoord)
            if (subInter != null) {
                processSpanWithIntersectionSplits(start, interCoord, transport, isUk, depth + 1)
            } else {
                IntermediateSpanResult(listOf(firstSpanAlts), listOf(start, interCoord))
            }
        } else {
            IntermediateSpanResult(listOf(firstSpanAlts), listOf(start, interCoord))
        }

        val secondHalf = if (depth < 1 && secondSpanAlts.size >= 2) {
            val subInter = findFirstIntersection(secondSpanAlts, interCoord, end)
            if (subInter != null) {
                processSpanWithIntersectionSplits(interCoord, end, transport, isUk, depth + 1)
            } else {
                IntermediateSpanResult(listOf(secondSpanAlts), listOf(interCoord, end))
            }
        } else {
            IntermediateSpanResult(listOf(secondSpanAlts), listOf(interCoord, end))
        }

        val combinedSpans = firstHalf.spans + secondHalf.spans
        val combinedWaypoints = firstHalf.waypoints + secondHalf.waypoints.drop(1)

        return IntermediateSpanResult(combinedSpans, combinedWaypoints)
    }

    private fun splitAlternativesAtIntersection(
        alternatives: List<RawAlternative>,
        interCoord: Pair<Double, Double>,
        end: Pair<Double, Double>,
        isUk: Boolean
    ): Pair<List<RawAlternative>, List<RawAlternative>> {
        val unit = if (isUk) "км" else "km"
        val firstHalfList = mutableListOf<RawAlternative>()
        val secondHalfList = mutableListOf<RawAlternative>()

        for (alt in alternatives) {
            val pts = alt.points
            if (pts.size < 2) continue

            var closestIdx = 0
            var minDist = Float.MAX_VALUE
            for (i in pts.indices) {
                val d = GeoMath.calculateDistance(pts[i].first, pts[i].second, interCoord.first, interCoord.second)
                if (d < minDist) {
                    minDist = d
                    closestIdx = i
                }
            }

            val headPts = mutableListOf<Pair<Double, Double>>()
            headPts.addAll(pts.subList(0, closestIdx + 1))
            if (headPts.isEmpty() || GeoMath.calculateDistance(headPts.last().first, headPts.last().second, interCoord.first, interCoord.second) > 2f) {
                headPts.add(interCoord)
            }

            val tailPts = mutableListOf<Pair<Double, Double>>()
            tailPts.add(interCoord)
            if (closestIdx + 1 < pts.size) {
                tailPts.addAll(pts.subList(closestIdx + 1, pts.size))
            } else {
                tailPts.add(end)
            }

            val headDist = computePolylineDistance(headPts)
            val headKm = headDist / 1000f
            val baseTitle = alt.advantage.substringBefore(" (")
            val headAdvantage = "$baseTitle (${String.format(Locale.US, "%.2f", headKm)} $unit)"
            firstHalfList.add(RawAlternative(alt.variantIndex, headPts, headDist, headAdvantage))

            val tailDist = computePolylineDistance(tailPts)
            val tailKm = tailDist / 1000f
            val tailAdvantage = "$baseTitle (${String.format(Locale.US, "%.2f", tailKm)} $unit)"
            secondHalfList.add(RawAlternative(alt.variantIndex, tailPts, tailDist, tailAdvantage))
        }

        val dedupFirst = filterDuplicates(firstHalfList)
        val dedupSecond = filterDuplicates(secondHalfList)

        return Pair(dedupFirst, dedupSecond)
    }

    private fun findFirstIntersection(
        alternatives: List<RawAlternative>,
        start: Pair<Double, Double>,
        end: Pair<Double, Double>
    ): Pair<Double, Double>? {
        val startThreshold = 40f
        val endThreshold = 40f

        for (a in 0 until alternatives.size - 1) {
            val path1 = alternatives[a].points
            for (b in a + 1 until alternatives.size) {
                val path2 = alternatives[b].points

                for (i in 1 until path1.size - 2) {
                    val p1 = path1[i]
                    val p2 = path1[i + 1]
                    val distToStart1 = GeoMath.calculateDistance(start.first, start.second, p1.first, p1.second)
                    val distToEnd1 = GeoMath.calculateDistance(end.first, end.second, p2.first, p2.second)
                    if (distToStart1 < startThreshold || distToEnd1 < endThreshold) continue

                    for (j in 1 until path2.size - 2) {
                        val q1 = path2[j]
                        val q2 = path2[j + 1]

                        // 1. Line segment intersection
                        val inter = segmentIntersection(p1, p2, q1, q2)
                        if (inter != null) {
                            val dStart = GeoMath.calculateDistance(start.first, start.second, inter.first, inter.second)
                            val dEnd = GeoMath.calculateDistance(end.first, end.second, inter.first, inter.second)
                            if (dStart > startThreshold && dEnd > endThreshold) {
                                return inter
                            }
                        }

                        // 2. Shared road junction node
                        val nodeDist = GeoMath.calculateDistance(p1.first, p1.second, q1.first, q1.second)
                        if (nodeDist < 12f && distToStart1 > startThreshold && distToEnd1 > endThreshold) {
                            val prevDist = GeoMath.calculateDistance(path1[i - 1].first, path1[i - 1].second, path2[j - 1].first, path2[j - 1].second)
                            val nextDist = GeoMath.calculateDistance(path1[i + 1].first, path1[i + 1].second, path2[j + 1].first, path2[j + 1].second)
                            if (prevDist > 20f || nextDist > 20f) {
                                return p1
                            }
                        }
                    }
                }
            }
        }
        return null
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
