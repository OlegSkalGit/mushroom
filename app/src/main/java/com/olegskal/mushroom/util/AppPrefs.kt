package com.olegskal.mushroom.util

import android.content.Context
import android.content.SharedPreferences
import com.olegskal.mushroom.storage.MushroomStorageManager
import org.json.JSONObject

/**
 * Centralized utility for managing application settings, mirrored in
 * SharedPreferences and /sdcard/mushroom/settings/settings.json.
 */
object AppPrefs {
    private const val PREFS_NAME = "mushroom_prefs"

    const val KEY_USER_STOPPED = "user_stopped"
    const val KEY_DEBUG_MODE = "debug_mode"
    const val KEY_LOGGING_ENABLED = "pref_logging_enabled"
    const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
    const val KEY_MAP_ZOOM = "map_zoom"
    const val KEY_MAP_LAT = "map_lat"
    const val KEY_MAP_LON = "map_lon"
    const val KEY_MAP_BEARING = "map_bearing"
    const val KEY_FOLLOW_USER = "follow_user"
    const val KEY_IS_RECORDING = "is_recording"
    const val KEY_ACTIVE_TRACK_ID = "active_track_id"
    const val KEY_HEADING_UP = "heading_up"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun syncToExternalStorage(context: Context) {
        try {
            val p = getPrefs(context)
            val json = JSONObject().apply {
                put(KEY_USER_STOPPED, p.getBoolean(KEY_USER_STOPPED, false))
                put(KEY_DEBUG_MODE, p.getBoolean(KEY_DEBUG_MODE, false))
                put(KEY_LOGGING_ENABLED, p.getBoolean(KEY_LOGGING_ENABLED, false))
                put(KEY_LAST_UPDATE_CHECK, p.getLong(KEY_LAST_UPDATE_CHECK, 0L))
                put(KEY_MAP_ZOOM, getMapZoom(context).toDouble())
                put(KEY_MAP_LAT, getMapLat(context))
                put(KEY_MAP_LON, getMapLon(context))
                put(KEY_MAP_BEARING, getMapBearing(context).toDouble())
                put(KEY_FOLLOW_USER, p.getBoolean(KEY_FOLLOW_USER, false))
                put(KEY_HEADING_UP, p.getBoolean(KEY_HEADING_UP, false))
            }
            MushroomStorageManager.saveSettingsJson(json)
        } catch (_: Exception) {}
    }

    fun restoreFromExternalStorage(context: Context) {
        try {
            val json = MushroomStorageManager.loadSettingsJson() ?: return
            val editor = getPrefs(context).edit()
            if (json.has(KEY_USER_STOPPED)) editor.putBoolean(KEY_USER_STOPPED, json.getBoolean(KEY_USER_STOPPED))
            if (json.has(KEY_DEBUG_MODE)) editor.putBoolean(KEY_DEBUG_MODE, json.getBoolean(KEY_DEBUG_MODE))
            if (json.has(KEY_LOGGING_ENABLED)) editor.putBoolean(KEY_LOGGING_ENABLED, json.getBoolean(KEY_LOGGING_ENABLED))
            if (json.has(KEY_LAST_UPDATE_CHECK)) editor.putLong(KEY_LAST_UPDATE_CHECK, json.getLong(KEY_LAST_UPDATE_CHECK))
            if (json.has(KEY_MAP_ZOOM)) editor.putFloat(KEY_MAP_ZOOM, json.getDouble(KEY_MAP_ZOOM).toFloat())
            if (json.has(KEY_MAP_LAT)) {
                val lat = json.getDouble(KEY_MAP_LAT)
                editor.putLong(KEY_MAP_LAT + "_d", java.lang.Double.doubleToRawLongBits(lat))
                editor.putFloat(KEY_MAP_LAT, lat.toFloat())
            }
            if (json.has(KEY_MAP_LON)) {
                val lon = json.getDouble(KEY_MAP_LON)
                editor.putLong(KEY_MAP_LON + "_d", java.lang.Double.doubleToRawLongBits(lon))
                editor.putFloat(KEY_MAP_LON, lon.toFloat())
            }
            if (json.has(KEY_MAP_BEARING)) editor.putFloat(KEY_MAP_BEARING, json.getDouble(KEY_MAP_BEARING).toFloat())
            if (json.has(KEY_FOLLOW_USER)) editor.putBoolean(KEY_FOLLOW_USER, json.getBoolean(KEY_FOLLOW_USER))
            if (json.has(KEY_HEADING_UP)) editor.putBoolean(KEY_HEADING_UP, json.getBoolean(KEY_HEADING_UP))
            editor.apply()
        } catch (_: Exception) {}
    }

    // User Stopped
    fun isUserStopped(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USER_STOPPED, false)
    }

    fun setUserStopped(context: Context, stopped: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
        syncToExternalStorage(context)
    }

    // Debug Mode
    fun isDebugMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEBUG_MODE, false)
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
        syncToExternalStorage(context)
    }

    // Logging Enabled
    fun isLoggingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOGGING_ENABLED, false)
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
        syncToExternalStorage(context)
    }

    // Update Check Timestamp
    fun getLastUpdateCheckMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)
    }

    fun setLastUpdateCheckMs(context: Context, timeMs: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, timeMs).apply()
        syncToExternalStorage(context)
    }

    // Map state
    fun hasSavedMapLocation(context: Context): Boolean {
        val p = getPrefs(context)
        return p.contains(KEY_MAP_LAT) || p.contains(KEY_MAP_LAT + "_d")
    }

    fun getMapLat(context: Context): Double {
        val p = getPrefs(context)
        if (p.contains(KEY_MAP_LAT + "_d")) {
            return java.lang.Double.longBitsToDouble(p.getLong(KEY_MAP_LAT + "_d", 0L))
        }
        return p.getFloat(KEY_MAP_LAT, 50.4501f).toDouble()
    }

    fun getMapLon(context: Context): Double {
        val p = getPrefs(context)
        if (p.contains(KEY_MAP_LON + "_d")) {
            return java.lang.Double.longBitsToDouble(p.getLong(KEY_MAP_LON + "_d", 0L))
        }
        return p.getFloat(KEY_MAP_LON, 30.5234f).toDouble()
    }

    fun getMapZoom(context: Context): Float {
        val p = getPrefs(context)
        return try {
            p.getFloat(KEY_MAP_ZOOM, 12.0f)
        } catch (_: Exception) {
            p.getInt(KEY_MAP_ZOOM, 12).toFloat()
        }
    }

    fun setMapZoom(context: Context, zoom: Float) {
        getPrefs(context).edit().putFloat(KEY_MAP_ZOOM, zoom).apply()
    }

    fun setMapZoom(context: Context, zoom: Int) {
        getPrefs(context).edit().putFloat(KEY_MAP_ZOOM, zoom.toFloat()).apply()
    }

    fun getMapBearing(context: Context): Float {
        return getPrefs(context).getFloat(KEY_MAP_BEARING, 0.0f)
    }

    fun setMapBearing(context: Context, bearing: Float) {
        getPrefs(context).edit().putFloat(KEY_MAP_BEARING, (bearing % 360f + 360f) % 360f).apply()
    }

    fun setMapState(context: Context, lat: Double, lon: Double, zoom: Float, bearing: Float) {
        getPrefs(context).edit().apply {
            putLong(KEY_MAP_LAT + "_d", java.lang.Double.doubleToRawLongBits(lat))
            putFloat(KEY_MAP_LAT, lat.toFloat())
            putLong(KEY_MAP_LON + "_d", java.lang.Double.doubleToRawLongBits(lon))
            putFloat(KEY_MAP_LON, lon.toFloat())
            putFloat(KEY_MAP_ZOOM, zoom)
            putFloat(KEY_MAP_BEARING, (bearing % 360f + 360f) % 360f)
            apply()
        }
        syncToExternalStorage(context)
    }

    fun getFollowUser(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLLOW_USER, false)
    fun setFollowUser(context: Context, follow: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FOLLOW_USER, follow).apply()
    }

    fun isHeadingUp(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HEADING_UP, false)
    fun setHeadingUp(context: Context, headingUp: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HEADING_UP, headingUp).apply()
    }

    // Track recording
    fun isRecordingTrack(context: Context): Boolean = getPrefs(context).getBoolean(KEY_IS_RECORDING, false)
    fun setRecordingTrack(context: Context, recording: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_RECORDING, recording).apply()
    }

    fun getActiveTrackId(context: Context): Long = getPrefs(context).getLong(KEY_ACTIVE_TRACK_ID, -1L)
    fun setActiveTrackId(context: Context, trackId: Long) {
        getPrefs(context).edit().putLong(KEY_ACTIVE_TRACK_ID, trackId).apply()
    }

    // App language (uk / en)
    const val KEY_APP_LANG = "app_lang"
    const val KEY_MUSHROOM_LANG = "mushroom_lang"
    const val KEY_MUSHROOM_IS_UKRAINE = "mushroom_is_ukraine"
    const val KEY_MUSHROOM_FILTER = "mushroom_filter"

    fun getAppLang(context: Context): String {
        return getPrefs(context).getString(KEY_APP_LANG, null)
            ?: getPrefs(context).getString(KEY_MUSHROOM_LANG, "uk") ?: "uk"
    }

    fun setAppLang(context: Context, lang: String) {
        getPrefs(context).edit()
            .putString(KEY_APP_LANG, lang)
            .putString(KEY_MUSHROOM_LANG, lang)
            .apply()
    }

    fun getMushroomLang(context: Context): String = getAppLang(context)
    fun setMushroomLang(context: Context, lang: String) = setAppLang(context, lang)

    fun getMushroomIsUkraine(context: Context): Boolean = getPrefs(context).getBoolean(KEY_MUSHROOM_IS_UKRAINE, true)
    fun setMushroomIsUkraine(context: Context, isUkraine: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MUSHROOM_IS_UKRAINE, isUkraine).apply()
    }

    fun getMushroomFilter(context: Context): String = getPrefs(context).getString(KEY_MUSHROOM_FILTER, "all") ?: "all"
    fun setMushroomFilter(context: Context, filter: String) {
        getPrefs(context).edit().putString(KEY_MUSHROOM_FILTER, filter).apply()
    }

    const val KEY_MUSHROOM_VIEW_MODE = "mushroom_view_mode"
    fun getMushroomViewMode(context: Context): String = getPrefs(context).getString(KEY_MUSHROOM_VIEW_MODE, "list") ?: "list"
    fun setMushroomViewMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_MUSHROOM_VIEW_MODE, mode).apply()
    }
}
