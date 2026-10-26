package com.olegskal.mushroom.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.model.TrackPoint
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import java.util.concurrent.Executors

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, getDatabasePath(context), null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "tracks.db"
        private const val DATABASE_VERSION = 5
        private val syncExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "TrackStorageSync").apply { isDaemon = true }
        }

        private fun getDatabasePath(context: Context): String {
            try {
                val target = MushroomStorageManager.trackDbFile
                val dir = target.parentFile
                if (dir != null && (dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                    if (!target.exists()) {
                        val oldInternal = context.getDatabasePath("mushroom.db")
                        val oldTracksMushroom = File(dir, "mushroom.db")
                        if (oldTracksMushroom.exists() && oldTracksMushroom.length() > 0) {
                            try {
                                oldTracksMushroom.renameTo(target)
                                AppLogger.log("DatabaseHelper", "getDatabasePath", true, "Renamed mushroom.db to tracks.db in tracks dir")
                            } catch (_: Exception) {}
                        } else if (oldInternal.exists() && oldInternal.length() > 0) {
                            try {
                                oldInternal.copyTo(target, overwrite = true)
                                AppLogger.log("DatabaseHelper", "getDatabasePath", true, "Migrated internal mushroom.db to ${target.absolutePath}")
                            } catch (e: Exception) {
                                AppLogger.log("DatabaseHelper", "getDatabasePath", false, "Failed to copy old db: ${e.message}")
                            }
                        }
                    }
                    return target.absolutePath
                }
            } catch (e: Exception) {
                AppLogger.log("DatabaseHelper", "getDatabasePath", false, "Error determining tracks db path: ${e.message}")
            }
            return DATABASE_NAME
        }

        // Tracks table
        const val TABLE_TRACKS = "tracks"
        const val COL_TRACK_ID = "id"
        const val COL_TRACK_TITLE = "title"
        const val COL_TRACK_START = "start_time"
        const val COL_TRACK_END = "end_time"
        const val COL_TRACK_DIST = "distance"
        const val COL_TRACK_DUR = "duration"
        const val COL_TRACK_COLOR = "color"
        const val COL_TRACK_VISIBLE = "is_visible"

        // Track points table
        const val TABLE_TRACK_POINTS = "track_points"
        const val COL_PT_ID = "id"
        const val COL_PT_TRACK_ID = "track_id"
        const val COL_PT_LAT = "lat"
        const val COL_PT_LON = "lon"
        const val COL_PT_ALT = "altitude"
        const val COL_PT_TIME = "time"
        const val COL_PT_SPEED = "speed"

    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRACKS (
                $COL_TRACK_ID INTEGER PRIMARY KEY,
                $COL_TRACK_TITLE TEXT NOT NULL,
                $COL_TRACK_START INTEGER NOT NULL,
                $COL_TRACK_END INTEGER NOT NULL,
                $COL_TRACK_DIST REAL NOT NULL,
                $COL_TRACK_DUR INTEGER NOT NULL,
                $COL_TRACK_COLOR INTEGER,
                $COL_TRACK_VISIBLE INTEGER DEFAULT 1
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_TRACK_POINTS (
                $COL_PT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PT_TRACK_ID INTEGER NOT NULL,
                $COL_PT_LAT REAL NOT NULL,
                $COL_PT_LON REAL NOT NULL,
                $COL_PT_ALT REAL DEFAULT 0,
                $COL_PT_TIME INTEGER NOT NULL,
                $COL_PT_SPEED REAL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_track_pts ON $TABLE_TRACK_POINTS($COL_PT_TRACK_ID, $COL_PT_TIME)")

        AppLogger.log("DatabaseHelper", "onCreate", true, "Database tables ($TABLE_TRACKS, $TABLE_TRACK_POINTS) created.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS markers")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACK_POINTS")
        onCreate(db)
        AppLogger.log("DatabaseHelper", "onUpgrade", true, "Upgraded DB from v$oldVersion to v$newVersion.")
    }

    // --- MARKERS CRUD (Primary & Only Storage: /storage/emulated/0/mushroom/markers/markers.json) ---

    fun insertMarker(marker: MushroomMarker) {
        MushroomStorageManager.insertMarker(marker)
    }

    fun updateMarker(marker: MushroomMarker) {
        MushroomStorageManager.updateMarker(marker)
    }

    fun deleteMarker(markerId: Long) {
        MushroomStorageManager.deleteMarker(markerId)
    }

    fun updateMarker(markerId: Long, newName: String, newColor: Int) {
        MushroomStorageManager.updateMarker(markerId, newName, newColor)
    }

    fun setMarkerVisibility(markerId: Long, isVisible: Boolean) {
        MushroomStorageManager.setMarkerVisibility(markerId, isVisible)
    }

    fun getAllMarkers(): List<MushroomMarker> {
        return MushroomStorageManager.loadMarkers()
    }

    fun getMarker(markerId: Long): MushroomMarker? {
        return MushroomStorageManager.getMarker(markerId)
    }

    // --- TRACKS CRUD ---

    fun insertTrack(track: MushroomTrack, syncToDisk: Boolean = true) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put(COL_TRACK_ID, track.id)
                put(COL_TRACK_TITLE, track.title)
                put(COL_TRACK_START, track.startTime)
                put(COL_TRACK_END, track.endTime)
                put(COL_TRACK_DIST, track.distanceMeters)
                put(COL_TRACK_DUR, track.durationSec)
                put(COL_TRACK_COLOR, track.color)
                put(COL_TRACK_VISIBLE, if (track.isVisible) 1 else 0)
            }
            db.insertWithOnConflict(TABLE_TRACKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            db.delete(TABLE_TRACK_POINTS, "$COL_PT_TRACK_ID = ?", arrayOf(track.id.toString()))
            db.compileStatement(
                "INSERT INTO $TABLE_TRACK_POINTS ($COL_PT_TRACK_ID, $COL_PT_LAT, $COL_PT_LON, $COL_PT_ALT, $COL_PT_TIME, $COL_PT_SPEED) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                for (p in track.points) {
                    stmt.clearBindings()
                    stmt.bindLong(1, track.id)
                    stmt.bindDouble(2, p.lat)
                    stmt.bindDouble(3, p.lon)
                    stmt.bindDouble(4, p.alt)
                    stmt.bindLong(5, p.time)
                    stmt.bindDouble(6, p.speed.toDouble())
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (syncToDisk) {
            syncTracksToStorage()
            MushroomStorageManager.saveTrackToGpx(track)
        }
    }

    fun updateTrack(track: MushroomTrack) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TRACK_TITLE, track.title)
            put(COL_TRACK_END, track.endTime)
            put(COL_TRACK_DIST, track.distanceMeters)
            put(COL_TRACK_DUR, track.durationSec)
            put(COL_TRACK_COLOR, track.color)
            put(COL_TRACK_VISIBLE, if (track.isVisible) 1 else 0)
        }
        db.update(TABLE_TRACKS, cv, "$COL_TRACK_ID = ?", arrayOf(track.id.toString()))
        syncTracksToStorage()
    }

    fun updateTrackInfo(trackId: Long, newTitle: String, newColor: Int) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TRACK_TITLE, newTitle)
            put(COL_TRACK_COLOR, newColor)
        }
        db.update(TABLE_TRACKS, cv, "$COL_TRACK_ID = ?", arrayOf(trackId.toString()))
        syncTracksToStorage()
    }

    fun addPointToTrack(trackId: Long, p: TrackPoint, totalDist: Float, durationSec: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put(COL_PT_TRACK_ID, trackId)
                put(COL_PT_LAT, p.lat)
                put(COL_PT_LON, p.lon)
                put(COL_PT_ALT, p.alt)
                put(COL_PT_TIME, p.time)
                put(COL_PT_SPEED, p.speed)
            }
            db.insert(TABLE_TRACK_POINTS, null, cv)

            val updateCv = ContentValues().apply {
                put(COL_TRACK_END, p.time)
                put(COL_TRACK_DIST, totalDist)
                put(COL_TRACK_DUR, durationSec)
            }
            db.update(TABLE_TRACKS, updateCv, "$COL_TRACK_ID = ?", arrayOf(trackId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setTrackVisibility(trackId: Long, isVisible: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TRACK_VISIBLE, if (isVisible) 1 else 0)
        }
        db.update(TABLE_TRACKS, cv, "$COL_TRACK_ID = ?", arrayOf(trackId.toString()))
        syncTracksToStorage()
    }

    fun deleteTrack(trackId: Long) {
        val db = writableDatabase
        db.delete(TABLE_TRACK_POINTS, "$COL_PT_TRACK_ID = ?", arrayOf(trackId.toString()))
        db.delete(TABLE_TRACKS, "$COL_TRACK_ID = ?", arrayOf(trackId.toString()))
        MushroomStorageManager.deleteTrackFile(trackId)
        syncTracksToStorage()
    }

    fun getAllTracks(): List<MushroomTrack> {
        val list = ArrayList<MushroomTrack>()
        val trackById = HashMap<Long, MushroomTrack>()
        val db = readableDatabase
        db.query(
            TABLE_TRACKS,
            null, null, null, null, null,
            "$COL_TRACK_START DESC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_TRACK_ID)
            val titleIdx = c.getColumnIndexOrThrow(COL_TRACK_TITLE)
            val startIdx = c.getColumnIndexOrThrow(COL_TRACK_START)
            val endIdx = c.getColumnIndexOrThrow(COL_TRACK_END)
            val distIdx = c.getColumnIndexOrThrow(COL_TRACK_DIST)
            val durIdx = c.getColumnIndexOrThrow(COL_TRACK_DUR)
            val colorIdx = c.getColumnIndexOrThrow(COL_TRACK_COLOR)
            val visIdx = c.getColumnIndexOrThrow(COL_TRACK_VISIBLE)

            while (c.moveToNext()) {
                val tId = c.getLong(idIdx)
                val track = MushroomTrack(
                    id = tId,
                    title = c.getString(titleIdx),
                    startTime = c.getLong(startIdx),
                    endTime = c.getLong(endIdx),
                    distanceMeters = c.getFloat(distIdx),
                    durationSec = c.getLong(durIdx),
                    color = c.getInt(colorIdx),
                    isVisible = c.getInt(visIdx) == 1
                )
                list.add(track)
                trackById[tId] = track
            }
        }
        if (list.isNotEmpty()) {
            db.query(
                TABLE_TRACK_POINTS,
                null, null, null, null, null,
                "$COL_PT_TRACK_ID ASC, $COL_PT_TIME ASC"
            ).use { c ->
                val trkIdx = c.getColumnIndexOrThrow(COL_PT_TRACK_ID)
                val latIdx = c.getColumnIndexOrThrow(COL_PT_LAT)
                val lonIdx = c.getColumnIndexOrThrow(COL_PT_LON)
                val altIdx = c.getColumnIndexOrThrow(COL_PT_ALT)
                val timeIdx = c.getColumnIndexOrThrow(COL_PT_TIME)
                val spdIdx = c.getColumnIndexOrThrow(COL_PT_SPEED)

                while (c.moveToNext()) {
                    val track = trackById[c.getLong(trkIdx)] ?: continue
                    track.points.add(
                        TrackPoint(
                            lat = c.getDouble(latIdx),
                            lon = c.getDouble(lonIdx),
                            alt = c.getDouble(altIdx),
                            time = c.getLong(timeIdx),
                            speed = c.getFloat(spdIdx)
                        )
                    )
                }
            }
        }
        return list
    }

    fun getPointsForTrack(trackId: Long): List<TrackPoint> {
        val list = ArrayList<TrackPoint>()
        val db = readableDatabase
        db.query(
            TABLE_TRACK_POINTS,
            null,
            "$COL_PT_TRACK_ID = ?",
            arrayOf(trackId.toString()),
            null, null,
            "$COL_PT_TIME ASC"
        ).use { c ->
            val latIdx = c.getColumnIndexOrThrow(COL_PT_LAT)
            val lonIdx = c.getColumnIndexOrThrow(COL_PT_LON)
            val altIdx = c.getColumnIndexOrThrow(COL_PT_ALT)
            val timeIdx = c.getColumnIndexOrThrow(COL_PT_TIME)
            val spdIdx = c.getColumnIndexOrThrow(COL_PT_SPEED)

            while (c.moveToNext()) {
                list.add(
                    TrackPoint(
                        lat = c.getDouble(latIdx),
                        lon = c.getDouble(lonIdx),
                        alt = c.getDouble(altIdx),
                        time = c.getLong(timeIdx),
                        speed = c.getFloat(spdIdx)
                    )
                )
            }
        }
        return list
    }

    fun syncTracksToStorage() {
        syncExecutor.execute {
            try {
                val list = getAllTracks()
                MushroomStorageManager.saveTracks(list)
            } catch (_: Exception) {}
        }
    }

    fun restoreDataFromExternalStorageIfDbEmpty() {
        try {
            val hasAnyTracks = readableDatabase.rawQuery("SELECT 1 FROM $TABLE_TRACKS LIMIT 1", null).use {
                it.moveToFirst()
            }
            if (!hasAnyTracks) {
                val loadedTracks = MushroomStorageManager.loadTracks()
                for (t in loadedTracks) {
                    insertTrack(t, syncToDisk = false)
                }
            }
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "restoreDataFromExternalStorage", false, "Error: ${e.message}")
        }
    }
}
