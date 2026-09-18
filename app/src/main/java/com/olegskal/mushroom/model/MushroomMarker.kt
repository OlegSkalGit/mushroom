package com.olegskal.mushroom.model

import org.json.JSONObject

data class MushroomMarker(
    val id: Long = System.currentTimeMillis(),
    var name: String,
    var type: String, // e.g., "Porcini", "Chanterelle", "Boletus", "Honey agaric", "Suillus", "Car", "Point"
    val lat: Double,
    val lon: Double,
    val altitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    var note: String = "",
    var color: Int = 0xFFFF9800.toInt(),
    var isVisible: Boolean = true
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type)
            put("lat", lat)
            put("lon", lon)
            put("altitude", altitude)
            put("timestamp", timestamp)
            put("note", note)
            put("color", color)
            put("isVisible", isVisible)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MushroomMarker {
            return MushroomMarker(
                id = json.optLong("id", System.currentTimeMillis()),
                name = json.optString("name", "Marker"),
                type = json.optString("type", "Mushroom"),
                lat = json.optDouble("lat", 0.0),
                lon = json.optDouble("lon", 0.0),
                altitude = json.optDouble("altitude", 0.0),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                note = json.optString("note", ""),
                color = json.optInt("color", 0xFFFF9800.toInt()),
                isVisible = json.optBoolean("isVisible", true)
            )
        }
    }
}
