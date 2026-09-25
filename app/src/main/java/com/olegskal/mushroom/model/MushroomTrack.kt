package com.olegskal.mushroom.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0,
    val time: Long = System.currentTimeMillis(),
    val speed: Float = 0f
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("alt", alt)
            put("time", time)
            put("speed", speed.toDouble())
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TrackPoint {
            return TrackPoint(
                lat = json.optDouble("lat", 0.0),
                lon = json.optDouble("lon", 0.0),
                alt = json.optDouble("alt", 0.0),
                time = json.optLong("time", 0L),
                speed = json.optDouble("speed", 0.0).toFloat()
            )
        }
    }
}

data class MushroomTrack(
    val id: Long = System.currentTimeMillis(),
    var title: String,
    val startTime: Long,
    var endTime: Long = startTime,
    var distanceMeters: Float = 0f,
    var durationSec: Long = 0L,
    val points: MutableList<TrackPoint> = ArrayList(),
    var color: Int = 0xFF2196F3.toInt(),
    var isVisible: Boolean = true
) {
    fun toJson(): JSONObject {
        val ptsArr = JSONArray()
        for (p in points) {
            ptsArr.put(p.toJson())
        }
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("startTime", startTime)
            put("endTime", endTime)
            put("distanceMeters", distanceMeters.toDouble())
            put("durationSec", durationSec)
            put("color", color)
            put("isVisible", isVisible)
            put("points", ptsArr)
        }
    }

    fun toGpx(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MushroomApp\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(title.replace("<", "&lt;").replace(">", "&gt;")).append("</name>\n")
        sb.append("    <time>").append(sdf.format(Date(startTime))).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(title.replace("<", "&lt;").replace(">", "&gt;")).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (pt in points) {
            sb.append("      <trkpt lat=\"").append(pt.lat).append("\" lon=\"").append(pt.lon).append("\">\n")
            if (pt.alt != 0.0) {
                sb.append("        <ele>").append(pt.alt).append("</ele>\n")
            }
            if (pt.time > 0L) {
                sb.append("        <time>").append(sdf.format(Date(pt.time))).append("</time>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    companion object {
        fun fromJson(json: JSONObject): MushroomTrack {
            val pts = ArrayList<TrackPoint>()
            val arr = json.optJSONArray("points")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    pts.add(TrackPoint.fromJson(obj))
                }
            }
            return MushroomTrack(
                id = json.optLong("id", System.currentTimeMillis()),
                title = json.optString("title", "Track"),
                startTime = json.optLong("startTime", System.currentTimeMillis()),
                endTime = json.optLong("endTime", System.currentTimeMillis()),
                distanceMeters = json.optDouble("distanceMeters", 0.0).toFloat(),
                durationSec = json.optLong("durationSec", 0L),
                points = pts,
                color = json.optInt("color", 0xFF2196F3.toInt()),
                isVisible = json.optBoolean("isVisible", true)
            )
        }
    }
}
