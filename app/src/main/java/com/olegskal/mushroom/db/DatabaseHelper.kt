package com.olegskal.mushroom.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.map.MapRegion
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.model.TrackPoint
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "mushroom.db"
        private const val DATABASE_VERSION = 5

        // Markers table
        const val TABLE_MARKERS = "markers"
        const val COL_MARKER_ID = "id"
        const val COL_MARKER_NAME = "name"
        const val COL_MARKER_TYPE = "type"
        const val COL_MARKER_LAT = "lat"
        const val COL_MARKER_LON = "lon"
        const val COL_MARKER_ALT = "altitude"
        const val COL_MARKER_TIME = "timestamp"
        const val COL_MARKER_NOTE = "note"
        const val COL_MARKER_COLOR = "color"
        const val COL_MARKER_VISIBLE = "is_visible"

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

        // OSM Countries table
        const val TABLE_COUNTRIES = "osm_countries"
        const val COL_COUNTRY_CODE = "code"
        const val COL_COUNTRY_NAME = "name"

        // OSM Regions table
        const val TABLE_REGIONS = "osm_regions"
        const val COL_REGION_ID = "id"
        const val COL_REGION_COUNTRY_CODE = "country_code"
        const val COL_REGION_NAME = "name"
        const val COL_REGION_MIN_LAT = "min_lat"
        const val COL_REGION_MAX_LAT = "max_lat"
        const val COL_REGION_MIN_LON = "min_lon"
        const val COL_REGION_MAX_LON = "max_lon"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MARKERS (
                $COL_MARKER_ID INTEGER PRIMARY KEY,
                $COL_MARKER_NAME TEXT NOT NULL,
                $COL_MARKER_TYPE TEXT NOT NULL,
                $COL_MARKER_LAT REAL NOT NULL,
                $COL_MARKER_LON REAL NOT NULL,
                $COL_MARKER_ALT REAL DEFAULT 0,
                $COL_MARKER_TIME INTEGER NOT NULL,
                $COL_MARKER_NOTE TEXT,
                $COL_MARKER_COLOR INTEGER,
                $COL_MARKER_VISIBLE INTEGER DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_marker_coords ON $TABLE_MARKERS($COL_MARKER_LAT, $COL_MARKER_LON)")

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

        ensureOsmTables(db)

        AppLogger.log("DatabaseHelper", "onCreate", true, "Database tables ($TABLE_MARKERS, $TABLE_TRACKS, $TABLE_TRACK_POINTS, $TABLE_COUNTRIES, $TABLE_REGIONS) created.")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureOsmTables(db)
    }

    private fun ensureOsmTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_COUNTRIES (
                $COL_COUNTRY_CODE TEXT PRIMARY KEY,
                $COL_COUNTRY_NAME TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_REGIONS (
                $COL_REGION_ID TEXT PRIMARY KEY,
                $COL_REGION_COUNTRY_CODE TEXT NOT NULL,
                $COL_REGION_NAME TEXT NOT NULL,
                $COL_REGION_MIN_LAT REAL NOT NULL,
                $COL_REGION_MAX_LAT REAL NOT NULL,
                $COL_REGION_MIN_LON REAL NOT NULL,
                $COL_REGION_MAX_LON REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_regions_country ON $TABLE_REGIONS($COL_REGION_COUNTRY_CODE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MARKERS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACK_POINTS")
        onCreate(db)
        AppLogger.log("DatabaseHelper", "onUpgrade", true, "Upgraded DB from v$oldVersion to v$newVersion.")
    }

    // --- MARKERS CRUD ---

    fun insertMarker(marker: MushroomMarker) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_MARKER_ID, marker.id)
            put(COL_MARKER_NAME, marker.name)
            put(COL_MARKER_TYPE, marker.type)
            put(COL_MARKER_LAT, marker.lat)
            put(COL_MARKER_LON, marker.lon)
            put(COL_MARKER_ALT, marker.altitude)
            put(COL_MARKER_TIME, marker.timestamp)
            put(COL_MARKER_NOTE, marker.note)
            put(COL_MARKER_COLOR, marker.color)
            put(COL_MARKER_VISIBLE, if (marker.isVisible) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_MARKERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        syncMarkersToStorage()
    }

    fun updateMarker(marker: MushroomMarker) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_MARKER_NAME, marker.name)
            put(COL_MARKER_TYPE, marker.type)
            put(COL_MARKER_NOTE, marker.note)
            put(COL_MARKER_COLOR, marker.color)
            put(COL_MARKER_VISIBLE, if (marker.isVisible) 1 else 0)
        }
        db.update(TABLE_MARKERS, cv, "$COL_MARKER_ID = ?", arrayOf(marker.id.toString()))
        syncMarkersToStorage()
    }

    fun deleteMarker(markerId: Long) {
        val db = writableDatabase
        db.delete(TABLE_MARKERS, "$COL_MARKER_ID = ?", arrayOf(markerId.toString()))
        syncMarkersToStorage()
    }

    fun updateMarkerName(markerId: Long, newName: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_MARKER_NAME, newName)
        }
        db.update(TABLE_MARKERS, cv, "$COL_MARKER_ID = ?", arrayOf(markerId.toString()))
        syncMarkersToStorage()
    }

    fun setMarkerVisibility(markerId: Long, isVisible: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_MARKER_VISIBLE, if (isVisible) 1 else 0)
        }
        db.update(TABLE_MARKERS, cv, "$COL_MARKER_ID = ?", arrayOf(markerId.toString()))
        syncMarkersToStorage()
    }

    fun getAllMarkers(): List<MushroomMarker> {
        val list = ArrayList<MushroomMarker>()
        val db = readableDatabase
        db.query(
            TABLE_MARKERS,
            null, null, null, null, null,
            "$COL_MARKER_TIME DESC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_MARKER_ID)
            val nameIdx = c.getColumnIndexOrThrow(COL_MARKER_NAME)
            val typeIdx = c.getColumnIndexOrThrow(COL_MARKER_TYPE)
            val latIdx = c.getColumnIndexOrThrow(COL_MARKER_LAT)
            val lonIdx = c.getColumnIndexOrThrow(COL_MARKER_LON)
            val altIdx = c.getColumnIndexOrThrow(COL_MARKER_ALT)
            val timeIdx = c.getColumnIndexOrThrow(COL_MARKER_TIME)
            val noteIdx = c.getColumnIndexOrThrow(COL_MARKER_NOTE)
            val colorIdx = c.getColumnIndexOrThrow(COL_MARKER_COLOR)
            val visIdx = c.getColumnIndexOrThrow(COL_MARKER_VISIBLE)

            while (c.moveToNext()) {
                list.add(
                    MushroomMarker(
                        id = c.getLong(idIdx),
                        name = c.getString(nameIdx),
                        type = c.getString(typeIdx),
                        lat = c.getDouble(latIdx),
                        lon = c.getDouble(lonIdx),
                        altitude = c.getDouble(altIdx),
                        timestamp = c.getLong(timeIdx),
                        note = c.getString(noteIdx) ?: "",
                        color = c.getInt(colorIdx),
                        isVisible = c.getInt(visIdx) == 1
                    )
                )
            }
        }
        return list
    }

    private fun syncMarkersToStorage() {
        try {
            val list = getAllMarkers()
            MushroomStorageManager.saveMarkers(list)
        } catch (_: Exception) {}
    }

    // --- TRACKS CRUD ---

    fun insertTrack(track: MushroomTrack) {
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
            val stmt = db.compileStatement(
                "INSERT INTO $TABLE_TRACK_POINTS ($COL_PT_TRACK_ID, $COL_PT_LAT, $COL_PT_LON, $COL_PT_ALT, $COL_PT_TIME, $COL_PT_SPEED) VALUES (?, ?, ?, ?, ?, ?)"
            )
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        syncTracksToStorage()
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
        syncTracksToStorage()
    }

    fun updateTrackTitle(trackId: Long, newTitle: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TRACK_TITLE, newTitle)
        }
        db.update(TABLE_TRACKS, cv, "$COL_TRACK_ID = ?", arrayOf(trackId.toString()))
        syncTracksToStorage()
    }

    fun getAllTracks(): List<MushroomTrack> {
        val list = ArrayList<MushroomTrack>()
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
            }
        }
        for (track in list) {
            track.points.addAll(getPointsForTrack(track.id))
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
        try {
            val list = getAllTracks()
            MushroomStorageManager.saveTracks(list)
        } catch (_: Exception) {}
    }

    fun restoreDataFromExternalStorageIfDbEmpty() {
        try {
            val currentMarkers = getAllMarkers()
            if (currentMarkers.isEmpty()) {
                val loaded = MushroomStorageManager.loadMarkers()
                for (m in loaded) {
                    insertMarker(m)
                }
            }
            val currentTracks = getAllTracks()
            if (currentTracks.isEmpty()) {
                val loadedTracks = MushroomStorageManager.loadTracks()
                for (t in loadedTracks) {
                    insertTrack(t)
                }
            }
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "restoreDataFromExternalStorage", false, "Error: ${e.message}")
        }
    }

    // --- OSM COUNTRIES & REGIONS CACHE ---

    fun getCachedCountries(): List<MapCountry> {
        val list = ArrayList<MapCountry>()
        val db = readableDatabase
        ensureOsmTables(db)
        db.query(TABLE_COUNTRIES, null, null, null, null, null, "$COL_COUNTRY_NAME ASC").use { c ->
            val codeIdx = c.getColumnIndexOrThrow(COL_COUNTRY_CODE)
            val nameIdx = c.getColumnIndexOrThrow(COL_COUNTRY_NAME)
            while (c.moveToNext()) {
                list.add(MapCountry(name = c.getString(nameIdx), code = c.getString(codeIdx)))
            }
        }
        return list
    }

    fun insertCountries(countries: List<MapCountry>) {
        val db = writableDatabase
        ensureOsmTables(db)
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR REPLACE INTO $TABLE_COUNTRIES ($COL_COUNTRY_CODE, $COL_COUNTRY_NAME) VALUES (?, ?)")
            for (c in countries) {
                stmt.clearBindings()
                stmt.bindString(1, c.code)
                stmt.bindString(2, c.name)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getCachedRegions(countryCode: String): List<MapRegion> {
        val list = ArrayList<MapRegion>()
        val db = readableDatabase
        ensureOsmTables(db)
        db.query(
            TABLE_REGIONS,
            null,
            "$COL_REGION_COUNTRY_CODE = ?",
            arrayOf(countryCode),
            null,
            null,
            "$COL_REGION_NAME ASC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_REGION_ID)
            val nameIdx = c.getColumnIndexOrThrow(COL_REGION_NAME)
            val minLatIdx = c.getColumnIndexOrThrow(COL_REGION_MIN_LAT)
            val maxLatIdx = c.getColumnIndexOrThrow(COL_REGION_MAX_LAT)
            val minLonIdx = c.getColumnIndexOrThrow(COL_REGION_MIN_LON)
            val maxLonIdx = c.getColumnIndexOrThrow(COL_REGION_MAX_LON)
            while (c.moveToNext()) {
                list.add(
                    MapRegion(
                        id = c.getString(idIdx),
                        name = c.getString(nameIdx),
                        minLat = c.getDouble(minLatIdx),
                        maxLat = c.getDouble(maxLatIdx),
                        minLon = c.getDouble(minLonIdx),
                        maxLon = c.getDouble(maxLonIdx)
                    )
                )
            }
        }
        if (list.isEmpty()) {
            if (countryCode.equals("UA", true)) {
                insertRegions("UA", com.olegskal.mushroom.network.OverpassSyncManager.BUILTIN_UA_REGIONS)
                return com.olegskal.mushroom.network.OverpassSyncManager.BUILTIN_UA_REGIONS
            } else if (countryCode.equals("PL", true)) {
                insertRegions("PL", com.olegskal.mushroom.network.OverpassSyncManager.BUILTIN_PL_REGIONS)
                return com.olegskal.mushroom.network.OverpassSyncManager.BUILTIN_PL_REGIONS
            }
        }
        return list
    }

    fun insertRegions(countryCode: String, regions: List<MapRegion>) {
        val db = writableDatabase
        ensureOsmTables(db)
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE_REGIONS ($COL_REGION_ID, $COL_REGION_COUNTRY_CODE, $COL_REGION_NAME, $COL_REGION_MIN_LAT, $COL_REGION_MAX_LAT, $COL_REGION_MIN_LON, $COL_REGION_MAX_LON) VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
            for (r in regions) {
                stmt.clearBindings()
                stmt.bindString(1, r.id)
                stmt.bindString(2, countryCode)
                stmt.bindString(3, r.name)
                stmt.bindDouble(4, r.minLat)
                stmt.bindDouble(5, r.maxLat)
                stmt.bindDouble(6, r.minLon)
                stmt.bindDouble(7, r.maxLon)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
