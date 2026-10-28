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

    fun getAddressesDir(context: Context): File {
        val target = MushroomStorageManager.addressesDir
        if (target.exists() && target.canWrite()) return target
        if (target.canWrite()) return target
        return File(context.filesDir, "addresses").apply { if (!exists()) mkdirs() }
    }

    fun getDatabaseFile(context: Context): File {
        val target = MushroomStorageManager.addressDbFile
        if (target.exists() && target.canWrite()) return target
        return File(getAddressesDir(context), "addr.db")
    }

    fun openDatabase(context: Context, writable: Boolean = false): SQLiteDatabase? {
        synchronized(dbLock) {
            val file = getDatabaseFile(context)
            if (!writable && (!file.exists() || file.length() < 1024L)) {
                return null
            }
            if (openDb?.isOpen == true) {
                if (!writable || !openDb!!.isReadOnly) {
                    if (writable) {
                        ensureTablesExist(openDb!!)
                    }
                    return openDb
                } else {
                    try { openDb?.close() } catch (_: Exception) {}
                    openDb = null
                }
            }
            return try {
                file.parentFile?.mkdirs()
                val canWrite = file.exists() && file.canWrite()
                val flags = if (writable || canWrite) {
                    SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
                } else {
                    SQLiteDatabase.OPEN_READONLY
                }
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
                try {
                    db.enableWriteAheadLogging()
                } catch (_: Exception) {}
                if (writable) {
                    initSchema(db)
                    ensureTablesExist(db)
                }
                openDb = db
                db
            } catch (e: Exception) {
                AppLogger.log(TAG, "openDatabase", false, "Failed to open addr.db: ${e.message}")
                null
            }
        }
    }

    fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        return try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1;",
                arrayOf(tableName)
            )
            cursor.use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    fun hasPoiTables(db: SQLiteDatabase): Boolean {
        return hasTable(db, "poi_places")
    }

    fun hasPoiDataForCountry(db: SQLiteDatabase, countryCode: String): Boolean {
        val code = countryCode.uppercase()
        if (!hasTable(db, "poi_places")) return false
        return try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM poi_places WHERE country_code = ? LIMIT 1;",
                arrayOf(code)
            )
            cursor.use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    fun ensureTablesExist(db: SQLiteDatabase) {
        if (!hasTable(db, "admin_places") || !hasTable(db, "streets") || !hasTable(db, "houses") || !hasTable(db, "poi_places") || !hasTable(db, "poi_types")) {
            initSchema(db)
        }
        try {
            if (hasTable(db, "houses")) {
                var pkCount = 0
                try {
                    val cursor = db.rawQuery("PRAGMA table_info(houses);", null)
                    cursor.use {
                        while (it.moveToNext()) {
                            val pk = it.getInt(5)
                            if (pk > 0) pkCount++
                        }
                    }
                } catch (_: Exception) {}
                if (pkCount in 1..3) {
                    try {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS houses_new (
                                street_id INTEGER NOT NULL,
                                number TEXT NOT NULL,
                                lat INTEGER NOT NULL,
                                lon INTEGER NOT NULL,
                                PRIMARY KEY (street_id, number, lat, lon),
                                FOREIGN KEY (street_id) REFERENCES streets(id)
                            ) WITHOUT ROWID;
                        """.trimIndent())
                        db.execSQL("INSERT OR IGNORE INTO houses_new SELECT street_id, number, lat, lon FROM houses;")
                        db.execSQL("DROP TABLE houses;")
                        db.execSQL("ALTER TABLE houses_new RENAME TO houses;")
                    } catch (e: Exception) {
                        AppLogger.log(TAG, "ensureTablesExist", false, "Error migrating houses table: ${e.message}")
                    }
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_houses_coords ON houses(lat, lon);")
            }
            if (hasTable(db, "street_geometries")) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_street_geom_box ON street_geometries(min_lat, max_lat, min_lon, max_lon);")
            }
            if (hasTable(db, "poi_places")) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_coords ON poi_places(lat, lon);")
            }
            if (hasTable(db, "admin_places")) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_admin_places_name ON admin_places(name COLLATE NOCASE);")
            }
        } catch (_: Exception) {}
    }

    fun initSchema(db: SQLiteDatabase) {
        try {
            db.enableWriteAheadLogging()
        } catch (_: Exception) {}

        try {
            db.execSQL("PRAGMA synchronous = NORMAL;")
        } catch (_: Exception) {}

        try {
            db.execSQL("PRAGMA foreign_keys = ON;")
        } catch (_: Exception) {}

        try {
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
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating admin_places: ${e.message}")
        }

        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_admin_places_parent ON admin_places(parent_id);")
        } catch (_: Exception) {}

        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS street_names (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                );
            """.trimIndent())
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating street_names: ${e.message}")
        }

        try {
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
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating streets: ${e.message}")
        }

        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_streets_place_id ON streets(place_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_streets_name_id ON streets(name_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_streets_region_id ON streets(region_id);")
        } catch (_: Exception) {}

        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS houses (
                    street_id INTEGER NOT NULL,
                    number TEXT NOT NULL,
                    lat INTEGER NOT NULL,
                    lon INTEGER NOT NULL,
                    PRIMARY KEY (street_id, number, lat, lon),
                    FOREIGN KEY (street_id) REFERENCES streets(id)
                ) WITHOUT ROWID;
            """.trimIndent())
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating houses: ${e.message}")
        }


        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS street_geometries (
                    street_id INTEGER PRIMARY KEY,
                    min_lat INTEGER NOT NULL,
                    max_lat INTEGER NOT NULL,
                    min_lon INTEGER NOT NULL,
                    max_lon INTEGER NOT NULL,
                    geom BLOB NOT NULL,
                    FOREIGN KEY (street_id) REFERENCES streets(id)
                );
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_street_geom_box ON street_geometries (min_lat, max_lat, min_lon, max_lon);")
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating street_geometries: ${e.message}")
        }

        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS poi_types (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_category TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    desc_en TEXT NOT NULL,
                    desc_local TEXT NOT NULL,
                    country_code TEXT NOT NULL,
                    UNIQUE(tag, country_code)
                );
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_types_country ON poi_types(country_code);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_types_tag ON poi_types(tag);")
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating poi_types: ${e.message}")
        }

        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS poi_places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    category_id INTEGER NOT NULL,
                    street_id INTEGER,
                    house_number TEXT,
                    lat INTEGER NOT NULL,
                    lon INTEGER NOT NULL,
                    country_code TEXT NOT NULL DEFAULT 'UA',
                    FOREIGN KEY (street_id) REFERENCES streets(id)
                );
            """.trimIndent())
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating poi_places: ${e.message}")
        }


        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_street ON poi_places(street_id);")
        } catch (_: Exception) {}

        createFtsTableWithFallback(db)
        createPoiFtsTableWithFallback(db)
    }

    private fun createFtsTableWithFallback(db: SQLiteDatabase) {
        val statements = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, tokenize='unicode61', content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, tokenize=unicode61, content='', matchinfo=fts3);"
        )
        for (sql in statements) {
            try {
                db.execSQL(sql)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun createPoiFtsTableWithFallback(db: SQLiteDatabase) {
        val statements = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, type_name, type_tag, city_name, street_name, tokenize='unicode61', content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, type_name, type_tag, city_name, street_name, tokenize=unicode61, content='', matchinfo=fts3);"
        )
        for (sql in statements) {
            try {
                db.execSQL(sql)
                return
            } catch (_: Exception) {
            }
        }
    }

    fun recreateAllIndexes(db: SQLiteDatabase) {
        val indexes = listOf(
            "idx_houses_coords" to "CREATE INDEX IF NOT EXISTS idx_houses_coords ON houses(lat, lon);",
            "idx_street_geom_box" to "CREATE INDEX IF NOT EXISTS idx_street_geom_box ON street_geometries(min_lat, max_lat, min_lon, max_lon);",
            "idx_poi_places_coords" to "CREATE INDEX IF NOT EXISTS idx_poi_places_coords ON poi_places(lat, lon);",
            "idx_admin_places_name" to "CREATE INDEX IF NOT EXISTS idx_admin_places_name ON admin_places(name COLLATE NOCASE);",
            "idx_admin_places_parent" to "CREATE INDEX IF NOT EXISTS idx_admin_places_parent ON admin_places(parent_id);",
            "idx_streets_place_id" to "CREATE INDEX IF NOT EXISTS idx_streets_place_id ON streets(place_id);",
            "idx_streets_name_id" to "CREATE INDEX IF NOT EXISTS idx_streets_name_id ON streets(name_id);",
            "idx_streets_region_id" to "CREATE INDEX IF NOT EXISTS idx_streets_region_id ON streets(region_id);",
            "idx_poi_types_country" to "CREATE INDEX IF NOT EXISTS idx_poi_types_country ON poi_types(country_code);",
            "idx_poi_types_tag" to "CREATE INDEX IF NOT EXISTS idx_poi_types_tag ON poi_types(tag);",
            "idx_poi_places_street" to "CREATE INDEX IF NOT EXISTS idx_poi_places_street ON poi_places(street_id);"
        )
        for ((name, _) in indexes) {
            try {
                db.execSQL("DROP INDEX IF EXISTS $name;")
            } catch (_: Exception) {}
        }
        for ((name, sql) in indexes) {
            try {
                db.execSQL(sql)
            } catch (e: Exception) {
                AppLogger.log(TAG, "recreateAllIndexes", false, "Error creating index $name: ${e.message}")
            }
        }
    }

    fun optimizeAndVacuum(db: SQLiteDatabase) {
        try {
            if (db.inTransaction()) {
                db.endTransaction()
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "optimizeAndVacuum", false, "endTransaction warning: ${e.message}")
        }
        try {
            recreateAllIndexes(db)
        } catch (e: Exception) {
            AppLogger.log(TAG, "optimizeAndVacuum", false, "recreateAllIndexes failed: ${e.message}")
        }
        try {
            db.execSQL("INSERT INTO search_fts(search_fts) VALUES('optimize');")
        } catch (e: Exception) {
            AppLogger.log(TAG, "optimizeAndVacuum", false, "search_fts optimize failed: ${e.message}")
        }
        try {
            db.execSQL("INSERT INTO poi_fts(poi_fts) VALUES('optimize');")
        } catch (e: Exception) {
            AppLogger.log(TAG, "optimizeAndVacuum", false, "poi_fts optimize failed: ${e.message}")
        }
        try {
            db.execSQL("VACUUM;")
        } catch (e: Exception) {
            AppLogger.log(TAG, "optimizeAndVacuum", false, "VACUUM failed: ${e.message}")
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            AppLogger.log(TAG, "optimizeAndVacuum", false, "wal_checkpoint failed: ${e.message}")
        }
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
