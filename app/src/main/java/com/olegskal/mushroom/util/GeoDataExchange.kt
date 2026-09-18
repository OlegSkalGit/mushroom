package com.olegskal.mushroom.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import androidx.core.content.FileProvider
import com.olegskal.mushroom.math.GeoMath
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.model.TrackPoint
import com.olegskal.mushroom.storage.MushroomStorageManager
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

object GeoDataExchange {

    private val COORD_DECIMAL_PATTERN = Pattern.compile(
        "(-?\\d{1,2}\\.\\d+)[,\\s;]+(-?\\d{1,3}\\.\\d+)"
    )

    private val COORD_COMMA_DECIMAL_PATTERN = Pattern.compile(
        "(-?\\d{1,2},\\d+)[;\\s]+(-?\\d{1,3},\\d+)"
    )

    private val COORD_PATTERN = Pattern.compile(
        "(-?\\d{1,2}(?:\\.\\d+)?)[,\\s;]+(-?\\d{1,3}(?:\\.\\d+)?)"
    )

    private val GEO_URI_PATTERN = Pattern.compile(
        "geo:(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"
    )

    private val MAPS_URL_PATTERN = Pattern.compile(
        "(?:q=|@|ll=)(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"
    )

    /**
     * Parses coordinates (lat, lon) from any arbitrary text string,
     * including formats like:
     * - "50.4501, 30.5234"
     * - "50.4501 30.5234"
     * - "50,4501; 30,5234"
     * - "geo:50.4501,30.5234"
     * - "https://maps.google.com/?q=50.4501,30.5234"
     * - "https://www.google.com/maps/@50.4501,30.5234,17z"
     */
    fun parseCoordinates(rawText: String?): Pair<Double, Double>? {
        if (rawText.isNullOrBlank()) return null
        val text = rawText.trim()

        // 1. Check geo: URI
        val geoMatcher = GEO_URI_PATTERN.matcher(text)
        if (geoMatcher.find()) {
            val lat = geoMatcher.group(1)?.toDoubleOrNull()
            val lon = geoMatcher.group(2)?.toDoubleOrNull()
            if (isValidCoord(lat, lon)) return Pair(lat!!, lon!!)
        }

        // 2. Check Google Maps / Apple / OSM URL pattern
        val urlMatcher = MAPS_URL_PATTERN.matcher(text)
        if (urlMatcher.find()) {
            val lat = urlMatcher.group(1)?.toDoubleOrNull()
            val lon = urlMatcher.group(2)?.toDoubleOrNull()
            if (isValidCoord(lat, lon)) return Pair(lat!!, lon!!)
        }

        // 3. Check decimal point coordinates (e.g. 50.4501, 30.5234)
        val decMatcher = COORD_DECIMAL_PATTERN.matcher(text)
        if (decMatcher.find()) {
            val lat = decMatcher.group(1)?.toDoubleOrNull()
            val lon = decMatcher.group(2)?.toDoubleOrNull()
            if (isValidCoord(lat, lon)) return Pair(lat!!, lon!!)
        }

        // 4. Check comma decimal coordinates (e.g. 50,4501; 30,5234)
        val commaMatcher = COORD_COMMA_DECIMAL_PATTERN.matcher(text)
        if (commaMatcher.find()) {
            val lat = commaMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
            val lon = commaMatcher.group(2)?.replace(',', '.')?.toDoubleOrNull()
            if (isValidCoord(lat, lon)) return Pair(lat!!, lon!!)
        }

        // 5. Check generic decimal coordinates
        val matcher = COORD_PATTERN.matcher(text)
        while (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull()
            val lon = matcher.group(2)?.toDoubleOrNull()
            if (isValidCoord(lat, lon)) {
                return Pair(lat!!, lon!!)
            }
        }
        return null
    }

    private fun isValidCoord(lat: Double?, lon: Double?): Boolean {
        return lat != null && lon != null &&
                lat >= -90.0 && lat <= 90.0 &&
                lon >= -180.0 && lon <= 180.0 &&
                (lat != 0.0 || lon != 0.0)
    }

    fun getClipboardText(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(context)?.toString()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun shareMarker(activity: Activity, marker: MushroomMarker) {
        try {
            val text = "${marker.name} (${marker.type})\n" +
                    "Coordinates: ${String.format(Locale.US, "%.5f, %.5f", marker.lat, marker.lon)}\n" +
                    "https://maps.google.com/?q=${marker.lat},${marker.lon}"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Mushroom marker: ${marker.name}")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(intent, "Share Marker"))
            AppLogger.log("GeoDataExchange", "shareMarker", true, "Shared marker: ${marker.name}")
        } catch (e: Exception) {
            AppLogger.log("GeoDataExchange", "shareMarker", false, "Failed to share marker: ${e.message}")
        }
    }

    fun shareTrackGpx(activity: Activity, track: MushroomTrack) {
        try {
            val gpxFile = File(MushroomStorageManager.tracksDir, "track_${track.id}.gpx")
            if (!gpxFile.exists() || gpxFile.length() == 0L) {
                MushroomStorageManager.saveTrackToGpx(track)
            }

            val uri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                gpxFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GPX Track: ${track.title}")
                putExtra(Intent.EXTRA_TEXT, "Track \"${track.title}\" (length: ${String.format(Locale.US, "%.2f", track.distanceMeters / 1000f)} km)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share GPX Track"))
            AppLogger.log("GeoDataExchange", "shareTrackGpx", true, "Shared track: ${track.id} (${gpxFile.name})")
        } catch (e: Exception) {
            AppLogger.log("GeoDataExchange", "shareTrackGpx", false, "Failed to share GPX track: ${e.message}")
        }
    }

    /**
     * 100% native lightweight GPX XML parser.
     * Extracts track points, timestamps, elevation, and builds a MushroomTrack.
     */
    fun parseGpx(inputStream: InputStream, defaultTitle: String = "Imported Track"): MushroomTrack? {
        val points = ArrayList<TrackPoint>()
        var trackName = defaultTitle
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var currentLat: Double? = null
            var currentLon: Double? = null
            var currentEle: Double = 0.0
            var currentTime: Long = 0L
            var currentTag = ""
            var insideTrk = false
            var insideTrkpt = false
            var insideWpt = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase(Locale.US)
                        when (currentTag) {
                            "trk" -> insideTrk = true
                            "trkpt", "wpt" -> {
                                insideTrkpt = (currentTag == "trkpt")
                                insideWpt = (currentTag == "wpt")
                                currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                currentEle = 0.0
                                currentTime = 0L
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim()
                        if (!text.isNullOrEmpty()) {
                            when (currentTag) {
                                "name" -> {
                                    if (insideTrk && trackName == defaultTitle) {
                                        trackName = text
                                    }
                                }
                                "ele" -> {
                                    if (insideTrkpt || insideWpt) {
                                        currentEle = text.toDoubleOrNull() ?: 0.0
                                    }
                                }
                                "time" -> {
                                    if (insideTrkpt || insideWpt) {
                                        try {
                                            // Normalize ISO 8601 string
                                            val clean = text.replace("Z", "").substringBefore(".")
                                            val parsed = sdf.parse(clean)
                                            if (parsed != null) currentTime = parsed.time
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase(Locale.US)
                        if (tag == "trkpt" || tag == "wpt") {
                            if (currentLat != null && currentLon != null && isValidCoord(currentLat, currentLon)) {
                                val t = if (currentTime > 0L) currentTime else System.currentTimeMillis()
                                points.add(TrackPoint(currentLat, currentLon, currentEle, t, 0f))
                            }
                            insideTrkpt = false
                            insideWpt = false
                        } else if (tag == "trk") {
                            insideTrk = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            AppLogger.log("GeoDataExchange", "parseGpx", false, "GPX parsing error: ${e.message}")
        }

        if (points.isEmpty()) return null

        // Calculate total distance and duration
        var totalDist = 0f
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            totalDist += GeoMath.calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon)
        }

        val startTime = points.first().time
        val endTime = points.last().time
        val durationSec = maxOf(1L, (endTime - startTime) / 1000L)
        val trackId = System.currentTimeMillis()

        return MushroomTrack(
            id = trackId,
            title = trackName,
            startTime = startTime,
            endTime = endTime,
            distanceMeters = totalDist,
            durationSec = durationSec,
            points = points,
            color = 0xFF2196F3.toInt(), // Nice Blue for imported tracks
            isVisible = true
        )
    }
}
