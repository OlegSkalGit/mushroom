package com.olegskal.mushroom.address

import com.olegskal.mushroom.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
            "https://gall.openstreetmap.de/api/interpreter",
            "https://lambert.openstreetmap.de/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://z.overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter"
        )
    }

    private val pool = Executors.newFixedThreadPool(DEFAULT_MIRRORS.size)

    data class MirrorRank(
        val mirror: String,
        val effectiveWaitSec: Int,
        val availableSlots: Int
    )

    private fun checkMirrorStatus(mirror: String): MirrorRank {
        val statusUrl = mirror.replace("/interpreter", "/status")
        return try {
            val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            var avail = 0
            val mAvail = Pattern.compile("(\\d+)\\s+slots?\\s+available\\s+now", Pattern.CASE_INSENSITIVE).matcher(text)
            if (mAvail.find()) {
                avail = mAvail.group(1)?.toIntOrNull() ?: 0
            }

            var waitSec = 0
            val mWait = Pattern.compile("in\\s+(\\d+)\\s+seconds", Pattern.CASE_INSENSITIVE).matcher(text)
            if (mWait.find()) {
                waitSec = mWait.group(1)?.toIntOrNull() ?: 0
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
            val futures = pool.invokeAll(tasks, 4000, TimeUnit.MILLISECONDS)
            futures.mapNotNull {
                try { it.get() } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val alive = results.filter { it.effectiveWaitSec < 9999 }.sortedBy { it.effectiveWaitSec }
        if (alive.isNotEmpty()) return alive
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
                logFn("  [⏳] Всі сервери зайняті. Очікуємо ${sleepSec}с на $bestMirror...")
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
                    val stream: InputStream = if (responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)

                    val rawBytes = readStreamToBytes(stream)
                    val decompressed = if (isGzip || (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte())) {
                        try {
                            decompressGzip(rawBytes)
                        } catch (_: Exception) {
                            rawBytes
                        }
                    } else rawBytes

                    val text = String(decompressed, Charsets.UTF_8)
                    if (text.isBlank()) {
                        logFn("  [-] $mirror: HTTP $responseCode — порожня відповідь. Спробуємо інше...")
                        interruptibleSleep(1000L, isCancelled)
                        continue
                    }

                    val textLow = text.lowercase()
                    if (textLow.contains("waiting for slot") || textLow.contains("slot available")) {
                        logFn("  [⏳] $mirror: Слот зайнято, пересканування...")
                        break
                    }

                    if (textLow.contains("<html") || textLow.contains("runtime error") || responseCode >= 400) {
                        if (textLow.contains("out of memory")) {
                            val (escalated, memStr) = escalateQueryMemory(activeQuery)
                            if (escalated != null) {
                                logFn("  [⚠️] $mirror: Out of memory. Ескалація ліміту RAM до $memStr [maxsize]...")
                                activeQuery = escalated
                                interruptibleSleep(2000L, isCancelled)
                                return query(activeQuery, maxOf(timeoutSec, 300), logFn, isCancelled, maxCycles)
                            }
                        }

                        if (textLow.contains("too busy") || responseCode == 429 || responseCode == 504) {
                            val waitTime = if (responseCode == 504 || textLow.contains("too busy")) 8000L else 3000L
                            logFn("  [⏳] $mirror: HTTP $responseCode ядро зайняте/rate limit. Пауза ${waitTime/1000}с...")
                            interruptibleSleep(waitTime, isCancelled)
                            break
                        }

                        logFn("  [-] $mirror: Помилка Overpass (HTTP $responseCode). Спробуємо інше...")
                        interruptibleSleep(1000L, isCancelled)
                        continue
                    }

                    logFn("  [+] $mirror: HTTP $responseCode OK — отримано ${text.lines().size} рядків (слотів: $freeSlots)")
                    return text

                } catch (e: Exception) {
                    AppLogger.log(TAG, "query", false, "Connection error to $mirror: ${e.message}")
                    interruptibleSleep(1500L, isCancelled)
                } finally {
                    conn?.disconnect()
                }
            }
        }
        return null
    }

    private fun interruptibleSleep(ms: Long, isCancelled: () -> Boolean): Boolean {
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
