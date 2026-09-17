package com.olegskal.mushroom.math

import android.location.Location
import kotlin.math.*

object GeoMath {

    fun angleDifference(bearing1: Float, bearing2: Float): Float {
        var diff = (bearing1 - bearing2) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    private val distanceResults = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(1)
    }

    private val distanceBearingResults = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(2)
    }

    fun calculateDistance(loc: Location, lat: Double, lon: Double): Float {
        val results = distanceResults.get() ?: FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
        return results[0]
    }

    fun calculateDistance(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val results = distanceResults.get() ?: FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, results)
        return results[0]
    }

    fun calculateDistanceAndBearing(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        outResults: FloatArray? = null
    ): FloatArray {
        val res = outResults ?: (distanceBearingResults.get() ?: FloatArray(2))
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, res)
        return res
    }

    fun evaluateLocationData(
        location: Location,
        speedKmh: Float,
        trajectoryBearing: Float = location.bearing,
        compassHeading: Float = 0f,
        isStationary: Boolean = false,
        isGpsDisabled: Boolean = false,
        isDeepSleep: Boolean = false,
        isMotionSensorActive: Boolean = false,
        isRecordingTrack: Boolean = false,
        recordedDistanceMeters: Float = 0f,
        recordedDurationSec: Long = 0L,
        notificationOverride: String? = null
    ): ProcessedLocationMetrics {
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 40f
        val accInt = if (location.hasAccuracy()) location.accuracy.toInt() else 0

        val gpsStatusStr = when {
            isGpsDisabled -> "GPS вимкнено в налаштуваннях"
            isDeepSleep -> if (isMotionSensorActive) "Режим сну (датчик руху)" else "Режим сну (акселерометр)"
            location.latitude == 0.0 && location.longitude == 0.0 -> "Пошук супутників..."
            isAccuracyWeak -> "Слабкий сигнал (±${accInt}м)"
            location.hasAccuracy() -> "GPS: OK (±${accInt}м)"
            else -> "GPS: Активний"
        }

        val defaultNotif = when {
            isGpsDisabled -> "GPS вимкнено"
            isDeepSleep -> "Грибник у режимі збереження заряду"
            isRecordingTrack -> {
                val kmStr = String.format(java.util.Locale.US, "%.2f км", recordedDistanceMeters / 1000f)
                val m = recordedDurationSec / 60
                val s = recordedDurationSec % 60
                val timeStr = String.format(java.util.Locale.US, "%02d:%02d", m, s)
                "Запис треку: $kmStr | $timeStr"
            }
            isAccuracyWeak -> "Слабкий сигнал GPS (±${accInt}м)"
            else -> "Грибник активний"
        }

        return ProcessedLocationMetrics(
            location = location,
            speedKmh = speedKmh,
            isAccuracyWeak = isAccuracyWeak,
            gpsStatusStr = gpsStatusStr,
            notificationText = notificationOverride ?: defaultNotif,
            isGpsDisabled = isGpsDisabled,
            isDeepSleep = isDeepSleep,
            trajectoryBearing = trajectoryBearing,
            compassHeading = compassHeading,
            isStationary = isStationary,
            isRecordingTrack = isRecordingTrack,
            recordedDistanceMeters = recordedDistanceMeters,
            recordedDurationSec = recordedDurationSec
        )
    }
}

data class ProcessedLocationMetrics(
    val location: Location,
    val speedKmh: Float,
    val isAccuracyWeak: Boolean,
    val gpsStatusStr: String,
    val notificationText: String,
    val isGpsDisabled: Boolean,
    val isDeepSleep: Boolean,
    val trajectoryBearing: Float,
    val compassHeading: Float,
    val isStationary: Boolean,
    val isRecordingTrack: Boolean = false,
    val recordedDistanceMeters: Float = 0f,
    val recordedDurationSec: Long = 0L
)

class TrajectoryFilter(
    private val maxBufferSize: Int = 10
) {
    private val buffer = java.util.ArrayDeque<Location>()
    private var lastBufferPushTimeMs: Long = 0L

    @Synchronized
    fun reset() {
        buffer.clear()
        lastBufferPushTimeMs = 0L
    }

    @Synchronized
    fun getPoints(): List<Location> = buffer.toList()

    @Synchronized
    fun processLocation(location: Location): TrajectoryResult {
        val isWeak = location.hasAccuracy() && location.accuracy > 50f
        if (isWeak) {
            return TrajectoryResult(
                isValid = false,
                isAccuracyWeak = true,
                points = buffer.toList(),
                averageSpeedKmh = 0f,
                trajectoryBearing = location.bearing,
                projectedLocation = location
            )
        }

        val locTime = if (location.time > 0L) location.time else System.currentTimeMillis()
        val timeDiff = locTime - lastBufferPushTimeMs
        if (timeDiff >= 1000L || timeDiff <= 0L || buffer.isEmpty()) {
            if (buffer.size >= maxBufferSize) {
                buffer.removeFirst()
            }
            buffer.addLast(location)
            lastBufferPushTimeMs = locTime
        }

        val pts = buffer.toList()
        if (pts.size < 2) {
            return TrajectoryResult(
                isValid = true,
                isAccuracyWeak = false,
                points = pts,
                averageSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
                trajectoryBearing = location.bearing,
                projectedLocation = location
            )
        }

        val first = pts.first()
        val last = pts.last()
        val dist = GeoMath.calculateDistance(first, last.latitude, last.longitude)
        val timeSec = maxOf(1.0, (last.time - first.time) / 1000.0)
        val speedKmh = ((dist / timeSec) * 3.6).toFloat()
        val bearing = GeoMath.calculateDistanceAndBearing(first.latitude, first.longitude, last.latitude, last.longitude)[1]

        val isStationary = dist < 3.0f && speedKmh < 1.0f

        return TrajectoryResult(
            isValid = true,
            isAccuracyWeak = false,
            points = pts,
            averageSpeedKmh = if (isStationary) 0f else speedKmh,
            trajectoryBearing = if (bearing < 0) bearing + 360f else bearing,
            projectedLocation = last,
            isStationary = isStationary
        )
    }
}

data class TrajectoryResult(
    val isValid: Boolean,
    val isAccuracyWeak: Boolean,
    val points: List<Location>,
    val averageSpeedKmh: Float,
    val trajectoryBearing: Float,
    val projectedLocation: Location? = null,
    val isStationary: Boolean = false
)
