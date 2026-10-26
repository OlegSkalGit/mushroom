package com.olegskal.mushroom.mushrooms

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.os.PowerManager
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
     * Потокове розпакування багатотомного архіву бази даних SQLite з валідацією розміру.
     */
    fun unpackDownloadedParts(
        context: Context,
        downloadedPartFiles: List<File>,
        onProgress: ((unpackedBytes: Long, totalExpectedBytes: Long) -> Unit)? = null
    ): Boolean {
        val targetDir = getDatabaseDirectory(context)
        val finalDbFile = File(targetDir, DB_FILE_NAME)
        val tempDbFile = File(targetDir, "$DB_FILE_NAME.tmp")
        if (tempDbFile.exists()) tempDbFile.delete()

        return try {
            val multiStream = MultiVolumeZipInputStream(downloadedPartFiles)
            val zipIn = java.util.zip.ZipInputStream(multiStream)
            var entry = zipIn.nextEntry
            val buffer = ByteArray(65536)
            var bytesUnpacked = 0L
            var lastReportMs = 0L

            while (entry != null) {
                if (entry.name.endsWith(".db") || entry.name == DB_FILE_NAME) {
                    FileOutputStream(tempDbFile).use { fos ->
                        var len: Int
                        while (zipIn.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                            bytesUnpacked += len
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs > 150L || bytesUnpacked >= DB_FULL_SIZE) {
                                lastReportMs = now
                                onProgress?.invoke(bytesUnpacked, DB_FULL_SIZE)
                            }
                        }
                        fos.flush()
                    }
                    break
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            zipIn.close()
            onProgress?.invoke(DB_FULL_SIZE, DB_FULL_SIZE)

            if (!tempDbFile.exists() || tempDbFile.length() != DB_FULL_SIZE) {
                if (tempDbFile.exists()) tempDbFile.delete()
                return false
            }

            closeDatabase()
            if (finalDbFile.exists()) finalDbFile.delete()
            File(targetDir, "$DB_FILE_NAME-wal").let { if (it.exists()) it.delete() }
            File(targetDir, "$DB_FILE_NAME-shm").let { if (it.exists()) it.delete() }
            val renamed = tempDbFile.renameTo(finalDbFile)
            if (!renamed) {
                tempDbFile.copyTo(finalDbFile, overwrite = true)
                tempDbFile.delete()
            }

            for (f in downloadedPartFiles) {
                try { if (f.exists()) f.delete() } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            if (tempDbFile.exists()) tempDbFile.delete()
            false
        }
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
            SELECT * FROM taxa
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
        val dbIds = mutableListOf<Int>()
        val seenKeys = HashSet<String>()

        try {
            val cursor = db.rawQuery(selectSql, fullArgs.toTypedArray())
            cursor.use {
                val cId = it.getColumnIndex("id")
                val cInatId = it.getColumnIndex("inat_id")
                val cSciName = it.getColumnIndex("scientific_name")
                val cNameUk = it.getColumnIndex("name_uk")
                val cNameEn = it.getColumnIndex("name_en")
                val cFamily = it.getColumnIndex("family")
                val cOrder = it.getColumnIndex("order_name")
                val cGenus = it.getColumnIndex("genus")
                val cEdibility = it.getColumnIndex("edibility")
                val cHymenium = it.getColumnIndex("hymenium")
                val cDescUk = it.getColumnIndex("desc_uk")
                val cDescEn = it.getColumnIndex("desc_en")
                val cWikiUk = it.getColumnIndex("wiki_url_uk")
                val cWikiEn = it.getColumnIndex("wiki_url_en")
                val cPhotosCount = it.getColumnIndex("photos_count")
                val cPhylum = it.getColumnIndex("phylum")
                val cTaxonClass = it.getColumnIndex("taxon_class")
                val cConservation = it.getColumnIndex("conservation_status")
                val cObservations = it.getColumnIndex("observations_count")
                val cLookalikesUk = it.getColumnIndex("lookalikes_uk")
                val cLookalikesEn = it.getColumnIndex("lookalikes_en")
                val cPropsCap = it.getColumnIndex("props_cap")
                val cPropsHymenium = it.getColumnIndex("props_hymenium")
                val cPropsStem = it.getColumnIndex("props_stem")
                val cPropsFlesh = it.getColumnIndex("props_flesh")
                val cPropsSeason = it.getColumnIndex("props_season")
                val cPropsHabitat = it.getColumnIndex("props_habitat")
                val cEcologyUk = it.getColumnIndex("ecology_uk")
                val cEcologyEn = it.getColumnIndex("ecology_en")
                val cToxicityUk = it.getColumnIndex("toxicity_uk")
                val cToxicityEn = it.getColumnIndex("toxicity_en")
                val cUseUk = it.getColumnIndex("use_uk")
                val cUseEn = it.getColumnIndex("use_en")

                fun colStr(c: Int): String? = if (c >= 0 && !it.isNull(c)) it.getString(c)?.trim()?.takeIf { s -> s.isNotEmpty() && !s.equals("null", ignoreCase = true) } else null
                fun colInt(c: Int): Int? = if (c >= 0 && !it.isNull(c)) it.getInt(c) else null

                while (it.moveToNext()) {
                    val dbId = if (cId >= 0) it.getInt(cId) else 0
                    val inatId = colInt(cInatId) ?: dbId
                    val sciName = (if (cSciName >= 0) it.getString(cSciName) else null) ?: ""
                    val nameUk = colStr(cNameUk)
                    val nameEn = colStr(cNameEn)
                    val family = colStr(cFamily)
                    val orderName = colStr(cOrder)
                    val genus = colStr(cGenus)
                    val rawEdibility = colStr(cEdibility) ?: "unknown"
                    val rawHymenium = colStr(cHymenium) ?: "other"
                    val descUk = colStr(cDescUk)
                    val descEn = colStr(cDescEn)
                    val wikiUrlUk = colStr(cWikiUk)
                    val wikiUrlEn = colStr(cWikiEn)
                    val photosCount = if (cPhotosCount >= 0) it.getInt(cPhotosCount) else 0

                    val phylum = colStr(cPhylum)
                    val taxonClass = colStr(cTaxonClass)
                    val conservationStatus = colStr(cConservation)
                    val realObsCount = colInt(cObservations)
                    val observationsCount = if (realObsCount != null && realObsCount > 0) realObsCount else if (photosCount > 0) photosCount * 12 else 0

                    val lookalikesUk = colStr(cLookalikesUk)
                    val lookalikesEn = colStr(cLookalikesEn)
                    val propsCap = colStr(cPropsCap)
                    val propsHymenium = colStr(cPropsHymenium)
                    val propsStem = colStr(cPropsStem)
                    val propsFlesh = colStr(cPropsFlesh)
                    val propsSeason = colStr(cPropsSeason)
                    val propsHabitat = colStr(cPropsHabitat)
                    val ecologyUk = colStr(cEcologyUk)
                    val ecologyEn = colStr(cEcologyEn)
                    val toxicityUk = colStr(cToxicityUk)
                    val toxicityEn = colStr(cToxicityEn)
                    val useUk = colStr(cUseUk)
                    val useEn = colStr(cUseEn)

                    val sciKey = sciName.lowercase().trim()
                    val inatKey = if (inatId > 0) "inat_$inatId" else "db_$dbId"
                    if (seenKeys.contains(sciKey) || seenKeys.contains(inatKey)) {
                        continue
                    }
                    seenKeys.add(sciKey)
                    seenKeys.add(inatKey)

                    val meta = MycoKnowledge.resolveMetadata(sciName)
                    val edibility = if (meta.edibility != "unknown") meta.edibility else rawEdibility
                    val hymenium = if (meta.hymenium != "other") meta.hymenium else if (rawHymenium.isNotBlank()) rawHymenium else "other"

                    val commonName = if (lang == "uk") {
                        nameUk ?: nameEn ?: sciName
                    } else {
                        nameEn ?: nameUk ?: sciName
                    }

                    val wikiSummary = if (lang == "uk") {
                        descUk ?: descEn
                    } else {
                        descEn ?: descUk
                    }

                    val wikiUrl = if (lang == "uk") {
                        wikiUrlUk ?: wikiUrlEn
                    } else {
                        wikiUrlEn ?: wikiUrlUk
                    }

                    dbIds.add(dbId)
                    resultList.add(
                        MushroomTaxon(
                            id = inatId,
                            scientificName = sciName,
                            commonName = commonName,
                            defaultPhotoUrl = null,
                            photoUrls = emptyList(),
                            wikipediaSummary = wikiSummary,
                            wikipediaUrl = wikiUrl,
                            edibility = edibility,
                            hymenium = hymenium,
                            observationsCount = observationsCount,
                            rank = "species",
                            family = family,
                            order = orderName,
                            genus = genus,
                            englishCommonName = nameEn,
                            conservationStatus = conservationStatus,
                            phylum = phylum,
                            taxonClass = taxonClass,
                            lookalikesUk = lookalikesUk ?: meta.lookalikesUk,
                            lookalikesEn = lookalikesEn ?: meta.lookalikesEn,
                            propsCap = propsCap ?: meta.propsUk?.cap,
                            propsHymenium = propsHymenium ?: meta.propsUk?.hymenophore,
                            propsStem = propsStem ?: meta.propsUk?.stem,
                            propsFlesh = propsFlesh ?: meta.propsUk?.flesh,
                            propsSeason = propsSeason ?: meta.propsUk?.season,
                            propsHabitat = propsHabitat ?: meta.propsUk?.habitat,
                            ecologyUk = ecologyUk,
                            ecologyEn = ecologyEn,
                            toxicityUk = toxicityUk,
                            toxicityEn = toxicityEn,
                            useUk = useUk,
                            useEn = useEn
                        )
                    )
                }
            }

            if (dbIds.isNotEmpty()) {
                val photoMap = HashMap<Int, MutableList<String>>(dbIds.size)
                val placeholders = dbIds.joinToString(",") { "?" }
                val args = dbIds.map { it.toString() }.toTypedArray()
                val photoCursor = db.rawQuery(
                    "SELECT taxon_id, id FROM photos WHERE taxon_id IN ($placeholders) ORDER BY taxon_id ASC, photo_index ASC",
                    args
                )
                photoCursor.use { pc ->
                    while (pc.moveToNext()) {
                        val tId = pc.getInt(0)
                        val pId = pc.getInt(1)
                        photoMap.getOrPut(tId) { mutableListOf() }.add("db://photo/$pId")
                    }
                }
                for (i in resultList.indices) {
                    val uris = photoMap[dbIds[i]]
                    if (!uris.isNullOrEmpty()) {
                        resultList[i] = resultList[i].copy(
                            defaultPhotoUrl = uris.first(),
                            photoUrls = uris
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        val hasMore = (offset + resultList.size) < totalCount
        return Pair(resultList, hasMore)
    }

    /**
     * Retrieves localized names (name_uk or name_en depending on lang) from taxa table for given scientific names.
     */
    fun getLocalizedNames(
        context: Context,
        scientificNames: Collection<String>,
        lang: String
    ): Map<String, String> {
        if (scientificNames.isEmpty()) return emptyMap()
        val db = getDatabase(context) ?: return emptyMap()
        val result = HashMap<String, String>(scientificNames.size)
        try {
            val distinctNames = scientificNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (distinctNames.isEmpty()) return emptyMap()
            val placeholders = distinctNames.joinToString(",") { "?" }
            val cursor = db.rawQuery(
                "SELECT LOWER(scientific_name), name_uk, name_en FROM taxa WHERE LOWER(scientific_name) IN ($placeholders)",
                distinctNames.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val sci = it.getString(0) ?: ""
                    val uk = it.getString(1)?.trim()?.takeIf { s -> s.isNotEmpty() && !s.equals("null", ignoreCase = true) }
                    val en = it.getString(2)?.trim()?.takeIf { s -> s.isNotEmpty() && !s.equals("null", ignoreCase = true) }
                    val chosen = if (lang == "uk") (uk ?: en) else (en ?: uk)
                    if (!chosen.isNullOrBlank()) {
                        result[sci] = chosen
                    }
                }
            }
        } catch (_: Exception) {}
        return result
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
}
