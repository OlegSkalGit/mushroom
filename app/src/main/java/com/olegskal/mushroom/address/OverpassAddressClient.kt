package com.olegskal.mushroom.address

import com.olegskal.mushroom.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

class OverpassAddressClient(
    private val mirrors: List<String> = DEFAULT_MIRRORS
) {
    companion object {
        private const val TAG = "OverpassClient"
        const val USER_AGENT = "Mushroom-Address-Tool/2.0 (Android; https://github.com/OlegSkalGit/mushroom)"
        val DEFAULT_MIRRORS = listOf(
            "https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://gall.openstreetmap.de/api/interpreter",
            "https://lambert.openstreetmap.de/api/interpreter",
            "https://z.overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter"
        )
        private val pool = Executors.newFixedThreadPool(DEFAULT_MIRRORS.size) { r ->
            Thread(r, "OverpassMirrorCheck").apply { isDaemon = true }
        }
    }

    data class MirrorRank(
        val mirror: String,
        val effectiveWaitSec: Int,
        val availableSlots: Int
    )

    private fun checkMirrorStatus(mirror: String): MirrorRank {
        val statusUrl = mirror.replace("/interpreter", "/status")
        return try {
            val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 400..499) {
                // Сервери (як-от openstreetmap.fr з 403), які не мають окремого публічного /status, але активні
                conn.disconnect()
                return MirrorRank(mirror, 0, 1)
            }
            if (code !in 200..299) {
                conn.disconnect()
                return MirrorRank(mirror, 9999, 0)
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val lines = text.lines()
            var avail = 0
            var waitSec = 0
            for (line in lines) {
                val mAvail = Pattern.compile("(\\d+)\\s+slots?\\s+available").matcher(line)
                if (mAvail.find()) avail = mAvail.group(1)?.toIntOrNull() ?: 0
                val mWait = Pattern.compile("Slot available after:\\s+.*in\\s+(\\d+)\\s+seconds").matcher(line)
                if (mWait.find()) waitSec = mWait.group(1)?.toIntOrNull() ?: 0
            }
            val effWait = if (avail > 0) 0 else (if (waitSec > 0) waitSec else 60)
            MirrorRank(mirror, effWait, avail)
        } catch (_: Exception) {
            MirrorRank(mirror, 9999, 0)
        }
    }

    fun findBestMirrors(): List<MirrorRank> {
        val tasks = mirrors.map { m -> Callable { checkMirrorStatus(m) } }
        val results = try {
            val futures = pool.invokeAll(tasks, 2500, TimeUnit.MILLISECONDS)
            futures.mapNotNull {
                try { it.get() } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val alive = results.filter { it.effectiveWaitSec < 9999 }.sortedBy { it.effectiveWaitSec }
        if (alive.isNotEmpty() && alive.any { it.effectiveWaitSec == 0 }) {
            return alive
        }

        // Якщо всі зайняті (таймаут) або без слотів — пробуємо всі відомі сервери
        return mirrors.map { MirrorRank(it, 0, 1) }
    }

    private fun escalateQueryMemory(query: String): Pair<String?, String?> {
        val m = Pattern.compile("\\[maxsize:(\\d+)\\]").matcher(query)
        return if (!m.find()) {
            val newQ = if (query.contains("[timeout:")) {
                query.replace(Regex("(\\[timeout:\\d+\\])"), "$1[maxsize:1073741824]")
            } else {
                "[maxsize:1073741824]$query"
            }
            Pair(newQ, "1 ГБ")
        } else {
            val currentBytes = m.group(1)?.toLongOrNull() ?: 0L
            if (currentBytes < 1073741824L) {
                Pair(query.replace(Regex("\\[maxsize:\\d+\\]"), "[maxsize:1073741824]"), "1 ГБ")
            } else if (currentBytes < 2147483648L) {
                Pair(query.replace(Regex("\\[maxsize:\\d+\\]"), "[maxsize:2147483648]"), "2 ГБ")
            } else {
                Pair(null, null)
            }
        }
    }

    fun query(
        qlQuery: String,
        timeoutSec: Int = 90,
        logFn: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        maxCycles: Int = 3
    ): String? {
        var activeQuery = qlQuery

        for (cycle in 1..maxCycles) {
            if (isCancelled()) return null

            val ranked = findBestMirrors()
            if (ranked.isEmpty()) break

            val (bestMirror, minWait, avail) = ranked.first()
            if (avail == 0 && minWait > 0) {
                val sleepSec = minOf(minWait + 2, 60)
                AppLogger.log(TAG, "query", true, "Всі сервери зайняті. Очікуємо ${sleepSec}с на $bestMirror...")
                if (interruptibleSleep(sleepSec * 1000L, isCancelled)) return null
            }

            for ((mirror, _, freeSlots) in ranked) {
                if (isCancelled()) return null

                var conn: HttpURLConnection? = null
                try {
                    val encodedData = "data=" + URLEncoder.encode(activeQuery, "UTF-8")
                    val postBytes = encodedData.toByteArray(Charsets.UTF_8)

                    conn = (URL(mirror).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = timeoutSec * 1000
                        doOutput = true
                        setRequestProperty("User-Agent", USER_AGENT)
                        setRequestProperty("Accept-Encoding", "gzip, deflate")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("Content-Length", postBytes.size.toString())
                    }

                    conn.outputStream.use { it.write(postBytes) }

                    val responseCode = conn.responseCode
                    val isGzip = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
                    val stream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream

                    if (stream == null) {
                        AppLogger.log(TAG, "query", false, "$mirror: HTTP $responseCode — з'єднання скинуто/без тіла. Наступне дзеркало...")
                        interruptibleSleep(1000L, isCancelled)
                        continue
                    }

                    val rawBytes = readStreamToBytes(stream)
                    val decompressed = if (isGzip || (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte())) {
                        try {
                            decompressGzip(rawBytes)
                        } catch (_: Exception) {
                            rawBytes
                        }
                    } else rawBytes

                    val text = String(decompressed, Charsets.UTF_8)
                    val textLow = text.lowercase()

                    if (text.isBlank()) {
                        AppLogger.log(TAG, "query", false, "$mirror: HTTP $responseCode — порожня відповідь. Спробуємо інше...")
                        interruptibleSleep(1000L, isCancelled)
                        continue
                    }

                    if (textLow.contains("out of memory")) {
                        val (escalated, memStr) = escalateQueryMemory(activeQuery)
                        if (escalated != null) {
                            AppLogger.log(TAG, "query", true, "$mirror: Out of memory. Ескалація ліміту RAM до $memStr [maxsize]...")
                            activeQuery = escalated
                            interruptibleSleep(2000L, isCancelled)
                            return query(activeQuery, maxOf(timeoutSec, 300), logFn, isCancelled, maxCycles)
                        }
                    }

                    if (responseCode == 429 || textLow.contains("waiting for slot") || textLow.contains("slot available") || textLow.contains("rate_limited")) {
                        AppLogger.log(TAG, "query", true, "$mirror: HTTP $responseCode — слот зайнято. Пробуємо наступне дзеркало...")
                        interruptibleSleep(1500L, isCancelled)
                        continue
                    }

                    if (responseCode == 504) {
                        AppLogger.log(TAG, "query", false, "$mirror: HTTP 504 Gateway Timeout. Спробуємо інше дзеркало...")
                        interruptibleSleep(1500L, isCancelled)
                        continue
                    }

                    if (responseCode !in 200..299 || textLow.contains("<html") || textLow.contains("runtime error")) {
                        AppLogger.log(TAG, "query", false, "$mirror: HTTP $responseCode помилка. Наступне дзеркало...")
                        val sleepMs = if (responseCode == 500) 500L else 1500L
                        interruptibleSleep(sleepMs, isCancelled)
                        continue
                    }

                    AppLogger.log(TAG, "query", true, "$mirror: HTTP $responseCode OK — отримано ${text.lines().size} рядків (слотів: $freeSlots)")
                    return text

                } catch (e: Exception) {
                    AppLogger.log(TAG, "query", false, "Connection error to $mirror: ${e.message}")
                    interruptibleSleep(3000L, isCancelled)
                    continue
                } finally {
                    conn?.disconnect()
                }
            }

            if (cycle < maxCycles && !isCancelled()) {
                interruptibleSleep((4000L * cycle).coerceAtMost(15000L), isCancelled)
            }
        }
        return null
    }

    fun queryToFile(
        qlQuery: String,
        targetFile: File,
        timeoutSec: Int = 90,
        logFn: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        maxCycles: Int = 3
    ): Boolean {
        var activeQuery = qlQuery

        for (cycle in 1..maxCycles) {
            if (isCancelled()) return false

            val ranked = findBestMirrors()
            if (ranked.isEmpty()) break

            val (bestMirror, minWait, avail) = ranked.first()
            if (avail == 0 && minWait > 0) {
                val sleepSec = minOf(minWait + 2, 60)
                AppLogger.log(TAG, "queryToFile", true, "Всі сервери зайняті. Очікуємо ${sleepSec}с на $bestMirror...")
                if (interruptibleSleep(sleepSec * 1000L, isCancelled)) return false
            }

            for ((mirror, _, freeSlots) in ranked) {
                if (isCancelled()) return false

                var conn: HttpURLConnection? = null
                try {
                    val encodedData = "data=" + URLEncoder.encode(activeQuery, "UTF-8")
                    val postBytes = encodedData.toByteArray(Charsets.UTF_8)

                    conn = (URL(mirror).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = timeoutSec * 1000
                        doOutput = true
                        setRequestProperty("User-Agent", USER_AGENT)
                        setRequestProperty("Accept-Encoding", "gzip, deflate")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("Content-Length", postBytes.size.toString())
                    }

                    conn.outputStream.use { it.write(postBytes) }

                    val responseCode = conn.responseCode
                    val isGzip = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
                    val stream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream

                    if (stream == null) {
                        AppLogger.log(TAG, "queryToFile", false, "$mirror: HTTP $responseCode — з'єднання скинуто. Наступне дзеркало...")
                        interruptibleSleep(1000L, isCancelled)
                        continue
                    }

                    if (responseCode !in 200..299) {
                        val errBytes = readStreamToBytes(stream)
                        val errText = String(errBytes, Charsets.UTF_8).lowercase()
                        if (responseCode == 429 || errText.contains("waiting for slot") || errText.contains("slot available") || errText.contains("rate_limited")) {
                            AppLogger.log(TAG, "queryToFile", true, "$mirror: HTTP $responseCode — слот зайнято. Наступне дзеркало...")
                            interruptibleSleep(1500L, isCancelled)
                            continue
                        }
                        if (responseCode == 504) {
                            AppLogger.log(TAG, "queryToFile", false, "$mirror: HTTP 504 Gateway Timeout. Спробуємо інше...")
                            interruptibleSleep(1500L, isCancelled)
                            continue
                        }
                        AppLogger.log(TAG, "queryToFile", false, "$mirror: HTTP $responseCode помилка. Наступне дзеркало...")
                        val sleepMs = if (responseCode == 500) 500L else 1500L
                        interruptibleSleep(sleepMs, isCancelled)
                        continue
                    }

                    targetFile.parentFile?.mkdirs()
                    val bis = BufferedInputStream(stream)
                    bis.mark(2)
                    val b1 = bis.read()
                    val b2 = bis.read()
                    bis.reset()
                    val isGz = isGzip || (b1 == 0x1f && b2 == 0x8b)
                    val actualStream: InputStream = if (isGz) GZIPInputStream(bis) else bis

                    FileOutputStream(targetFile).use { fos ->
                        val buf = ByteArray(32768)
                        var len: Int
                        while (actualStream.read(buf).also { len = it } != -1) {
                            if (isCancelled()) {
                                targetFile.delete()
                                return false
                            }
                            fos.write(buf, 0, len)
                        }
                    }

                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        AppLogger.log(TAG, "queryToFile", false, "$mirror: HTTP $responseCode — порожня відповідь. Спробуємо інше...")
                        targetFile.delete()
                        interruptibleSleep(1000L, isCancelled)
                        continue
                    }

                    if (targetFile.length() < 2048L) {
                        val head = targetFile.readText(Charsets.UTF_8).lowercase()
                        if (head.contains("out of memory")) {
                            targetFile.delete()
                            val (escalated, memStr) = escalateQueryMemory(activeQuery)
                            if (escalated != null) {
                                AppLogger.log(TAG, "queryToFile", true, "$mirror: Out of memory. Ескалація RAM до $memStr [maxsize]...")
                                activeQuery = escalated
                                interruptibleSleep(2000L, isCancelled)
                                return queryToFile(activeQuery, targetFile, maxOf(timeoutSec, 300), logFn, isCancelled, maxCycles)
                            }
                            continue
                        }
                        if (head.contains("<html") || head.contains("runtime error")) {
                            targetFile.delete()
                            AppLogger.log(TAG, "queryToFile", false, "$mirror: помилка в HTML тілі. Спробуємо інше...")
                            interruptibleSleep(1500L, isCancelled)
                            continue
                        }
                    } else {
                        val isJson = activeQuery.contains("[out:json]", ignoreCase = true)
                        val isCsv = activeQuery.contains("[out:csv", ignoreCase = true)
                        if (isJson || isCsv) {
                            val tailText = try {
                                java.io.RandomAccessFile(targetFile, "r").use { raf ->
                                    val len = raf.length()
                                    val readLen = minOf(len, 512L).toInt()
                                    raf.seek(len - readLen)
                                    val buf = ByteArray(readLen)
                                    raf.readFully(buf)
                                    String(buf, Charsets.UTF_8).lowercase()
                                }
                            } catch (_: Exception) { "" }

                            val isBroken = when {
                                tailText.contains("runtime error") || tailText.contains("timed out") -> true
                                isJson && !tailText.trimEnd().endsWith("}") -> true
                                else -> false
                            }

                            if (isBroken) {
                                targetFile.delete()
                                AppLogger.log(TAG, "queryToFile", false, "$mirror: обірваний або помилковий потік в кінці відповіді. Спробуємо інше дзеркало...")
                                interruptibleSleep(2500L, isCancelled)
                                continue
                            }
                        }
                    }

                    AppLogger.log(TAG, "queryToFile", true, "$mirror: HTTP $responseCode OK — збережено ${targetFile.length()} байт (слотів: $freeSlots)")
                    return true

                } catch (e: Exception) {
                    AppLogger.log(TAG, "queryToFile", false, "Connection error to $mirror: ${e.message}")
                    targetFile.delete()
                    interruptibleSleep(3000L, isCancelled)
                    continue
                } finally {
                    conn?.disconnect()
                }
            }

            if (cycle < maxCycles && !isCancelled()) {
                interruptibleSleep((4000L * cycle).coerceAtMost(15000L), isCancelled)
            }
        }
        targetFile.delete()
        return false
    }

    fun interruptibleSleep(ms: Long, isCancelled: () -> Boolean): Boolean {
        val step = 400L
        var waited = 0L
        while (waited < ms) {
            if (isCancelled()) return true
            try { Thread.sleep(step) } catch (_: Exception) { return true }
            waited += step
        }
        return isCancelled()
    }

    private fun readStreamToBytes(stream: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var len: Int
        while (stream.read(buf).also { len = it } != -1) {
            bos.write(buf, 0, len)
        }
        return bos.toByteArray()
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray {
        GZIPInputStream(compressed.inputStream()).use { gz ->
            return readStreamToBytes(gz)
        }
    }
}
