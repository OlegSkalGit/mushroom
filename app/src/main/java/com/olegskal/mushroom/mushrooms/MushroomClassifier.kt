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

object MushroomClassifier {

    enum class ModelStatus {
        MISSING,
        NEEDS_UPDATE,
        READY
    }

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
}
