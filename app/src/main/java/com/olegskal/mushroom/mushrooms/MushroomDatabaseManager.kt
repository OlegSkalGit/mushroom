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

    const val DB_FILE_NAME = "mushrooms.db"
    const val DB_DIR_NAME = "mushrooms"
    const val DB_MIN_SIZE = 400 * 1024 * 1024L // Min 400 MB to be considered valid
    const val DB_FULL_SIZE = 560164864L // Exact 534 MB (~534 MB)

    const val PRIMARY_URL = "https://media.githubusercontent.com/media/OlegSkalGit/mushroom/main/downloads/mushrooms.db"
    const val FALLBACK_URL = "https://github.com/OlegSkalGit/mushroom/raw/main/downloads/mushrooms.db"

    @Volatile
    private var cachedDb: SQLiteDatabase? = null
    private val dbLock = Any()

    fun getDatabaseDirectory(context: Context): File {
        val sdCard = File(Environment.getExternalStorageDirectory(), "mushroom/$DB_DIR_NAME")
        if (!sdCard.exists()) {
            sdCard.mkdirs()
        }
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
            val hasData = cursor.use {
                if (it.moveToFirst()) it.getInt(0) > 0 else false
            }
            hasData
        } catch (_: Exception) {
            false
        }
    }

    fun getDatabase(context: Context): SQLiteDatabase? {
        synchronized(dbLock) {
            cachedDb?.let {
                if (it.isOpen) return it
            }
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
            try {
                cachedDb?.close()
            } catch (_: Exception) {}
            cachedDb = null
        }
    }

    /**
     * Download or resume downloading mushrooms.db using HTTP Range requests.
     */
    fun downloadDatabase(
        context: Context,
        onProgress: (percent: Int, statusText: String) -> Unit,
        onComplete: (success: Boolean, errorMsg: String?) -> Unit
    ) {
        Thread {
            if (isDatabaseAvailable(context)) {
                onComplete(true, null)
                return@Thread
            }

            val targetDir = getDatabaseDirectory(context)
            if (!targetDir.exists()) targetDir.mkdirs()

            val tempFile = File(targetDir, "$DB_FILE_NAME.tmp")
            val finalFile = File(targetDir, DB_FILE_NAME)

            val urlsToTry = listOf(PRIMARY_URL, FALLBACK_URL)
            var downloadSuccess = false
            var lastError: String? = null

            for (urlCandidate in urlsToTry) {
                var conn: HttpURLConnection? = null
                var input: InputStream? = null
                var output: FileOutputStream? = null

                try {
                    val existingLength = if (tempFile.exists()) tempFile.length() else 0L
                    var currentUrl = urlCandidate
                    var redirects = 0
                    var responseCode = 0

                    while (redirects < 6) {
                        val url = URL(currentUrl)
                        conn = url.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent", "MushroomApp/1.0 (Android)")

                        if (existingLength > 0) {
                            conn.setRequestProperty("Range", "bytes=$existingLength-")
                        }

                        conn.connect()
                        responseCode = conn.responseCode

                        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                            responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                            responseCode == 307 || responseCode == 308) {
                            val newUrl = conn.getHeaderField("Location") ?: break
                            conn.disconnect()
                            currentUrl = newUrl
                            redirects++
                        } else if (responseCode == 200 || responseCode == 206) {
                            break
                        } else {
                            throw Exception("HTTP $responseCode from $currentUrl")
                        }
                    }

                    if (responseCode != 200 && responseCode != 206) {
                        throw Exception("Unexpected HTTP response: $responseCode")
                    }

                    val isResume = (responseCode == 206)
                    val append = isResume && existingLength > 0
                    val startingBytes = if (append) existingLength else 0L

                    val totalBytesExpected = if (isResume) {
                        val contentRange = conn!!.getHeaderField("Content-Range")
                        val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                        totalFromRange ?: (startingBytes + conn.contentLengthLong)
                    } else {
                        conn!!.contentLengthLong.takeIf { it > 0 } ?: DB_FULL_SIZE
                    }

                    input = conn.inputStream
                    output = FileOutputStream(tempFile, append)

                    val buffer = ByteArray(32768)
                    var totalDownloaded = startingBytes
                    var count: Int

                    val resumeText = if (append) " (докачування)" else ""
                    onProgress(
                        ((totalDownloaded * 100) / totalBytesExpected).toInt().coerceIn(0, 100),
                        "Завантаження${resumeText}..."
                    )

                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        totalDownloaded += count

                        if (totalBytesExpected > 0) {
                            val percent = ((totalDownloaded * 100) / totalBytesExpected).toInt().coerceIn(0, 100)
                            val mbDone = totalDownloaded / (1024 * 1024)
                            val mbTotal = totalBytesExpected / (1024 * 1024)
                            onProgress(percent, "$mbDone МБ / $mbTotal МБ ($percent%)")
                        }
                    }

                    output.flush()
                    output.close()
                    output = null
                    input.close()
                    input = null
                    conn.disconnect()

                    if (tempFile.exists() && tempFile.length() >= DB_MIN_SIZE) {
                        closeDatabase()
                        if (finalFile.exists()) finalFile.delete()
                        tempFile.renameTo(finalFile)
                        downloadSuccess = true
                        break
                    } else {
                        throw Exception("Файл бази неповний (${tempFile.length()} байт)")
                    }
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: e.toString()
                } finally {
                    try { input?.close() } catch (_: Exception) {}
                    try { output?.close() } catch (_: Exception) {}
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }

            if (downloadSuccess) {
                closeDatabase()
                onComplete(true, null)
            } else {
                onComplete(false, lastError ?: "Не вдалося завантажити базу даних")
            }
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
            "cond-edible" -> whereClauses.add("edibility = 'cond-edible'")
            "toxic" -> whereClauses.add("edibility = 'toxic'")
            "deadly" -> whereClauses.add("edibility = 'deadly'")
            "toxic_deadly" -> whereClauses.add("(edibility = 'toxic' OR edibility = 'deadly')")
            "tubes" -> whereClauses.add("hymenium = 'tubes'")
            "gills" -> whereClauses.add("hymenium = 'gills'")
        }

        when (hymeniumFilter) {
            "tubes" -> whereClauses.add("hymenium = 'tubes'")
            "gills" -> whereClauses.add("hymenium = 'gills'")
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
                    val rawHymenium = it.optStringVal(9) ?: "gills"
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
                    val hymenium = if (meta.hymenium != "other") meta.hymenium else rawHymenium

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
