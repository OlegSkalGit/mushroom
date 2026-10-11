package com.olegskal.mushroom.address

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File

object AddressDb {

    private const val TAG = "AddressDb"
    private var openDb: SQLiteDatabase? = null
    private val dbLock = Any()

    fun getDatabaseFile(context: Context): File {
        val target = MushroomStorageManager.addressDbFile
        if (target.exists() && target.canWrite()) return target
        if (MushroomStorageManager.addressesDir.canWrite()) return target
        val fallbackDir = File(context.filesDir, "addresses").apply { if (!exists()) mkdirs() }
        return File(fallbackDir, "addr.db")
    }

    fun openDatabase(context: Context, writable: Boolean = false): SQLiteDatabase? {
        synchronized(dbLock) {
            val file = getDatabaseFile(context)
            if (!writable && (!file.exists() || file.length() < 1024L)) {
                return null
            }
            if (openDb?.isOpen == true && (!writable || !openDb!!.isReadOnly)) {
                return openDb
            }
            return try {
                file.parentFile?.mkdirs()
                val flags = if (writable) {
                    SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
                } else {
                    SQLiteDatabase.OPEN_READONLY
                }
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
                if (writable) {
                    initSchema(db)
                }
                openDb = db
                db
            } catch (e: Exception) {
                AppLogger.log(TAG, "openDatabase", false, "Failed to open addr.db: ${e.message}")
                null
            }
        }
    }

    fun initSchema(db: SQLiteDatabase) {
        try {
            db.execSQL("PRAGMA journal_mode = WAL;")
            db.execSQL("PRAGMA synchronous = NORMAL;")
            db.execSQL("PRAGMA foreign_keys = ON;")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS admin_places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    osm_id INTEGER,
                    parent_id INTEGER,
                    level INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    code TEXT,
                    FOREIGN KEY (parent_id) REFERENCES admin_places(id)
                );
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_admin_places_parent ON admin_places(parent_id);")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS street_names (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                );
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS streets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    place_id INTEGER NOT NULL,
                    region_id INTEGER,
                    country_id INTEGER NOT NULL,
                    name_id INTEGER NOT NULL,
                    FOREIGN KEY (place_id) REFERENCES admin_places(id),
                    FOREIGN KEY (region_id) REFERENCES admin_places(id),
                    FOREIGN KEY (country_id) REFERENCES admin_places(id),
                    FOREIGN KEY (name_id) REFERENCES street_names(id)
                );
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_streets_place_id ON streets(place_id);")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS houses (
                    street_id INTEGER NOT NULL,
                    number TEXT NOT NULL,
                    lat INTEGER NOT NULL,
                    lon INTEGER NOT NULL,
                    PRIMARY KEY (street_id, number),
                    FOREIGN KEY (street_id) REFERENCES streets(id)
                ) WITHOUT ROWID;
            """.trimIndent())

            // Create FTS5 contentless table with fallback to standard FTS5/FTS4
            createFtsTableWithFallback(db)

        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Schema init error: ${e.message}")
        }
    }

    private fun createFtsTableWithFallback(db: SQLiteDatabase) {
        val statements = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name);"
        )
        for (sql in statements) {
            try {
                db.execSQL(sql)
                return
            } catch (_: Exception) {
            }
        }
    }

    fun optimizeAndVacuum(db: SQLiteDatabase) {
        try {
            db.execSQL("INSERT INTO search_fts(search_fts) VALUES('optimize');")
        } catch (_: Exception) {}
        try {
            db.execSQL("VACUUM;")
        } catch (_: Exception) {}
    }

    fun closeDatabase() {
        synchronized(dbLock) {
            try {
                if (openDb?.isOpen == true) {
                    openDb?.close()
                }
            } catch (_: Exception) {}
            openDb = null
        }
    }
}
