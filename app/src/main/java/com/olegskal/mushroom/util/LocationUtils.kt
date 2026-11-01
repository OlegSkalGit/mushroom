package com.olegskal.mushroom.util

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build

/**
 * Unified utility functions for GPS and Location operations across the app.
 */
object LocationUtils {

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
        return com.olegskal.mushroom.service.MushroomTrackingService.currentServiceLocation
            ?: getLastKnownLocationCascade(context)
    }

    /**
     * Attempts to retrieve the best available last known location across GPS, Network, and Passive providers.
     */
    fun getLastKnownLocationCascade(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return getLastKnownLocationCascade(lm)
    }

    /**
     * Attempts to retrieve the best available last known location across Fused, GPS, Network, and Passive providers.
     * Evaluates freshness and accuracy to avoid returning stale fixes.
     */
    fun getLastKnownLocationCascade(lm: LocationManager): Location? {
        return try {
            val providers = mutableListOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                providers.add(0, LocationManager.FUSED_PROVIDER)
            }

            val list = providers.mapNotNull { p ->
                try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            }
            if (list.isEmpty()) return null

            val now = System.currentTimeMillis()
            // Fresh fixes (< 2 min): pick the most accurate
            val fresh = list.filter { (now - it.time) < 120_000L }
            if (fresh.isNotEmpty()) {
                fresh.minByOrNull { if (it.hasAccuracy()) it.accuracy else 1000f }
            } else {
                // Otherwise pick the newest fix
                list.maxByOrNull { it.time }
            }
        } catch (e: Exception) {
            null
        }
    }
}
