package com.olegskal.mushroom.util

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

/**
 * Unified utility functions for GPS and Location operations across the app.
 */
object LocationUtils {

    const val MAX_FRESH_LOCATION_AGE_MS = 60_000L
    const val MAX_LIVE_FIX_AGE_MS = 20_000L

    fun getLocationAgeMs(location: Location): Long {
        val elapsedNs = location.elapsedRealtimeNanos
        if (elapsedNs > 0L) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedNs) / 1_000_000L
            if (ageMs >= -5_000L) return ageMs.coerceAtLeast(0L)
        }
        val nowMs = System.currentTimeMillis()
        if (location.time > 0L) {
            val ageMs = nowMs - location.time
            if (ageMs >= -5_000L) return ageMs.coerceAtLeast(0L)
        }
        return Long.MAX_VALUE
    }

    fun isFreshLocation(location: Location?, maxAgeMs: Long = MAX_FRESH_LOCATION_AGE_MS): Boolean {
        return location != null && getLocationAgeMs(location) <= maxAgeMs
    }

    /**
     * Checks whether system GPS / location is disabled on the device.
     */
    fun isGpsDisabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return isGpsDisabled(lm)
    }


    /**
     * Checks whether system GPS / location is disabled via LocationManager instance.
     */
    fun isGpsDisabled(lm: LocationManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !lm.isLocationEnabled || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } else {
                !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
        } catch (e: Exception) {
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    fun getUserLocation(context: Context): Location? {
        val serviceLoc = com.olegskal.mushroom.service.MushroomTrackingService.currentServiceLocation
        if (isFreshLocation(serviceLoc)) return serviceLoc
        return getLastKnownLocationCascade(context)
    }

    /**
     * Attempts to retrieve the best available fresh last known location across providers.
     */
    fun getLastKnownLocationCascade(
        context: Context,
        maxAgeMs: Long = MAX_FRESH_LOCATION_AGE_MS
    ): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return getLastKnownLocationCascade(lm, maxAgeMs)
    }

    /**
     * Attempts to retrieve the best available fresh last known location across Fused, GPS, Network, and Passive providers.
     * Returns null if no fix younger than maxAgeMs is available.
     */
    fun getLastKnownLocationCascade(
        lm: LocationManager,
        maxAgeMs: Long = MAX_FRESH_LOCATION_AGE_MS
    ): Location? {
        return try {
            val providers = mutableListOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                providers.add(0, LocationManager.FUSED_PROVIDER)
            }

            val fresh = providers.mapNotNull { p ->
                try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            }.filter { isFreshLocation(it, maxAgeMs) }

            fresh.minByOrNull { if (it.hasAccuracy()) it.accuracy else 1000f }
        } catch (e: Exception) {
            null
        }
    }
}
