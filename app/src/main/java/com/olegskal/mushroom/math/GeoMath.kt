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
        isRecordingTrack: Boolean = false,
        recordedDistanceMeters: Float = 0f,
        recordedDurationSec: Long = 0L
    ): ProcessedLocationMetrics {
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 40f

        return ProcessedLocationMetrics(
            location = location,
            speedKmh = speedKmh,
            isAccuracyWeak = isAccuracyWeak,
            isGpsDisabled = isGpsDisabled,
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
    val isGpsDisabled: Boolean,
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
    fun processLocation(location: Location): TrajectoryResult {
        val isWeak = location.hasAccuracy() && location.accuracy > 50f
        if (isWeak) {
            return TrajectoryResult(
                averageSpeedKmh = 0f,
                trajectoryBearing = location.bearing
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

        if (buffer.size < 2) {
            return TrajectoryResult(
                averageSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
                trajectoryBearing = location.bearing
            )
        }

        val first = buffer.first()
        val last = buffer.last()
        val dist = GeoMath.calculateDistance(first, last.latitude, last.longitude)
        val timeSec = maxOf(1.0, (last.time - first.time) / 1000.0)
        val speedKmh = ((dist / timeSec) * 3.6).toFloat()
        val bearing = GeoMath.calculateDistanceAndBearing(first.latitude, first.longitude, last.latitude, last.longitude)[1]

        val isStationary = dist < 3.0f && speedKmh < 1.0f

        return TrajectoryResult(
            averageSpeedKmh = if (isStationary) 0f else speedKmh,
            trajectoryBearing = if (bearing < 0) bearing + 360f else bearing,
            isStationary = isStationary
        )
    }
}

data class TrajectoryResult(
    val averageSpeedKmh: Float,
    val trajectoryBearing: Float,
    val isStationary: Boolean = false
)
