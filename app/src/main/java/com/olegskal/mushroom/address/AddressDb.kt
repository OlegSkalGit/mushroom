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
                try {
                    db.enableWriteAheadLogging()
                } catch (_: Exception) {}
                if (writable) {
                    initSchema(db)
                    ensureTablesExist(db)
                } else if (canWrite) {
                    if (isFtsUpgradeNeeded(db)) {
                        rebuildFtsIndex(db)
                    }
                    if (isPoiFtsUpgradeNeeded(db)) {
                        rebuildPoiFtsIndex(db)
                    }
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

    fun isPoiFtsUpgradeNeeded(db: SQLiteDatabase): Boolean {
        return try {
            val cursor = db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='poi_fts' LIMIT 1;",
                null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val sql = it.getString(0) ?: ""
                    !sql.contains("type_name", ignoreCase = true)
                } else {
                    hasTable(db, "poi_places")
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun rebuildPoiFtsIndex(db: SQLiteDatabase) {
        try {
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS poi_fts;")
                db.execSQL("DROP TABLE IF EXISTS poi_fts_content;")
                createPoiFtsTableWithFallback(db)
                if (hasTable(db, "poi_places")) {
                    val hasPoiTypes = hasTable(db, "poi_types")
                    val sql = if (hasPoiTypes) {
                        """
                            INSERT INTO poi_fts (rowid, poi_name, type_name, type_tag, city_name, street_name)
                            SELECT pp.id, pp.name, COALESCE(pt.desc_local, pt.desc_en, ''), COALESCE(pt.tag, ''), COALESCE(city.name, ''), COALESCE(sn.name, '')
                            FROM poi_places pp
                            LEFT JOIN poi_types pt ON pt.id = pp.category_id
                            LEFT JOIN streets s ON s.id = pp.street_id
                            LEFT JOIN street_names sn ON sn.id = s.name_id
                            LEFT JOIN admin_places city ON city.id = s.place_id;
                        """.trimIndent()
                    } else {
                        """
                            INSERT INTO poi_fts (rowid, poi_name, type_name, type_tag, city_name, street_name)
                            SELECT pp.id, pp.name, pp.category_id, pp.category_id, COALESCE(city.name, ''), COALESCE(sn.name, '')
                            FROM poi_places pp
                            LEFT JOIN streets s ON s.id = pp.street_id
                            LEFT JOIN street_names sn ON sn.id = s.name_id
                            LEFT JOIN admin_places city ON city.id = s.place_id;
                        """.trimIndent()
                    }
                    db.execSQL(sql)
                }
                db.setTransactionSuccessful()
                AppLogger.log(TAG, "rebuildPoiFtsIndex", true, "POI FTS index successfully rebuilt")
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "rebuildPoiFtsIndex", false, "Error rebuilding POI FTS index: ${e.message}")
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
        if (hasTable(db, "search_fts_content") || isFtsUpgradeNeeded(db)) {
            rebuildFtsIndex(db)
        }
        if (hasTable(db, "poi_fts_content") || isPoiFtsUpgradeNeeded(db)) {
            rebuildPoiFtsIndex(db)
        }
        try {
            if (hasTable(db, "houses")) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_houses_coords ON houses(lat, lon);")
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
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_country ON poi_places(country_code);")
        } catch (_: Exception) {}

        createFtsTableWithFallback(db)
        createPoiFtsTableWithFallback(db)
    }

    private fun createFtsTableWithFallback(db: SQLiteDatabase) {
        val statements = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, tokenize='unicode61', content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, tokenize='unicode61', content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(region_name, city_name, street_name, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, tokenize=unicode61, content='', matchinfo=fts3);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, tokenize=unicode61, matchinfo=fts3);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, content='', matchinfo=fts3);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(region_name, city_name, street_name, matchinfo=fts3);"
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
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, type_name, type_tag, city_name, street_name, tokenize='unicode61', content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, type_name, type_tag, city_name, street_name, content='', detail=column, columnsize=0);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts5(poi_name, type_name, type_tag, city_name, street_name, content='');",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, type_name, type_tag, city_name, street_name, tokenize=unicode61, content='', matchinfo=fts3);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, type_name, type_tag, city_name, street_name, tokenize=unicode61, matchinfo=fts3);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, type_name, type_tag, city_name, street_name, content='', matchinfo=fts3);",
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(poi_name, type_name, type_tag, city_name, street_name, matchinfo=fts3);"
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
