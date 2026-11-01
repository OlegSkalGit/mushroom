package com.olegskal.mushroom.util

import android.content.Context
import android.content.SharedPreferences
import com.olegskal.mushroom.storage.MushroomStorageManager
import org.json.JSONObject

/**
 * Centralized utility for managing application settings, stored in
 * /sdcard/mushroom/settings/settings.json.
 */
object AppPrefs {
    const val KEY_USER_STOPPED = "user_stopped"
    const val KEY_MAP_ZOOM = "map_zoom"
    const val KEY_MAP_LAT = "map_lat"
    const val KEY_MAP_LON = "map_lon"
    const val KEY_MAP_BEARING = "map_bearing"
    const val KEY_FOLLOW_USER = "follow_user"
    const val KEY_IS_RECORDING = "is_recording"
    const val KEY_ACTIVE_TRACK_ID = "active_track_id"
    const val KEY_INITIAL_SETUP_DISMISSED = "initial_setup_dismissed"

    // Language & Mushrooms preferences
    const val KEY_APP_LANG = "app_lang"
    const val KEY_MUSHROOM_LANG = KEY_APP_LANG
    const val KEY_MUSHROOM_IS_UKRAINE = "mushroom_is_ukraine"
    const val KEY_MUSHROOM_EDIBILITY_FILTER = "mushroom_edibility_filter"
    const val KEY_MUSHROOM_HYMENIUM_FILTER = "mushroom_hymenium_filter"
    const val KEY_MUSHROOM_VIEW_MODE = "mushroom_view_mode"
    const val KEY_MUSHROOM_OFFLINE_MODE = "mushroom_offline_mode"

    fun getPrefs(context: Context): SharedPreferences = JsonSharedPreferences

    private object JsonSharedPreferences : SharedPreferences {
        override fun getAll(): Map<String, *> {
            val json = MushroomStorageManager.getSettingsJson()
            val map = mutableMapOf<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.opt(k)
            }
            return map
        }

        override fun getString(key: String?, defValue: String?): String? {
            if (key == null) return defValue
            val json = MushroomStorageManager.getSettingsJson()
            return if (json.has(key) && !json.isNull(key)) json.optString(key) else defValue
        }

        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
            if (key == null) return defValues
            val json = MushroomStorageManager.getSettingsJson()
            val arr = json.optJSONArray(key) ?: return defValues
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.optString(i))
            }
            return set
        }

        override fun getInt(key: String?, defValue: Int): Int {
            if (key == null) return defValue
            val json = MushroomStorageManager.getSettingsJson()
            return if (json.has(key)) json.optInt(key, defValue) else defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            if (key == null) return defValue
            val json = MushroomStorageManager.getSettingsJson()
            return if (json.has(key)) json.optLong(key, defValue) else defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            if (key == null) return defValue
            val json = MushroomStorageManager.getSettingsJson()
            return if (json.has(key)) json.optDouble(key, defValue.toDouble()).toFloat() else defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            if (key == null) return defValue
            val json = MushroomStorageManager.getSettingsJson()
            return if (json.has(key)) json.optBoolean(key, defValue) else defValue
        }

        override fun contains(key: String?): Boolean {
            if (key == null) return false
            return MushroomStorageManager.getSettingsJson().has(key)
        }

        override fun edit(): SharedPreferences.Editor = JsonEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class JsonEditor : SharedPreferences.Editor {
        private val modifications = mutableListOf<(JSONObject) -> Unit>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                modifications.add { it.put(key, value) }
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) {
                modifications.add {
                    val arr = org.json.JSONArray()
                    values?.forEach { v -> arr.put(v) }
                    it.put(key, arr)
                }
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) {
                modifications.add { it.put(key, value) }
            }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) {
                modifications.add { it.put(key, value) }
            }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) {
                modifications.add { it.put(key, value.toDouble()) }
            }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) {
                modifications.add { it.put(key, value) }
            }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                modifications.add { it.remove(key) }
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(AppPrefs) {
                val json = if (clearRequested) JSONObject() else MushroomStorageManager.getSettingsJson()
                modifications.forEach { it(json) }
                MushroomStorageManager.saveSettingsJson(json)
            }
        }
    }

    fun isInitialSetupDismissed(context: Context): Boolean = getPrefs(context).getBoolean(KEY_INITIAL_SETUP_DISMISSED, false)
    fun setInitialSetupDismissed(context: Context, dismissed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_INITIAL_SETUP_DISMISSED, dismissed).apply()
    }

    // User Stopped
    fun isUserStopped(context: Context): Boolean = getPrefs(context).getBoolean(KEY_USER_STOPPED, false)
    fun setUserStopped(context: Context, stopped: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
    }

    // Map state
    fun hasSavedMapLocation(context: Context): Boolean {
        return getPrefs(context).contains(KEY_MAP_LAT)
    }

    fun getMapLat(context: Context): Double {
        return MushroomStorageManager.getSettingsJson().optDouble(KEY_MAP_LAT, 50.4501)
    }

    fun getMapLon(context: Context): Double {
        return MushroomStorageManager.getSettingsJson().optDouble(KEY_MAP_LON, 30.5234)
    }

    fun getMapZoom(context: Context): Float = getPrefs(context).getFloat(KEY_MAP_ZOOM, 12.0f)
    fun setMapZoom(context: Context, zoom: Float) {
        getPrefs(context).edit().putFloat(KEY_MAP_ZOOM, zoom).apply()
    }
    fun setMapZoom(context: Context, zoom: Int) {
        setMapZoom(context, zoom.toFloat())
    }

    fun getMapBearing(context: Context): Float = getPrefs(context).getFloat(KEY_MAP_BEARING, 0.0f)
    fun setMapBearing(context: Context, bearing: Float) {
        getPrefs(context).edit().putFloat(KEY_MAP_BEARING, (bearing % 360f + 360f) % 360f).apply()
    }

    fun setMapState(context: Context, lat: Double, lon: Double, zoom: Float, bearing: Float) {
        synchronized(this) {
            val json = MushroomStorageManager.getSettingsJson()
            json.put(KEY_MAP_LAT, lat)
            json.put(KEY_MAP_LON, lon)
            json.put(KEY_MAP_ZOOM, zoom.toDouble())
            json.put(KEY_MAP_BEARING, ((bearing % 360f + 360f) % 360f).toDouble())
            MushroomStorageManager.saveSettingsJson(json)
        }
    }

    fun getFollowUser(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLLOW_USER, false)
    fun setFollowUser(context: Context, follow: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FOLLOW_USER, follow).apply()
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

    fun getAppLang(context: Context): String {
        return getPrefs(context).getString(KEY_APP_LANG, "uk") ?: "uk"
    }

    fun setAppLang(context: Context, lang: String) {
        getPrefs(context).edit()
            .putString(KEY_APP_LANG, lang)
            .apply()
    }

    fun getMushroomLang(context: Context): String = getAppLang(context)
    fun setMushroomLang(context: Context, lang: String) = setAppLang(context, lang)

    fun isUk(context: Context): Boolean = getAppLang(context) == "uk"
    fun t(context: Context, uk: String, en: String): String = if (isUk(context)) uk else en

    fun getMushroomIsUkraine(context: Context): Boolean = getPrefs(context).getBoolean(KEY_MUSHROOM_IS_UKRAINE, true)
    fun setMushroomIsUkraine(context: Context, isUkraine: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MUSHROOM_IS_UKRAINE, isUkraine).apply()
    }

    fun getMushroomEdibilityFilter(context: Context): String = getPrefs(context).getString(KEY_MUSHROOM_EDIBILITY_FILTER, "all") ?: "all"
    fun setMushroomEdibilityFilter(context: Context, filter: String) {
        getPrefs(context).edit().putString(KEY_MUSHROOM_EDIBILITY_FILTER, filter).apply()
    }

    fun getMushroomHymeniumFilter(context: Context): String = getPrefs(context).getString(KEY_MUSHROOM_HYMENIUM_FILTER, "all") ?: "all"
    fun setMushroomHymeniumFilter(context: Context, filter: String) {
        getPrefs(context).edit().putString(KEY_MUSHROOM_HYMENIUM_FILTER, filter).apply()
    }

    fun getMushroomViewMode(context: Context): String = getPrefs(context).getString(KEY_MUSHROOM_VIEW_MODE, "list") ?: "list"
    fun setMushroomViewMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_MUSHROOM_VIEW_MODE, mode).apply()
    }

    fun getMushroomOfflineMode(context: Context): Boolean = getPrefs(context).getBoolean(KEY_MUSHROOM_OFFLINE_MODE, false)
    fun setMushroomOfflineMode(context: Context, isOffline: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MUSHROOM_OFFLINE_MODE, isOffline).apply()
    }
}
