package com.olegskal.mushroom.mushrooms

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class MushroomPrediction(
    val species: String,
    val confidence: Float,
    val edibility: String = "unknown",
    val hymenium: String = "other",
    val beitConfidence: Float = 0f,
    val swinConfidence: Float = 0f
) {
    val scientificName: String get() = species
}

class MushroomClassifier(private val context: Context? = null) {

    enum class ModelStatus {
        MISSING,
        NEEDS_UPDATE,
        READY
    }

    companion object {
        val MODEL_PARTS get() = MushroomDataConfig.MODEL_PARTS
        val MODEL_TOTAL_ARCHIVE_SIZE get() = MushroomDataConfig.MODEL_TOTAL_ARCHIVE_SIZE
        val MODEL_BASE_DOWNLOAD_URL get() = MushroomDataConfig.MODEL_BASE_DOWNLOAD_URL
        val MODEL_FALLBACK_DOWNLOAD_URL get() = MushroomDataConfig.MODEL_FALLBACK_DOWNLOAD_URL
        val REQUIRED_FILES get() = MushroomDataConfig.MODEL_REQUIRED_FILES

        fun getModelDirectory(): File {
            val sdDir = File(Environment.getExternalStorageDirectory(), "mushroom/${MushroomDataConfig.MODEL_DIR_NAME}")
            if (!sdDir.exists()) {
                sdDir.mkdirs()
            }
            return sdDir
        }

        fun getRequiredFile(context: Context, fileName: String): File {
            val primary = File(getModelDirectory(), fileName)
            if (primary.exists() && primary.length() > 0) return primary

            val extFiles = context.getExternalFilesDir(null)
            if (extFiles != null) {
                val extFile = File(extFiles, "${MushroomDataConfig.MODEL_DIR_NAME}/$fileName")
                if (extFile.exists() && extFile.length() > 0) return extFile
            }

            val internal = File(context.filesDir, "${MushroomDataConfig.MODEL_DIR_NAME}/$fileName")
            if (internal.exists() && internal.length() > 0) return internal

            return primary
        }

        fun checkModelStatus(context: Context): ModelStatus {
            for (item in REQUIRED_FILES) {
                val file = getRequiredFile(context, item.fileName)
                if (!file.exists() || file.length() == 0L) {
                    return ModelStatus.MISSING
                }
            }

            for (item in REQUIRED_FILES) {
                val file = getRequiredFile(context, item.fileName)
                if (file.length() != item.exactSize) {
                    return ModelStatus.NEEDS_UPDATE
                }
            }

            return ModelStatus.READY
        }

        fun isModelDownloaded(context: Context): Boolean {
            return checkModelStatus(context) == ModelStatus.READY
        }

        fun isModelAvailable(context: Context): Boolean {
            for (item in REQUIRED_FILES) {
                val file = getRequiredFile(context, item.fileName)
                if (!file.exists() || file.length() == 0L) {
                    return false
                }
            }
            return true
        }

        fun downloadModel(
            context: Context,
            forceDownload: Boolean = false,
            onProgress: (percent: Int, statusText: String) -> Unit,
            onDetailedProgress: ((fileName: String, bytesDownloaded: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int, percent: Int) -> Unit)? = null,
            onComplete: (Boolean, String?) -> Unit
        ) {
            Thread {
                if (!forceDownload && isModelDownloaded(context)) {
                    onComplete(true, null)
                    return@Thread
                }

                val targetDir = try {
                    val sd = getModelDirectory()
                    if (!sd.exists()) sd.mkdirs()
                    if (sd.canWrite()) sd else File(context.filesDir, MushroomDataConfig.MODEL_DIR_NAME).apply { mkdirs() }
                } catch (e: Exception) {
                    File(context.filesDir, MushroomDataConfig.MODEL_DIR_NAME).apply { mkdirs() }
                }

                val downloadedPartFiles = mutableListOf<File>()
                var downloadedTotalBytes = 0L

                for ((index, part) in MODEL_PARTS.withIndex()) {
                    val (fileName, expectedSize) = part
                    val partFile = File(targetDir, fileName)
                    downloadedPartFiles.add(partFile)

                    if (forceDownload && partFile.exists()) {
                        partFile.delete()
                    }

                    if (partFile.exists() && partFile.length() == expectedSize) {
                        downloadedTotalBytes += expectedSize
                        continue
                    }

                    if (partFile.exists() && partFile.length() > expectedSize) {
                        partFile.delete()
                    }

                    var success = false
                    var attempts = 0
                    val urlsToTry = listOf(
                        "$MODEL_BASE_DOWNLOAD_URL$fileName",
                        "$MODEL_FALLBACK_DOWNLOAD_URL$fileName"
                    )

                    var lastError: String? = null

                    for (urlCandidate in urlsToTry) {
                        if (success) break

                        while (!success && attempts < 3) {
                            attempts++
                            var conn: HttpURLConnection? = null
                            var input: InputStream? = null
                            var output: FileOutputStream? = null

                            try {
                                val existingLength = if (partFile.exists()) partFile.length() else 0L
                                var currentUrl = urlCandidate
                                var redirects = 0
                                var responseCode = 0

                                while (redirects < 5) {
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

                                if (responseCode == 416) {
                                    partFile.delete()
                                    break
                                }

                                if (responseCode != 200 && responseCode != 206) {
                                    throw Exception("HTTP помилка: $responseCode")
                                }

                                val isResume = (responseCode == 206)
                                val append = isResume && existingLength > 0
                                val activeConn = conn ?: throw Exception("Connection is null")
                                input = activeConn.inputStream
                                output = FileOutputStream(partFile, append)

                                val data = ByteArray(32768)
                                var count: Int

                                while (input.read(data).also { count = it } != -1) {
                                    output.write(data, 0, count)
                                    val currentPartLen = partFile.length()
                                    val overallProgressBytes = downloadedTotalBytes + currentPartLen
                                    val percent = ((overallProgressBytes * 85) / MODEL_TOTAL_ARCHIVE_SIZE).toInt().coerceIn(0, 85)
                                    val mbDone = overallProgressBytes / (1024 * 1024)
                                    val mbTotal = MODEL_TOTAL_ARCHIVE_SIZE / (1024 * 1024)

                                    val partPercent = if (expectedSize > 0) ((currentPartLen * 100) / expectedSize).toInt().coerceIn(0, 100) else 0
                                    onProgress(percent, "Том ${index + 1}/${MODEL_PARTS.size}: $mbDone/$mbTotal МБ ($percent%)")
                                    onDetailedProgress?.invoke(fileName, currentPartLen, expectedSize, index + 1, MODEL_PARTS.size, partPercent)
                                }

                                output.flush()
                                output.close()
                                output = null
                                input.close()
                                input = null
                                activeConn.disconnect()

                                if (expectedSize <= 0L || partFile.length() == expectedSize) {
                                    downloadedTotalBytes += expectedSize
                                    success = true
                                } else {
                                    partFile.delete()
                                }
                            } catch (e: Exception) {
                                lastError = e.localizedMessage ?: e.toString()
                            } finally {
                                try { input?.close() } catch (ignored: Exception) {}
                                try { output?.close() } catch (ignored: Exception) {}
                                try { conn?.disconnect() } catch (ignored: Exception) {}
                            }
                        }
                    }

                    if (!success) {
                        onComplete(false, lastError ?: "Не вдалося завантажити $fileName")
                        return@Thread
                    }
                }

                onProgress(88, "Розпакування нейромережі...")
                val successUnpack = unpackDownloadedParts(context, downloadedPartFiles)
                if (!successUnpack) {
                    onComplete(false, "Перевірка розмірів або помилка розпакування моделі")
                    return@Thread
                }

                onProgress(100, "Готово!")
                onComplete(true, null)
            }.start()
        }

        fun unpackDownloadedParts(
            context: Context,
            downloadedPartFiles: List<File>,
            onProgress: ((unpackedBytes: Long, totalExpectedBytes: Long) -> Unit)? = null
        ): Boolean {
            val targetDir = try {
                val sd = getModelDirectory()
                if (!sd.exists()) sd.mkdirs()
                if (sd.canWrite()) sd else File(context.filesDir, MushroomDataConfig.MODEL_DIR_NAME).apply { mkdirs() }
            } catch (e: Exception) {
                File(context.filesDir, MushroomDataConfig.MODEL_DIR_NAME).apply { mkdirs() }
            }

            try {
                val multiStream = MultiVolumeZipInputStream(downloadedPartFiles)
                val zipIn = ZipInputStream(multiStream)
                var entry = zipIn.nextEntry
                val buffer = ByteArray(65536)
                var bytesUnpacked = 0L
                var lastReportMs = 0L
                val totalModelBytes = MushroomDataConfig.MODEL_PARTS.sumOf { it.exactSize }

                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = File(entry.name).name
                        if (fileName.isNotEmpty()) {
                            val targetFile = File(targetDir, fileName)
                            val tempOut = File(targetDir, "$fileName.tmp")
                            FileOutputStream(tempOut).use { outStream ->
                                var len: Int
                                while (zipIn.read(buffer).also { len = it } != -1) {
                                    outStream.write(buffer, 0, len)
                                    bytesUnpacked += len
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportMs > 150L) {
                                        lastReportMs = now
                                        onProgress?.invoke(bytesUnpacked, totalModelBytes)
                                    }
                                }
                                outStream.flush()
                            }
                            if (targetFile.exists()) targetFile.delete()
                            val renamed = tempOut.renameTo(targetFile)
                            if (!renamed) {
                                tempOut.copyTo(targetFile, overwrite = true)
                                tempOut.delete()
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                zipIn.close()

                for (partFile in downloadedPartFiles) {
                    try { if (partFile.exists()) partFile.delete() } catch (_: Exception) {}
                }

                val ok = isModelDownloaded(context)
                if (ok) {
                    MycoKnowledge.reloadClasses(context)
                }
                return ok
            } catch (e: Exception) {
                return false
            }
        }

        fun downloadModel(
            context: Context,
            forceDownload: Boolean = false,
            onProgress: (Int) -> Unit,
            onComplete: (Boolean, String?) -> Unit
        ) {
            downloadModel(
                context = context,
                forceDownload = forceDownload,
                onProgress = { pct, _ -> onProgress(pct) },
                onDetailedProgress = null,
                onComplete = onComplete
            )
        }
    }
}
