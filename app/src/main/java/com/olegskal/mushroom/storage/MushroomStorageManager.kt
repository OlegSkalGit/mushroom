package com.olegskal.mushroom.storage

import android.content.Context
import android.os.Environment
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MushroomStorageManager {

    private const val TAG = "MushroomStorage"

    val baseDir: File
        get() {
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, "mushroom")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            ensureNoMedia(dir)
            return dir
        }

    val mapsDir: File
        get() = File(baseDir, "maps").apply { if (!exists()) mkdirs() }

    val navigationDir: File
        get() = File(baseDir, "navigation").apply { if (!exists()) mkdirs() }

    val poiDir: File
        get() = File(baseDir, "poi").apply { if (!exists()) mkdirs() }

    val poiConfigFile: File
        get() = File(poiDir, "poi_categories.json")

    val markersDir: File
        get() = File(baseDir, "markers").apply { if (!exists()) mkdirs() }

    val tracksDir: File
        get() = File(baseDir, "tracks").apply { if (!exists()) mkdirs() }

    val addressesDir: File
        get() = File(baseDir, "addresses").apply { if (!exists()) mkdirs() }

    val addressDbFile: File
        get() = File(addressesDir, "addr.db")

    val settingsDir: File
        get() = File(baseDir, "settings").apply { if (!exists()) mkdirs() }

    private fun ensureNoMedia(dir: File) {
        try {
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) {
                nomedia.createNewFile()
                AppLogger.log(TAG, "ensureNoMedia", true, "Created .nomedia file at: ${nomedia.absolutePath}")
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "ensureNoMedia", false, "Failed to create .nomedia: ${e.message}")
        }
    }

    fun initStorage() {
        try {
            baseDir
            mapsDir
            navigationDir
            poiDir
            markersDir
            tracksDir
            addressesDir
            settingsDir
            ensureNoMedia(baseDir)
            AppLogger.log(TAG, "initStorage", true, "Storage structure initialized at: ${baseDir.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "initStorage", false, "Failed to initialize storage: ${e.message}")
        }
    }

    fun loadPoiCategories(): Set<String> {
        return try {
            val file = poiConfigFile
            if (!file.exists()) return emptySet()
            val content = file.readText(Charsets.UTF_8)
            val arr = JSONArray(content)
            val set = HashSet<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (e: Exception) {
            AppLogger.log(TAG, "loadPoiCategories", false, "Error loading POI categories: ${e.message}")
            emptySet()
        }
    }

    fun savePoiCategories(categories: Set<String>) {
        try {
            val arr = JSONArray()
            for (c in categories) {
                arr.put(c)
            }
            poiConfigFile.writeText(arr.toString(2), Charsets.UTF_8)
            AppLogger.log(TAG, "savePoiCategories", true, "Saved ${categories.size} POI categories")
        } catch (e: Exception) {
            AppLogger.log(TAG, "savePoiCategories", false, "Error saving POI categories: ${e.message}")
        }
    }

    fun isPoiEnabled(): Boolean {
        val f = File(settingsDir, "poi_enabled.txt")
        if (!f.exists()) return true
        return f.readText(Charsets.UTF_8).trim() != "false"
    }

    fun setPoiEnabled(enabled: Boolean) {
        try {
            File(settingsDir, "poi_enabled.txt").writeText(enabled.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    // --- Markers (Primary & Only Storage: /storage/emulated/0/mushroom/markers/markers.json) ---
    val markersFile: File
        get() = File(markersDir, "markers.json")

    @Synchronized
    fun loadMarkers(): List<MushroomMarker> {
        val list = ArrayList<MushroomMarker>()
        try {
            val file = markersFile
            if (!file.exists()) return list
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isEmpty()) return list
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(MushroomMarker.fromJson(obj))
            }
            list.sortByDescending { it.timestamp }
            AppLogger.log(TAG, "loadMarkers", true, "Loaded ${list.size} markers from ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "loadMarkers", false, "Error loading markers: ${e.message}")
        }
        return list
    }

    @Synchronized
    fun saveMarkers(markers: List<MushroomMarker>) {
        try {
            val arr = JSONArray()
            for (m in markers) {
                arr.put(m.toJson())
            }
            val file = markersFile
            val tmpFile = File(markersDir, "markers.json.tmp")
            tmpFile.writeText(arr.toString(2), Charsets.UTF_8)
            if (file.exists()) {
                file.delete()
            }
            if (tmpFile.renameTo(file)) {
                AppLogger.log(TAG, "saveMarkers", true, "Saved ${markers.size} markers to ${file.absolutePath}")
            } else {
                file.writeText(arr.toString(2), Charsets.UTF_8)
                tmpFile.delete()
                AppLogger.log(TAG, "saveMarkers", true, "Saved (fallback) ${markers.size} markers to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "saveMarkers", false, "Error saving markers: ${e.message}")
        }
    }

    @Synchronized
    fun insertMarker(marker: MushroomMarker) {
        val list = loadMarkers().toMutableList()
        val index = list.indexOfFirst { it.id == marker.id }
        if (index != -1) {
            list[index] = marker
        } else {
            list.add(0, marker)
        }
        saveMarkers(list)
    }

    @Synchronized
    fun updateMarker(marker: MushroomMarker) {
        val list = loadMarkers().toMutableList()
        val index = list.indexOfFirst { it.id == marker.id }
        if (index != -1) {
            list[index] = marker
        } else {
            list.add(0, marker)
        }
        saveMarkers(list)
    }

    @Synchronized
    fun updateMarker(markerId: Long, newName: String, newColor: Int) {
        val list = loadMarkers().toMutableList()
        val index = list.indexOfFirst { it.id == markerId }
        if (index != -1) {
            val m = list[index]
            m.name = newName
            m.color = newColor
            saveMarkers(list)
        }
    }

    @Synchronized
    fun updateMarkerName(markerId: Long, newName: String) {
        val list = loadMarkers().toMutableList()
        val index = list.indexOfFirst { it.id == markerId }
        if (index != -1) {
            list[index].name = newName
            saveMarkers(list)
        }
    }

    @Synchronized
    fun setMarkerVisibility(markerId: Long, isVisible: Boolean) {
        val list = loadMarkers().toMutableList()
        val index = list.indexOfFirst { it.id == markerId }
        if (index != -1) {
            list[index].isVisible = isVisible
            saveMarkers(list)
        }
    }

    @Synchronized
    fun deleteMarker(markerId: Long): Boolean {
        val list = loadMarkers().toMutableList()
        val removed = list.removeAll { it.id == markerId }
        if (removed) {
            saveMarkers(list)
        }
        return removed
    }

    @Synchronized
    fun getMarker(markerId: Long): MushroomMarker? {
        return loadMarkers().firstOrNull { it.id == markerId }
    }

    // --- Tracks ---
    private val tracksFile: File
        get() = File(tracksDir, "tracks.json")

    fun loadTracks(): List<MushroomTrack> {
        val list = ArrayList<MushroomTrack>()
        try {
            val file = tracksFile
            if (!file.exists()) return list
            val content = file.readText(Charsets.UTF_8)
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(MushroomTrack.fromJson(obj))
            }
            AppLogger.log(TAG, "loadTracks", true, "Loaded ${list.size} tracks from ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "loadTracks", false, "Error loading tracks: ${e.message}")
        }
        return list
    }

    fun saveTracks(tracks: List<MushroomTrack>, exportGpx: Boolean = false) {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(t.toJson())
                if (exportGpx) {
                    saveTrackToGpx(t)
                }
            }
            val file = tracksFile
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString(2), Charsets.UTF_8)
            if (tmp.exists() && tmp.length() > 0) {
                tmp.renameTo(file)
            }
            AppLogger.log(TAG, "saveTracks", true, "Saved ${tracks.size} tracks to ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "saveTracks", false, "Error saving tracks: ${e.message}")
        }
    }

    fun saveTrackToGpx(track: MushroomTrack) {
        try {
            val gpxFile = File(tracksDir, "track_${track.id}.gpx")
            gpxFile.writeText(track.toGpx(), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.log(TAG, "saveTrackToGpx", false, "Error saving GPX track ${track.id}: ${e.message}")
        }
    }

    fun deleteTrackFile(trackId: Long): Boolean {
        return try {
            val gpxFile = File(tracksDir, "track_${trackId}.gpx")
            if (gpxFile.exists()) {
                val deleted = gpxFile.delete()
                AppLogger.log(TAG, "deleteTrackFile", deleted, "Deleted track GPX: ${gpxFile.absolutePath}")
                deleted
            } else {
                AppLogger.log(TAG, "deleteTrackFile", true, "GPX file not found: ${gpxFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "deleteTrackFile", false, "Error deleting GPX track $trackId: ${e.message}")
            false
        }
    }

    // --- Settings ---
    private val settingsFile: File
        get() = File(settingsDir, "settings.json")

    fun loadSettingsJson(): JSONObject? {
        return try {
            val file = settingsFile
            if (!file.exists()) return null
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun saveSettingsJson(json: JSONObject) {
        try {
            settingsFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.log(TAG, "saveSettingsJson", false, "Error saving settings: ${e.message}")
        }
    }
}
