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
            if (openDb?.isOpen == true) {
                if (file.canWrite() && isFtsUpgradeNeeded(openDb!!)) {
                    try { openDb?.close() } catch (_: Exception) {}
                    openDb = null
                } else if (!writable || !openDb!!.isReadOnly) {
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
                if (writable) {
                    initSchema(db)
                    ensureTablesExist(db)
                } else if (canWrite && isFtsUpgradeNeeded(db)) {
                    rebuildFtsIndex(db)
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

    fun isFtsUpgradeNeeded(db: SQLiteDatabase): Boolean {
        return try {
            val cursor = db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='search_fts' LIMIT 1;",
                null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val sql = it.getString(0) ?: ""
                    !sql.contains("unicode61", ignoreCase = true)
                } else {
                    hasTable(db, "streets")
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun rebuildFtsIndex(db: SQLiteDatabase) {
        try {
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS search_fts;")
                db.execSQL("DROP TABLE IF EXISTS search_fts_content;")
                createFtsTableWithFallback(db)
                if (hasTable(db, "streets") && hasTable(db, "street_names") && hasTable(db, "admin_places")) {
                    db.execSQL("""
                        INSERT INTO search_fts (rowid, region_name, city_name, street_name)
                        SELECT s.id, LOWER(COALESCE(reg.name, '')), LOWER(city.name), LOWER(sn.name)
                        FROM streets s
                        JOIN street_names sn ON s.name_id = sn.id
                        JOIN admin_places city ON s.place_id = city.id
                        LEFT JOIN admin_places reg ON s.region_id = reg.id;
                    """.trimIndent())
                }
                db.setTransactionSuccessful()
                AppLogger.log(TAG, "rebuildFtsIndex", true, "FTS index successfully rebuilt with unicode61")
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "rebuildFtsIndex", false, "Error rebuilding FTS index: ${e.message}")
        }
    }

    fun hasPoiTables(db: SQLiteDatabase): Boolean {
        return hasTable(db, "poi_places") && hasTable(db, "poi_country_ranges")
    }

    fun hasPoiData(db: SQLiteDatabase): Boolean {
        if (!hasTable(db, "poi_places")) return false
        return try {
            val cursor = db.rawQuery("SELECT 1 FROM poi_places LIMIT 1;", null)
            cursor.use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    fun hasPoiDataForCountry(db: SQLiteDatabase, countryCode: String): Boolean {
        if (!hasTable(db, "poi_country_ranges")) return false
        return try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM poi_country_ranges WHERE country_code = ? AND total_count > 0 LIMIT 1;",
                arrayOf(countryCode.uppercase())
            )
            cursor.use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    fun getCountryPoiCount(db: SQLiteDatabase, countryCode: String): Int {
        if (!hasTable(db, "poi_country_ranges")) return 0
        return try {
            val cursor = db.rawQuery(
                "SELECT total_count FROM poi_country_ranges WHERE country_code = ? LIMIT 1;",
                arrayOf(countryCode.uppercase())
            )
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) {
            0
        }
    }

    fun ensureTablesExist(db: SQLiteDatabase) {
        if (!hasTable(db, "admin_places") || !hasTable(db, "streets") || !hasTable(db, "houses") || !hasTable(db, "poi_places") || !hasTable(db, "poi_country_ranges")) {
            initSchema(db)
        }
        checkAndMigratePoiPlaces(db)
        if (hasTable(db, "search_fts_content") || isFtsUpgradeNeeded(db)) {
            rebuildFtsIndex(db)
        }
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
        } catch (_: Exception) {}

        try {
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
        } catch (_: Exception) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS houses (
                        street_id INTEGER NOT NULL,
                        number TEXT NOT NULL,
                        lat INTEGER NOT NULL,
                        lon INTEGER NOT NULL,
                        PRIMARY KEY (street_id, number),
                        FOREIGN KEY (street_id) REFERENCES streets(id)
                    );
                """.trimIndent())
            } catch (e2: Exception) {
                AppLogger.log(TAG, "initSchema", false, "Error creating houses: ${e2.message}")
            }
        }

        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_houses_coords ON houses(lat, lon);")
        } catch (_: Exception) {}

        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS poi_places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    poi_id INTEGER,
                    name TEXT NOT NULL,
                    category_id TEXT NOT NULL,
                    street_id INTEGER,
                    house_number TEXT,
                    lat INTEGER NOT NULL,
                    lon INTEGER NOT NULL,
                    FOREIGN KEY (street_id) REFERENCES streets(id)
                );
            """.trimIndent())
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating poi_places: ${e.message}")
        }

        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_street ON poi_places(street_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_cat ON poi_places(category_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_coords ON poi_places(lat, lon);")
        } catch (_: Exception) {}

        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS poi_country_ranges (
                    country_code TEXT PRIMARY KEY,
                    min_id INTEGER NOT NULL,
                    max_id INTEGER NOT NULL,
                    total_count INTEGER NOT NULL
                );
            """.trimIndent())
        } catch (e: Exception) {
            AppLogger.log(TAG, "initSchema", false, "Error creating poi_country_ranges: ${e.message}")
        }

        createFtsTableWithFallback(db)
        createPoiFtsTableWithFallback(db)
    }

    fun checkAndMigratePoiPlaces(db: SQLiteDatabase) {
        try {
            if (!hasTable(db, "poi_places")) return
            val cursor = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='poi_places' LIMIT 1;", null)
            val sql = cursor.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
            if (sql.contains("poi_id INTEGER UNIQUE", ignoreCase = true)) {
                val ownTx = !db.inTransaction()
                if (ownTx) db.beginTransaction()
                try {
                    db.execSQL("""
                        CREATE TABLE poi_places_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            poi_id INTEGER,
                            name TEXT NOT NULL,
                            category_id TEXT NOT NULL,
                            street_id INTEGER,
                            house_number TEXT,
                            lat INTEGER NOT NULL,
                            lon INTEGER NOT NULL,
                            FOREIGN KEY (street_id) REFERENCES streets(id)
                        );
                    """.trimIndent())
                    db.execSQL("INSERT INTO poi_places_new (id, poi_id, name, category_id, street_id, house_number, lat, lon) SELECT id, poi_id, name, category_id, street_id, house_number, lat, lon FROM poi_places;")
                    db.execSQL("DROP TABLE poi_places;")
                    db.execSQL("ALTER TABLE poi_places_new RENAME TO poi_places;")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_street ON poi_places(street_id);")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_cat ON poi_places(category_id);")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_coords ON poi_places(lat, lon);")
                    if (ownTx) db.setTransactionSuccessful()
                    AppLogger.log(TAG, "checkAndMigratePoiPlaces", true, "Migrated poi_places to non-unique poi_id")
                } finally {
                    if (ownTx && db.inTransaction()) {
                        db.endTransaction()
                    }
                }
            }

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS poi_country_ranges (
                    country_code TEXT PRIMARY KEY,
                    min_id INTEGER NOT NULL,
                    max_id INTEGER NOT NULL,
                    total_count INTEGER NOT NULL
                );
            """.trimIndent())

            val cntCursor = db.rawQuery("SELECT count(1) FROM poi_country_ranges;", null)
            val rangeCount = cntCursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (rangeCount == 0 && hasTable(db, "poi_places")) {
                val pCursor = db.rawQuery("SELECT min(id), max(id), count(id) FROM poi_places;", null)
                pCursor.use {
                    if (it.moveToFirst()) {
                        val count = it.getInt(2)
                        if (count > 0) {
                            val minId = it.getLong(0)
                            val maxId = it.getLong(1)
                            val sampleCur = db.rawQuery("SELECT lat, lon FROM poi_places LIMIT 1;", null)
                            val (sLat, sLon) = sampleCur.use { sc ->
                                if (sc.moveToFirst()) Pair(sc.getInt(0), sc.getInt(1)) else Pair(0, 0)
                            }
                            val code = if (sLat < 0 && sLon < 0) "PE" else "UA"
                            db.execSQL(
                                "INSERT OR REPLACE INTO poi_country_ranges (country_code, min_id, max_id, total_count) VALUES (?, ?, ?, ?);",
                                arrayOf(code, minId, maxId, count)
                            )
                            AppLogger.log(TAG, "checkAndMigratePoiPlaces", true, "Backfilled existing POI range for $code: $count points")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "checkAndMigratePoiPlaces", false, "Migration error: ${e.message}")
        }
    }

    private fun createFtsTableWithFallback(db: SQLiteDatabase) {
        val statements = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, tokenize='unicode61', content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, tokenize='unicode61', content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, tokenize=unicode61, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, tokenize=unicode61);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, content='');",
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

    private fun createPoiFtsTableWithFallback(db: SQLiteDatabase) {
        val statements = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, category_name, city_name, street_name, tokenize='unicode61', content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, category_name, city_name, street_name, tokenize='unicode61', content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, category_name, city_name, street_name, content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, category_name, city_name, street_name, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, category_name, city_name, street_name, tokenize=unicode61, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, category_name, city_name, street_name, tokenize=unicode61);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, category_name, city_name, street_name, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, category_name, city_name, street_name);"
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
            if (db.inTransaction()) {
                db.endTransaction()
            }
        } catch (_: Exception) {}
        try {
            db.execSQL("INSERT INTO search_fts(search_fts) VALUES('optimize');")
        } catch (_: Exception) {}
        try {
            db.execSQL("INSERT INTO poi_fts(poi_fts) VALUES('optimize');")
        } catch (_: Exception) {}
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { it.moveToFirst() }
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
