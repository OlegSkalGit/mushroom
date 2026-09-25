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

    val tilesDir: File
        get() = File(mapsDir, "tiles").apply { if (!exists()) mkdirs() }

    val markersDir: File
        get() = File(baseDir, "markers").apply { if (!exists()) mkdirs() }

    val tracksDir: File
        get() = File(baseDir, "tracks").apply { if (!exists()) mkdirs() }

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
            tilesDir
            markersDir
            tracksDir
            settingsDir
            ensureNoMedia(baseDir)
            AppLogger.log(TAG, "initStorage", true, "Storage structure initialized at: ${baseDir.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "initStorage", false, "Failed to initialize storage: ${e.message}")
        }
    }

    // --- Markers ---
    private val markersFile: File
        get() = File(markersDir, "markers.json")

    fun loadMarkers(): List<MushroomMarker> {
        val list = ArrayList<MushroomMarker>()
        try {
            val file = markersFile
            if (!file.exists()) return list
            val content = file.readText(Charsets.UTF_8)
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(MushroomMarker.fromJson(obj))
            }
            AppLogger.log(TAG, "loadMarkers", true, "Loaded ${list.size} markers from ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "loadMarkers", false, "Error loading markers: ${e.message}")
        }
        return list
    }

    fun saveMarkers(markers: List<MushroomMarker>) {
        try {
            val arr = JSONArray()
            for (m in markers) {
                arr.put(m.toJson())
            }
            val file = markersFile
            file.writeText(arr.toString(2), Charsets.UTF_8)
            AppLogger.log(TAG, "saveMarkers", true, "Saved ${markers.size} markers to ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log(TAG, "saveMarkers", false, "Error saving markers: ${e.message}")
        }
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

    fun saveTracks(tracks: List<MushroomTrack>) {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(t.toJson())
                // Also export GPX per track
                saveTrackToGpx(t)
            }
            val file = tracksFile
            file.writeText(arr.toString(2), Charsets.UTF_8)
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

    // --- Tile Cache helper ---
    fun getTileFile(zoom: Int, x: Int, y: Int): File {
        val zDir = File(tilesDir, zoom.toString())
        val xDir = File(zDir, x.toString())
        if (!xDir.exists()) {
            xDir.mkdirs()
        }
        return File(xDir, "$y.png")
    }
}
