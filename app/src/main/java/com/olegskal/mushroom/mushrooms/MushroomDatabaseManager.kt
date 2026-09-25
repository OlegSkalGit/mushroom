package com.olegskal.mushroom.mushrooms

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object MushroomDatabaseManager {

    val DB_FILE_NAME get() = MushroomDataConfig.DB_FILE_NAME
    val DB_DIR_NAME get() = MushroomDataConfig.DB_DIR_NAME
    val DB_MIN_SIZE get() = MushroomDataConfig.DB_MIN_SIZE
    val DB_FULL_SIZE get() = MushroomDataConfig.DB_FULL_SIZE
    val BASE_DOWNLOAD_URL get() = MushroomDataConfig.DB_BASE_DOWNLOAD_URL
    val DB_PARTS get() = MushroomDataConfig.DB_PARTS
    val TOTAL_ARCHIVE_SIZE get() = MushroomDataConfig.DB_TOTAL_ARCHIVE_SIZE

    enum class DataStatus {
        MISSING,
        NEEDS_UPDATE,
        READY
    }

    fun checkDatabaseStatus(context: Context): DataStatus {
        val file = getDatabaseFile(context)
        if (!file.exists() || file.length() < DB_MIN_SIZE) {
            return DataStatus.MISSING
        }
        if (file.length() != DB_FULL_SIZE) {
            return DataStatus.NEEDS_UPDATE
        }
        return DataStatus.READY
    }

    fun isDatabaseUpToDate(context: Context): Boolean {
        return checkDatabaseStatus(context) == DataStatus.READY
    }

    @Volatile
    private var cachedDb: SQLiteDatabase? = null
    private val dbLock = Any()

    fun getDatabaseDirectory(context: Context): File {
        val sdCard = File(Environment.getExternalStorageDirectory(), "mushroom/$DB_DIR_NAME")
        if (!sdCard.exists()) sdCard.mkdirs()
        if (sdCard.canWrite()) return sdCard

        val extFiles = context.getExternalFilesDir(null)
        if (extFiles != null) {
            val extDir = File(extFiles, DB_DIR_NAME)
            if (!extDir.exists()) extDir.mkdirs()
            if (extDir.canWrite()) return extDir
        }

        val internalDir = File(context.filesDir, DB_DIR_NAME)
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    fun getDatabaseFile(context: Context): File {
        val candidates = listOf(
            File(getDatabaseDirectory(context), DB_FILE_NAME),
            File(Environment.getExternalStorageDirectory(), "mushroom/$DB_DIR_NAME/$DB_FILE_NAME"),
            File(Environment.getExternalStorageDirectory(), "mushroom/$DB_FILE_NAME"),
            context.getExternalFilesDir(null)?.let { File(it, "$DB_DIR_NAME/$DB_FILE_NAME") },
            File(context.filesDir, "$DB_DIR_NAME/$DB_FILE_NAME")
        )
        for (c in candidates) {
            if (c != null && c.exists() && c.length() >= DB_MIN_SIZE) {
                return c
            }
        }
        return File(getDatabaseDirectory(context), DB_FILE_NAME)
    }

    fun isDatabaseAvailable(context: Context): Boolean {
        val file = getDatabaseFile(context)
        if (!file.exists() || file.length() < DB_MIN_SIZE) {
            return false
        }
        return try {
            val db = getDatabase(context) ?: return false
            val cursor = db.rawQuery("SELECT COUNT(*) FROM taxa", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) > 0 else false }
        } catch (_: Exception) {
            false
        }
    }

    fun getDatabase(context: Context): SQLiteDatabase? {
        synchronized(dbLock) {
            cachedDb?.let { if (it.isOpen) return it }
            val file = getDatabaseFile(context)
            if (!file.exists() || file.length() < DB_MIN_SIZE) return null

            return try {
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                cachedDb = db
                db
            } catch (_: Exception) {
                null
            }
        }
    }

    fun closeDatabase() {
        synchronized(dbLock) {
            try { cachedDb?.close() } catch (_: Exception) {}
            cachedDb = null
        }
    }

    /**
     * Потоковий об'єднувач томів .z01 ... .zip в єдиний InputStream
     */
    private class MultiVolumeZipInputStream(private val files: List<File>) : InputStream() {
        private var currentIndex = 0
        private var currentStream: InputStream? = null

        init {
            openNextStream()
        }

        private fun openNextStream(): Boolean {
            currentStream?.close()
            currentStream = null
            if (currentIndex >= files.size) return false

            var fis: InputStream = FileInputStream(files[currentIndex])

            // У першому томі (.z01) пропускаємо сигнатуру багатотомника 0x08074B50, якщо вона є
            if (currentIndex == 0) {
                val header = ByteArray(4)
                val read = fis.read(header)
                if (read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x07.toByte() && header[3] == 0x08.toByte()) {
                    // Сигнатуру пропущено
                } else if (read > 0) {
                    val pbis = java.io.PushbackInputStream(fis, 4)
                    pbis.unread(header, 0, read)
                    fis = pbis
                }
            }

            currentStream = fis
            currentIndex++
            return true
        }

        override fun read(): Int {
            while (true) {
                val stream = currentStream ?: return -1
                val b = stream.read()
                if (b != -1) return b
                if (!openNextStream()) return -1
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val stream = currentStream ?: return -1
                val r = stream.read(b, off, len)
                if (r != -1) return r
                if (!openNextStream()) return -1
            }
        }

        override fun close() {
            currentStream?.close()
            currentStream = null
        }
    }

    /**
     * Завантаження багатотомного архіву з докачуванням та потоковим розпакуванням
     */
    fun downloadDatabase(
        context: Context,
        forceDownload: Boolean = false,
        onProgress: (percent: Int, statusText: String) -> Unit,
        onComplete: (success: Boolean, errorMsg: String?) -> Unit
    ) {
        Thread {
            if (!forceDownload && isDatabaseUpToDate(context)) {
                onComplete(true, null)
                return@Thread
            }

            val targetDir = getDatabaseDirectory(context)
            if (!targetDir.exists()) targetDir.mkdirs()

            val finalDbFile = File(targetDir, DB_FILE_NAME)
            val downloadedPartFiles = mutableListOf<File>()

            // 1. Завантаження всіх томів .z01 ... .zip
            var downloadedTotalBytes = 0L

            for ((index, part) in DB_PARTS.withIndex()) {
                val (fileName, expectedSize) = part
                val partFile = File(targetDir, fileName)
                downloadedPartFiles.add(partFile)

                // Якщо файл уже повністю завантажений — пропускаємо
                if (partFile.exists() && partFile.length() == expectedSize) {
                    downloadedTotalBytes += expectedSize
                    continue
                }

                // Якщо файл більший ніж треба — видаляємо
                if (partFile.exists() && partFile.length() > expectedSize) {
                    partFile.delete()
                }

                var success = false
                var attempts = 0
                val partUrl = "$BASE_DOWNLOAD_URL$fileName"

                while (!success && attempts < 3) {
                    attempts++
                    var conn: HttpURLConnection? = null
                    var input: InputStream? = null
                    var output: FileOutputStream? = null

                    try {
                        val existingLen = if (partFile.exists()) partFile.length() else 0L
                        val url = URL(partUrl)
                        conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent", "MushroomApp/1.0 (Android)")

                        if (existingLen > 0) {
                            conn.setRequestProperty("Range", "bytes=$existingLen-")
                        }
                        conn.connect()

                        val code = conn.responseCode
                        val isResume = (code == 206)
                        if (code != 200 && code != 206) {
                            throw Exception("HTTP $code від $fileName")
                        }

                        val append = isResume && existingLen > 0
                        input = conn.inputStream
                        output = FileOutputStream(partFile, append)

                        val buffer = ByteArray(32768)
                        var readCount: Int

                        while (input.read(buffer).also { readCount = it } != -1) {
                            output.write(buffer, 0, readCount)
                            val currentPartLen = partFile.length()
                            val overallProgressBytes = downloadedTotalBytes + currentPartLen
                            val percent = ((overallProgressBytes * 85) / TOTAL_ARCHIVE_SIZE).toInt().coerceIn(0, 85)
                            val mbDone = overallProgressBytes / (1024 * 1024)
                            val mbTotal = TOTAL_ARCHIVE_SIZE / (1024 * 1024)

                            onProgress(percent, "Том ${index + 1}/${DB_PARTS.size}: $mbDone/$mbTotal МБ ($percent%)")
                        }

                        output.flush()
                        output.close()
                        output = null
                        input.close()
                        input = null
                        conn.disconnect()

                        if (partFile.length() == expectedSize) {
                            downloadedTotalBytes += expectedSize
                            success = true
                        }
                    } catch (e: Exception) {
                        try { output?.close() } catch (_: Exception) {}
                        try { input?.close() } catch (_: Exception) {}
                        try { conn?.disconnect() } catch (_: Exception) {}
                        if (attempts >= 3) {
                            onComplete(false, "Помилка завантаження $fileName: ${e.message}")
                            return@Thread
                        }
                    }
                }
                // Одразу після закриття блоку while (!success && attempts < 3):
                if (!success) {
                    onComplete(false, "Не вдалося повністю завантажити $fileName")
                    return@Thread
                }
            }

            // 2. Потокове розпакування через MultiVolumeZipInputStream
            onProgress(88, "Розпакування бази даних...")
            val tempDbFile = File(targetDir, "$DB_FILE_NAME.tmp")
            if (tempDbFile.exists()) tempDbFile.delete()

            try {
                val multiStream = MultiVolumeZipInputStream(downloadedPartFiles)
                val zipIn = java.util.zip.ZipInputStream(multiStream)
                var entry = zipIn.nextEntry
                val buffer = ByteArray(65536)

                while (entry != null) {
                    if (entry.name.endsWith(".db") || entry.name == DB_FILE_NAME) {
                        FileOutputStream(tempDbFile).use { fos ->
                            var len: Int
                            var written = 0L
                            while (zipIn.read(buffer).also { len = it } != -1) {
                                fos.write(buffer, 0, len)
                                written += len
                                val unpackPercent = 88 + ((written * 10) / DB_FULL_SIZE).toInt().coerceIn(0, 10)
                                onProgress(unpackPercent, "Розпакування: ${written / (1024 * 1024)} МБ...")
                            }
                            fos.flush()
                        }
                        break
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                zipIn.close()

                if (!tempDbFile.exists() || tempDbFile.length() != DB_FULL_SIZE) {
                    throw Exception("Розмір розпакованої бази не співпадає (${tempDbFile.length()} байт, очікувалось $DB_FULL_SIZE)")
                }

                // Заміна старої бази на нову
                closeDatabase()
                if (finalDbFile.exists()) finalDbFile.delete()
                File(targetDir, "$DB_FILE_NAME-wal").let { if (it.exists()) it.delete() }
                File(targetDir, "$DB_FILE_NAME-shm").let { if (it.exists()) it.delete() }
                tempDbFile.renameTo(finalDbFile)


            } catch (e: Exception) {
                if (tempDbFile.exists()) tempDbFile.delete()
                onComplete(false, "Помилка розпакування: ${e.message}")
                return@Thread
            }

            // 3. Видалення завантажених томів для вивільнення ~527 МБ пам'яті
            onProgress(99, "Очищення тимчасових томів...")
            for (f in downloadedPartFiles) {
                try { if (f.exists()) f.delete() } catch (_: Exception) {}
            }

            onProgress(100, "Готово!")
            closeDatabase()
            onComplete(true, null)
        }.start()
    }

    /**
     * Query taxa from SQLite offline database matching search query and category filters.
     */
    fun queryTaxa(
        context: Context,
        query: String,
        edibilityFilter: String = "all",
        hymeniumFilter: String = "all",
        lang: String,
        page: Int = 1,
        perPage: Int = 24
    ): Pair<List<MushroomTaxon>, Boolean> {
        val db = getDatabase(context) ?: return Pair(emptyList(), false)

        val cleanQuery = query.trim().lowercase()
        val whereClauses = mutableListOf<String>()
        val whereArgs = mutableListOf<String>()

        if (cleanQuery.isNotEmpty()) {
            whereClauses.add("(LOWER(scientific_name) LIKE ? OR LOWER(name_uk) LIKE ? OR LOWER(name_en) LIKE ? OR LOWER(genus) LIKE ?)")
            val pattern = "%$cleanQuery%"
            whereArgs.add(pattern)
            whereArgs.add(pattern)
            whereArgs.add(pattern)
            whereArgs.add(pattern)
        }

        when (edibilityFilter) {
            "edible" -> whereClauses.add("edibility = 'edible'")
            "inedible" -> whereClauses.add("edibility != 'edible'")
        }

        when (hymeniumFilter) {
            "tubes" -> whereClauses.add("hymenium = 'tubes'")
            "gills" -> whereClauses.add("hymenium = 'gills'")
            "other" -> whereClauses.add("hymenium = 'other'")
        }

        val whereSql = if (whereClauses.isNotEmpty()) {
            "WHERE " + whereClauses.joinToString(" AND ")
        } else {
            ""
        }

        val offset = (page - 1) * perPage

        // Count total matching
        val countSql = "SELECT COUNT(*) FROM taxa $whereSql"
        val totalCount = try {
            val c = db.rawQuery(countSql, whereArgs.toTypedArray())
            c.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Exception) { 0 }

        val orderSql = if (cleanQuery.isNotEmpty()) {
            "ORDER BY CASE WHEN LOWER(name_uk) = ? OR LOWER(scientific_name) = ? THEN 0 WHEN LOWER(name_uk) LIKE ? OR LOWER(scientific_name) LIKE ? THEN 1 ELSE 2 END, photos_count DESC, id ASC"
        } else {
            "ORDER BY photos_count DESC, id ASC"
        }

        val selectSql = """
            SELECT id, inat_id, scientific_name, name_uk, name_en, family, order_name, genus,
                   edibility, hymenium, desc_uk, desc_en, wiki_url_uk, wiki_url_en, photos_count
            FROM taxa
            $whereSql
            $orderSql
            LIMIT ? OFFSET ?
        """.trimIndent()

        val fullArgs = mutableListOf<String>()
        fullArgs.addAll(whereArgs)
        if (cleanQuery.isNotEmpty()) {
            fullArgs.add(cleanQuery)
            fullArgs.add(cleanQuery)
            fullArgs.add("$cleanQuery%")
            fullArgs.add("$cleanQuery%")
        }
        fullArgs.add(perPage.toString())
        fullArgs.add(offset.toString())

        val resultList = mutableListOf<MushroomTaxon>()
        val seenKeys = HashSet<String>()

        try {
            val cursor = db.rawQuery(selectSql, fullArgs.toTypedArray())
            cursor.use {
                while (it.moveToNext()) {
                    val dbId = it.getInt(0)
                    val inatId = it.optIntVal(1) ?: dbId
                    val sciName = it.getString(2) ?: ""
                    val nameUk = it.optStringVal(3)
                    val nameEn = it.optStringVal(4)
                    val family = it.optStringVal(5)
                    val orderName = it.optStringVal(6)
                    val genus = it.optStringVal(7)
                    val rawEdibility = it.optStringVal(8) ?: "unknown"
                    val rawHymenium = it.optStringVal(9) ?: "other"
                    val descUk = it.optStringVal(10)
                    val descEn = it.optStringVal(11)
                    val wikiUrlUk = it.optStringVal(12)
                    val wikiUrlEn = it.optStringVal(13)
                    val photosCount = it.getInt(14)

                    val sciKey = sciName.lowercase().trim()
                    val inatKey = if (inatId > 0) "inat_$inatId" else "db_$dbId"
                    if (seenKeys.contains(sciKey) || seenKeys.contains(inatKey)) {
                        continue
                    }
                    seenKeys.add(sciKey)
                    seenKeys.add(inatKey)

                    val meta = MycoKnowledge.resolveMetadata(sciName)
                    val edibility = if (meta.edibility != "unknown") meta.edibility else rawEdibility
                    val hymenium = if (meta.hymenium != "other") meta.hymenium else if (!rawHymenium.isNullOrBlank()) rawHymenium else "other"

                    val commonName = if (lang == "uk") {
                        nameUk?.takeIf { s -> s.isNotBlank() } ?: sciName
                    } else {
                        nameEn?.takeIf { s -> s.isNotBlank() } ?: sciName
                    }

                    val wikiSummary = if (lang == "uk") {
                        descUk?.takeIf { s -> s.isNotBlank() } ?: descEn
                    } else {
                        descEn?.takeIf { s -> s.isNotBlank() } ?: descUk
                    }

                    val wikiUrl = if (lang == "uk") {
                        wikiUrlUk?.takeIf { s -> s.isNotBlank() } ?: wikiUrlEn
                    } else {
                        wikiUrlEn?.takeIf { s -> s.isNotBlank() } ?: wikiUrlUk
                    }

                    // Pre-generate offline photo URIs for this taxon
                    val photoUris = getTaxonPhotoUris(db, dbId)
                    val defaultUri = photoUris.firstOrNull()

                    resultList.add(
                        MushroomTaxon(
                            id = inatId,
                            scientificName = sciName,
                            commonName = commonName,
                            defaultPhotoUrl = defaultUri,
                            photoUrls = photoUris,
                            wikipediaSummary = wikiSummary,
                            wikipediaUrl = wikiUrl,
                            edibility = edibility,
                            hymenium = hymenium,
                            observationsCount = if (photosCount > 0) photosCount * 12 else 0,
                            rank = "species",
                            family = family,
                            order = orderName,
                            genus = genus,
                            englishCommonName = nameEn
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        val hasMore = (offset + resultList.size) < totalCount
        return Pair(resultList, hasMore)
    }

    private fun getTaxonPhotoUris(db: SQLiteDatabase, taxonDbId: Int): List<String> {
        val list = mutableListOf<String>()
        try {
            val cursor = db.rawQuery("SELECT id FROM photos WHERE taxon_id = ? ORDER BY photo_index ASC", arrayOf(taxonDbId.toString()))
            cursor.use {
                while (it.moveToNext()) {
                    val photoId = it.getInt(0)
                    list.add("db://photo/$photoId")
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun loadPhotoBlob(context: Context, photoId: Int): ByteArray? {
        val db = getDatabase(context) ?: return null
        return try {
            val cursor = db.rawQuery("SELECT image_data FROM photos WHERE id = ?", arrayOf(photoId.toString()))
            cursor.use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun android.database.Cursor.optStringVal(index: Int): String? {
        return if (!isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.optIntVal(index: Int): Int? {
        return if (!isNull(index)) getInt(index) else null
    }
}
