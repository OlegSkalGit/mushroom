package com.olegskal.mushroom.address

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.mushrooms.MultiVolumeZipInputStream
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Менеджер альтернативного завантаження готових баз адрес для країн
 * та швидкої консолідації (злиття) з основною базою додатку addr.db.
 *
 * Не змінює і не зачіпає основний алгоритм парсингу PBF/Overpass.
 */
object AddressPrebuiltManager {

    private const val TAG = "AddressPrebuiltManager"

    const val BASE_DOWNLOAD_URL = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/downloads/addresses/"
    const val FALLBACK_DOWNLOAD_URL = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/downloads/"

    /**
     * Спроба завантажити готову базу країни з сервера або локального файлу,
     * розпакувати її та консолідувати в addr.db.
     *
     * @return true, якщо готова база знайдена, успішно розпакована та інтегрована.
     *         false, якщо готової бази немає (потрібно використати стандартний фолбек).
     */
    fun tryInstallPrebuiltCountry(
        context: Context,
        country: MapCountry,
        onBytes: (Long) -> Unit = {},
        onSizeDiscovered: (Long) -> Unit = {},
        onProgress: (ratio: Float, statusMsg: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val code = country.code.uppercase()

        // 1. Перевірка локальних архівів чи готової бази в папках завантажень або addresses
        val localArchiveOrDb = findLocalPrebuiltFile(country)
        if (localArchiveOrDb != null) {
            AppLogger.log(TAG, "tryInstallPrebuiltCountry", true, "Знайдено локальний файл готової бази: ${localArchiveOrDb.absolutePath}")
            val success = processLocalPrebuiltFile(context, localArchiveOrDb, country, onProgress, isCancelled)
            if (success) return true
        }

        if (isCancelled()) return false

        // 2. Пошук готового архіву на віддаленому сервері
        onProgress(0.01f, "Перевірка готової бази адрес для ${country.name}...")
        val remoteInfo = discoverRemoteArchive(country, isCancelled) ?: run {
            AppLogger.log(TAG, "tryInstallPrebuiltCountry", true, "Віддалена готова база для $code не знайдена на сервері. Працює стандартний механізм.")
            return false
        }

        if (isCancelled()) return false

        val (baseUrl, parts) = remoteInfo
        AppLogger.log(TAG, "tryInstallPrebuiltCountry", true, "Знайдено готову базу для $code на $baseUrl: ${parts.joinToString()}")

        // 3. Завантаження томів архіву
        val tmpDir = File(MushroomStorageManager.addressesDir, "tmp").apply { if (!exists()) mkdirs() }
        val downloadedParts = mutableListOf<File>()

        try {
            var totalDownloadedBytes = 0L
            for ((index, partName) in parts.withIndex()) {
                if (isCancelled()) return false
                val partFile = File(tmpDir, partName)
                val partUrl = "$baseUrl$partName"

                val success = downloadPartWithResume(
                    urlStr = partUrl,
                    destFile = partFile,
                    onBytesRead = { bytes ->
                        totalDownloadedBytes += bytes
                        onBytes(bytes)
                    },
                    onSizeDiscovered = { size ->
                        onSizeDiscovered(size)
                    },
                    onProgress = { partRatio ->
                        val overallRatio = ((index + partRatio) / parts.size.toFloat()) * 0.75f
                        val pct = (overallRatio * 100).toInt()
                        onProgress(overallRatio, "Завантаження бази адрес (${index + 1}/${parts.size}): $pct%")
                    },
                    isCancelled = isCancelled
                )

                if (!success || isCancelled()) {
                    return false
                }
                downloadedParts.add(partFile)
            }

            if (isCancelled()) {
                return false
            }

            // 4. Потокове розпакування через MultiVolumeZipInputStream
            onProgress(0.78f, "Розпакування бази адрес...")
            val unpackedDb = File(tmpDir, "${country.code.lowercase()}_prebuilt.db")
            if (unpackedDb.exists()) unpackedDb.delete()

            val unpacked = unpackArchive(downloadedParts, unpackedDb, onProgress, isCancelled)
            // Видаляємо томи архіву після розпакування, щоб не займати пам'ять
            cleanupFiles(downloadedParts)

            if (!unpacked || !unpackedDb.exists() || unpackedDb.length() < 1024L || isCancelled()) {
                if (unpackedDb.exists()) unpackedDb.delete()
                return false
            }

            // 5. Консолідація розпакованої бази в addr.db
            onProgress(0.90f, "Консолідація бази адрес...")
            val consolidated = consolidatePrebuiltDatabase(context, unpackedDb, country, onProgress)
            if (unpackedDb.exists()) unpackedDb.delete()

            if (consolidated) {
                onProgress(1.0f, "Готово!")
                AppLogger.log(TAG, "tryInstallPrebuiltCountry", true, "Успішно встановлено та консолідовано готову базу для ${country.code}!")
                return true
            }

            return false
        } catch (e: Exception) {
            AppLogger.log(TAG, "tryInstallPrebuiltCountry", false, "Помилка встановлення готової бази: ${e.message}")
            return false
        } finally {
            cleanupFiles(downloadedParts)
            try {
                if (tmpDir.exists()) {
                    tmpDir.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Пошук локального файлу бази чи архіву
     */
    private fun findLocalPrebuiltFile(country: MapCountry): File? {
        val candidates = listOf(
            File(MushroomStorageManager.addressesDir, "${country.code.lowercase()}.db"),
            File(MushroomStorageManager.addressesDir, "${country.name.lowercase()}.db"),
            File(MushroomStorageManager.addressesDir, "${country.code.lowercase()}.zip"),
            File(MushroomStorageManager.addressesDir, "${country.name.lowercase()}.zip"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${country.code.lowercase()}.db"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${country.name.lowercase()}.db"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${country.code.lowercase()}.zip"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${country.name.lowercase()}.zip")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 1024L }
    }

    private fun processLocalPrebuiltFile(
        context: Context,
        file: File,
        country: MapCountry,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        if (file.name.endsWith(".db", ignoreCase = true)) {
            onProgress(0.85f, "Консолідація локальної бази ${file.name}...")
            return consolidatePrebuiltDatabase(context, file, country, onProgress)
        } else if (file.name.endsWith(".zip", ignoreCase = true)) {
            val tmpDir = File(MushroomStorageManager.addressesDir, "tmp").apply { if (!exists()) mkdirs() }
            val unpackedDb = File(tmpDir, "${country.code.lowercase()}_prebuilt.db")
            if (unpackedDb.exists()) unpackedDb.delete()

            return try {
                val unpacked = unpackArchive(listOf(file), unpackedDb, onProgress, isCancelled)
                if (unpacked && unpackedDb.exists() && unpackedDb.length() > 1024L) {
                    onProgress(0.90f, "Консолідація бази ${file.name}...")
                    consolidatePrebuiltDatabase(context, unpackedDb, country, onProgress)
                } else {
                    false
                }
            } finally {
                try {
                    if (tmpDir.exists()) {
                        tmpDir.deleteRecursively()
                    }
                } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Пошук архіву на GitHub / віддаленому сервері.
     * Перевіряє як окремий .zip, так і багатотомні .z01 ... .zip
     */
    private fun discoverRemoteArchive(
        country: MapCountry,
        isCancelled: () -> Boolean
    ): Pair<String, List<String>>? {
        val baseUrls = listOf(BASE_DOWNLOAD_URL, FALLBACK_DOWNLOAD_URL)
        val nameCandidates = listOf(
            country.name.lowercase(Locale.US).trim(),
            country.code.lowercase(Locale.US).trim(),
            country.name.trim(),
            country.code.uppercase(Locale.US).trim()
        ).distinct()

        for (baseUrl in baseUrls) {
            for (name in nameCandidates) {
                if (isCancelled()) return null

                // 1. Перевірка багатотомника: чи є .z01
                val z01Name = "$name.z01"
                if (checkUrlExists("$baseUrl$z01Name")) {
                    val parts = mutableListOf<String>()
                    parts.add(z01Name)
                    var index = 2
                    while (true) {
                        if (isCancelled()) return null
                        val nextPart = String.format(Locale.US, "%s.z%02d", name, index)
                        if (checkUrlExists("$baseUrl$nextPart")) {
                            parts.add(nextPart)
                            index++
                        } else {
                            break
                        }
                    }
                    val finalZip = "$name.zip"
                    if (checkUrlExists("$baseUrl$finalZip")) {
                        parts.add(finalZip)
                        return Pair(baseUrl, parts)
                    }
                }

                // 2. Перевірка одиничного архіву: чи є .zip
                val singleZip = "$name.zip"
                if (checkUrlExists("$baseUrl$singleZip")) {
                    return Pair(baseUrl, listOf(singleZip))
                }
            }
        }
        return null
    }

    private fun checkUrlExists(urlStr: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mushroom/2.0")
            }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun downloadPartWithResume(
        urlStr: String,
        destFile: File,
        onBytesRead: (Long) -> Unit,
        onSizeDiscovered: (Long) -> Unit,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        var attempts = 0

        while (attempts < 3) {
            attempts++
            if (isCancelled()) return false
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            var output: FileOutputStream? = null

            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mushroom/2.0")
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val responseCode = conn.responseCode
                val contentLength = conn.contentLength.toLong()
                val totalExpected = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    existingBytes + contentLength
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    contentLength
                } else {
                    -1L
                }

                if (totalExpected > 0L) {
                    onSizeDiscovered(totalExpected)
                }

                val append = (responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                input = conn.inputStream
                output = FileOutputStream(partFile, append)

                val buffer = ByteArray(32768)
                var bytesRead: Int
                var currentTotal = if (append) existingBytes else 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled()) {
                        output.flush()
                        output.close()
                        input.close()
                        conn.disconnect()
                        return false
                    }
                    output.write(buffer, 0, bytesRead)
                    currentTotal += bytesRead
                    onBytesRead(bytesRead.toLong())

                    if (totalExpected > 0L) {
                        val ratio = (currentTotal.toFloat() / totalExpected.toFloat()).coerceIn(0f, 1f)
                        onProgress(ratio)
                    }
                }

                output.flush()
                output.close()
                input.close()
                conn.disconnect()

                if (destFile.exists()) destFile.delete()
                if (partFile.renameTo(destFile)) {
                    return true
                } else {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                    return true
                }
            } catch (e: Exception) {
                try { output?.close() } catch (_: Exception) {}
                try { input?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
                if (attempts >= 3) return false
                try { Thread.sleep(1500) } catch (_: Exception) { return false }
            }
        }
        return false
    }

    /**
     * Потокове розпакування архіву (одно- чи багатотомного) у вихідний SQLite файл.
     */
    private fun unpackArchive(
        parts: List<File>,
        targetDbFile: File,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        var multiStream: InputStream? = null
        var zipIn: ZipInputStream? = null
        var fos: FileOutputStream? = null

        return try {
            multiStream = if (parts.size == 1) FileInputStream(parts[0]) else MultiVolumeZipInputStream(parts)
            zipIn = ZipInputStream(multiStream)
            var entry = zipIn.nextEntry
            val buffer = ByteArray(65536)

            while (entry != null) {
                if (isCancelled()) return false
                val eName = entry.name.lowercase(Locale.US)
                if (!entry.isDirectory && (eName.endsWith(".db") || eName.endsWith(".sqlite"))) {
                    fos = FileOutputStream(targetDbFile)
                    var len: Int
                    var totalExtracted = 0L
                    var lastReport = 0L

                    while (zipIn.read(buffer).also { len = it } != -1) {
                        if (isCancelled()) return false
                        fos.write(buffer, 0, len)
                        totalExtracted += len
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200L) {
                            lastReport = now
                            val mb = totalExtracted / (1024 * 1024)
                            onProgress(0.82f, "Розпаковано: $mb МБ...")
                        }
                    }
                    fos.flush()
                    fos.close()
                    fos = null
                    break
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            targetDbFile.exists() && targetDbFile.length() > 1024L
        } catch (e: Exception) {
            AppLogger.log(TAG, "unpackArchive", false, "Помилка розпакування: ${e.message}")
            false
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { zipIn?.close() } catch (_: Exception) {}
            try { multiStream?.close() } catch (_: Exception) {}
        }
    }

    private fun hasAnyCountriesInDatabase(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() < 1024L) return false
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                val c = it.rawQuery("SELECT 1 FROM admin_places WHERE level = 2 LIMIT 1;", null)
                c.use { cur -> cur.moveToFirst() }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Консолідація (злиття) розпакованої готової бази країни з основною addr.db.
     *
     * 1. Якщо addr.db відсутня або в ній немає жодної країни -> пряме перейменування/заміна (миттєво).
     * 2. Якщо addr.db вже існує з іншими країнами -> підключення через SQLite ATTACH, тимчасовий дроп індексів,
     *    зміщення ID (Shift Offset) первинних та зовнішніх ключів, усунення дублікатів назв вулиць,
     *    переливання даних у єдиній транзакції та відновлення індексів.
     */
    fun consolidatePrebuiltDatabase(
        context: Context,
        sourceDbFile: File,
        country: MapCountry,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean {
        val targetFile = AddressDb.getDatabaseFile(context)
        val hasExistingCountries = hasAnyCountriesInDatabase(targetFile)

        // СЦЕНАРІЙ А: Бази немає або в ній немає жодної країни — просте перейменування
        if (!hasExistingCountries) {
            onProgress(0.92f, "Ініціалізація нової бази адрес...")
            AddressDb.closeDatabase()
            targetFile.parentFile?.mkdirs()

            File(targetFile.parentFile, "${targetFile.name}-wal").delete()
            File(targetFile.parentFile, "${targetFile.name}-shm").delete()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val moved = if (sourceDbFile.renameTo(targetFile)) {
                true
            } else {
                sourceDbFile.copyTo(targetFile, overwrite = true)
                sourceDbFile.delete()
                true
            }

            if (!moved || !targetFile.exists()) return false

            val db = AddressDb.openDatabase(context, writable = true) ?: return false
            AddressDb.ensureTablesExist(db)
            AddressDb.optimizeAndVacuum(db)
            return true
        }

        // СЦЕНАРІЙ Б: Консолідація нової країни з існуючою базою addr.db
        onProgress(0.92f, "Злиття з існуючою адресною базою...")
        AddressDb.closeDatabase()
        val targetDb = AddressDb.openDatabase(context, writable = true) ?: return false

        try {
            targetDb.execSQL("PRAGMA foreign_keys = OFF;")
            targetDb.execSQL("PRAGMA synchronous = NORMAL;")

            // Отримуємо код та назву країни із вхідної бази для запобігання дублюванню
            val inCountryCode = country.code.uppercase(Locale.US)
            val inCountryName = country.name

            // 1. Очищення старих даних для цієї країни, якщо вони вже були в базі
            val oldCountryId = try {
                val cur = targetDb.rawQuery(
                    "SELECT id FROM admin_places WHERE level = 2 AND (UPPER(code) = ? OR UPPER(name) = ? OR UPPER(name) = ?) LIMIT 1;",
                    arrayOf(inCountryCode, inCountryName.uppercase(Locale.US), country.nameUk.uppercase(Locale.US))
                )
                cur.use { if (it.moveToFirst()) it.getLong(0) else null }
            } catch (_: Exception) { null }

            if (oldCountryId != null) {
                onProgress(0.93f, "Очищення попередньої версії ${country.name}...")
                targetDb.execSQL("DELETE FROM houses WHERE street_id IN (SELECT s.id FROM streets s WHERE s.country_id = ?);", arrayOf(oldCountryId.toString()))
                targetDb.execSQL("DELETE FROM search_fts WHERE rowid IN (SELECT s.id FROM streets s WHERE s.country_id = ?);", arrayOf(oldCountryId.toString()))
                targetDb.execSQL("DELETE FROM poi_fts WHERE rowid IN (SELECT id FROM poi_places WHERE country_code = ?);", arrayOf(inCountryCode))
                targetDb.execSQL("DELETE FROM poi_places WHERE country_code = ?;", arrayOf(inCountryCode))
                targetDb.execSQL("DELETE FROM streets WHERE country_id = ?;", arrayOf(oldCountryId.toString()))
                targetDb.execSQL("DELETE FROM admin_places WHERE parent_id IN (SELECT id FROM admin_places WHERE parent_id = ?);", arrayOf(oldCountryId.toString()))
                targetDb.execSQL("DELETE FROM admin_places WHERE id = ? OR parent_id = ?;", arrayOf(oldCountryId.toString(), oldCountryId.toString()))
            }

            // 2. Тимчасовий ДРОП індексів для максимальної швидкості масової вставки
            onProgress(0.94f, "Оптимізація індексів для масової вставки...")
            targetDb.execSQL("DROP INDEX IF EXISTS idx_streets_place_id;")
            targetDb.execSQL("DROP INDEX IF EXISTS idx_streets_name_id;")
            targetDb.execSQL("DROP INDEX IF EXISTS idx_admin_places_parent;")
            targetDb.execSQL("DROP INDEX IF EXISTS idx_poi_places_street;")
            targetDb.execSQL("DROP INDEX IF EXISTS idx_poi_places_country;")

            // 3. Підключення вхідної бази
            targetDb.execSQL("ATTACH DATABASE ? AS src;", arrayOf(sourceDbFile.absolutePath))

            val hasSrcPlaces = hasTableInSchema(targetDb, "src", "admin_places")
            val hasSrcNames = hasTableInSchema(targetDb, "src", "street_names")
            val hasSrcStreets = hasTableInSchema(targetDb, "src", "streets")
            val hasSrcHouses = hasTableInSchema(targetDb, "src", "houses")
            val hasSrcPoi = hasTableInSchema(targetDb, "src", "poi_places")

            if (!hasSrcPlaces || !hasSrcStreets || !hasSrcHouses) {
                targetDb.execSQL("DETACH DATABASE src;")
                restoreIndices(targetDb)
                return false
            }

            // 4. Розрахунок зміщення ID (Shift Offsets)
            val adminOffset = queryMaxId(targetDb, "admin_places")
            val streetOffset = queryMaxId(targetDb, "streets")
            val poiOffset = queryMaxId(targetDb, "poi_places")

            onProgress(0.96f, "Переливання адрес та об'єктів...")

            targetDb.beginTransaction()
            try {
                // А. admin_places (зберігаємо ієрархічне дерево через зміщення parent_id)
                targetDb.execSQL("""
                    INSERT INTO admin_places (id, osm_id, parent_id, level, name, code)
                    SELECT id + $adminOffset, osm_id, 
                           CASE WHEN parent_id IS NOT NULL THEN parent_id + $adminOffset ELSE NULL END, 
                           level, name, code
                    FROM src.admin_places;
                """.trimIndent())

                // Б. street_names (усуваємо дублікати та будуємо швидкий тимчасовий мапінг)
                if (hasSrcNames) {
                    targetDb.execSQL("INSERT OR IGNORE INTO street_names (name) SELECT name FROM src.street_names;")
                    targetDb.execSQL("CREATE TEMP TABLE temp_name_map (src_id INTEGER PRIMARY KEY, dst_id INTEGER);")
                    targetDb.execSQL("""
                        INSERT INTO temp_name_map (src_id, dst_id)
                        SELECT s.id, t.id
                        FROM src.street_names s
                        JOIN street_names t ON s.name = t.name;
                    """.trimIndent())
                }

                // В. streets (прив'язуємо до нових зміщених ID міст, областей, країн та назв вулиць)
                if (hasSrcNames) {
                    targetDb.execSQL("""
                        INSERT INTO streets (id, place_id, region_id, country_id, name_id)
                        SELECT 
                            s.id + $streetOffset,
                            s.place_id + $adminOffset,
                            CASE WHEN s.region_id IS NOT NULL THEN s.region_id + $adminOffset ELSE NULL END,
                            s.country_id + $adminOffset,
                            m.dst_id
                        FROM src.streets s
                        JOIN temp_name_map m ON s.name_id = m.src_id;
                    """.trimIndent())
                    targetDb.execSQL("DROP TABLE IF EXISTS temp_name_map;")
                } else {
                    targetDb.execSQL("""
                        INSERT INTO streets (id, place_id, region_id, country_id, name_id)
                        SELECT 
                            s.id + $streetOffset,
                            s.place_id + $adminOffset,
                            CASE WHEN s.region_id IS NOT NULL THEN s.region_id + $adminOffset ELSE NULL END,
                            s.country_id + $adminOffset,
                            s.name_id
                        FROM src.streets s;
                    """.trimIndent())
                }

                // Г. houses (переливаємо будинки з оновленим street_id)
                targetDb.execSQL("""
                    INSERT OR REPLACE INTO houses (street_id, number, lat, lon)
                    SELECT street_id + $streetOffset, number, lat, lon
                    FROM src.houses;
                """.trimIndent())

                // Д. poi_places (переливаємо точки POI зі зміщеними ID)
                if (hasSrcPoi) {
                    targetDb.execSQL("""
                        INSERT INTO poi_places (id, name, category_id, street_id, house_number, lat, lon, country_code)
                        SELECT 
                            id + $poiOffset,
                            name,
                            category_id,
                            CASE WHEN street_id IS NOT NULL THEN street_id + $streetOffset ELSE NULL END,
                            house_number,
                            lat,
                            lon,
                            country_code
                        FROM src.poi_places;
                    """.trimIndent())
                }

                // Е. search_fts (поповнюємо повнотекстовий індекс для нових вулиць)
                targetDb.execSQL("""
                    INSERT INTO search_fts (rowid, region_name, city_name, street_name)
                    SELECT s.id, LOWER(COALESCE(reg.name, '')), LOWER(city.name), LOWER(sn.name)
                    FROM streets s
                    JOIN street_names sn ON s.name_id = sn.id
                    JOIN admin_places city ON s.place_id = city.id
                    LEFT JOIN admin_places reg ON s.region_id = reg.id
                    WHERE s.id > $streetOffset;
                """.trimIndent())

                // Ж. poi_fts (поповнюємо індекс для нових POI)
                if (hasSrcPoi) {
                    targetDb.execSQL("""
                        INSERT INTO poi_fts (rowid, poi_name, category_name, city_name, street_name)
                        SELECT pp.id, pp.name, COALESCE(pp.category_id, ''), COALESCE(city.name, ''), COALESCE(sn.name, '')
                        FROM poi_places pp
                        LEFT JOIN streets s ON s.id = pp.street_id
                        LEFT JOIN street_names sn ON sn.id = s.name_id
                        LEFT JOIN admin_places city ON city.id = s.place_id
                        WHERE pp.id > $poiOffset;
                    """.trimIndent())
                }

                targetDb.setTransactionSuccessful()
            } finally {
                targetDb.endTransaction()
            }

            // 5. Відключення бази-донора
            targetDb.execSQL("DETACH DATABASE src;")

            // 6. Відновлення індексів
            onProgress(0.98f, "Відновлення індексів...")
            restoreIndices(targetDb)
            targetDb.execSQL("PRAGMA foreign_keys = ON;")

            // 7. Оптимізація та збереження
            AddressDb.optimizeAndVacuum(targetDb)
            return true
        } catch (e: Exception) {
            AppLogger.log(TAG, "consolidatePrebuiltDatabase", false, "Помилка консолідації: ${e.message}")
            try { targetDb.execSQL("DETACH DATABASE src;") } catch (_: Exception) {}
            restoreIndices(targetDb)
            return false
        }
    }

    private fun restoreIndices(db: SQLiteDatabase) {
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_streets_place_id ON streets(place_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_streets_name_id ON streets(name_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_admin_places_parent ON admin_places(parent_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_street ON poi_places(street_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_places_country ON poi_places(country_code);")
        } catch (e: Exception) {
            AppLogger.log(TAG, "restoreIndices", false, "Помилка створення індексів: ${e.message}")
        }
    }

    private fun queryMaxId(db: SQLiteDatabase, table: String): Long {
        return try {
            val cursor = db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM $table;", null)
            cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
        } catch (_: Exception) {
            0L
        }
    }

    private fun hasTableInSchema(db: SQLiteDatabase, schema: String, table: String): Boolean {
        return try {
            val cursor = db.rawQuery("SELECT 1 FROM $schema.sqlite_master WHERE type='table' AND name=? LIMIT 1;", arrayOf(table))
            cursor.use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupFiles(files: List<File>) {
        for (f in files) {
            try {
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
    }
}
