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
                put(KEY_MAP_ZOOM, p.getInt(KEY_MAP_ZOOM, 12))
                put(KEY_MAP_LAT, p.getFloat(KEY_MAP_LAT, 0f).toDouble())
                put(KEY_MAP_LON, p.getFloat(KEY_MAP_LON, 0f).toDouble())
                put(KEY_FOLLOW_USER, p.getBoolean(KEY_FOLLOW_USER, true))
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
            if (json.has(KEY_MAP_ZOOM)) editor.putInt(KEY_MAP_ZOOM, json.getInt(KEY_MAP_ZOOM))
            if (json.has(KEY_MAP_LAT)) editor.putFloat(KEY_MAP_LAT, json.getDouble(KEY_MAP_LAT).toFloat())
            if (json.has(KEY_MAP_LON)) editor.putFloat(KEY_MAP_LON, json.getDouble(KEY_MAP_LON).toFloat())
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
    fun getMapZoom(context: Context): Int = getPrefs(context).getInt(KEY_MAP_ZOOM, 12)
    fun setMapZoom(context: Context, zoom: Int) {
        getPrefs(context).edit().putInt(KEY_MAP_ZOOM, zoom).apply()
    }

    fun getFollowUser(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLLOW_USER, true)
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
}
