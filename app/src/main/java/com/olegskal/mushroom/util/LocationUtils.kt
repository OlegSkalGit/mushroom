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
     * Attempts to retrieve the best available last known location across GPS, Network, and Passive providers.
     */
    fun getLastKnownLocationCascade(lm: LocationManager): Location? {
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }
}
