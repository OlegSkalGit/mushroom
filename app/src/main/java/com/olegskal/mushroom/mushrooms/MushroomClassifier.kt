package com.olegskal.mushroom.mushrooms

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.exp

data class MushroomPrediction(
    val species: String,
    val scientificName: String,
    val isPoisonous: Boolean,
    val confidence: Float,
    val edibility: String,
    val rawLabel: String
)

class MushroomClassifier(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var labels: List<String> = emptyList()
    private var isInitialized = false

    companion object {
        const val MODEL_URL = "https://github.com/OlegSkalGit/mushroom/model.onnx"
        const val MODEL_FILENAME = "model.onnx"

        fun getModelDirectory(): File {
            val sdDir = File(Environment.getExternalStorageDirectory(), "mushroom/model")
            if (!sdDir.exists()) {
                sdDir.mkdirs()
            }
            return sdDir
        }

        fun getModelFile(context: Context): File {
            val primary = File(getModelDirectory(), MODEL_FILENAME)
            if (primary.exists()) return primary

            val internal = File(context.filesDir, "model/$MODEL_FILENAME")
            if (internal.exists()) return internal

            return primary
        }

        fun isModelDownloaded(context: Context): Boolean {
            val file = getModelFile(context)
            return file.exists() && file.length() > 10 * 1024 * 1024 // at least 10MB
        }

        fun downloadModel(
            context: Context,
            onProgress: (Int) -> Unit,
            onComplete: (Boolean, String?) -> Unit
        ) {
            Thread {
                var conn: HttpURLConnection? = null
                var input: InputStream? = null
                var output: FileOutputStream? = null

                try {
                    val targetDir = try {
                        val sd = getModelDirectory()
                        if (!sd.exists()) sd.mkdirs()
                        if (sd.canWrite()) sd else File(context.filesDir, "model").apply { mkdirs() }
                    } catch (e: Exception) {
                        File(context.filesDir, "model").apply { mkdirs() }
                    }

                    val targetFile = File(targetDir, MODEL_FILENAME)
                    val tempFile = File(targetDir, "$MODEL_FILENAME.tmp")

                    var currentUrl = MODEL_URL
                    var redirects = 0
                    while (redirects < 5) {
                        val url = URL(currentUrl)
                        conn = url.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent", "MushroomApp/1.0 (Android)")
                        conn.connect()

                        val code = conn.responseCode
                        if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                            code == HttpURLConnection.HTTP_MOVED_TEMP ||
                            code == 307 || code == 308) {
                            val newUrl = conn.getHeaderField("Location")
                            conn.disconnect()
                            currentUrl = newUrl
                            redirects++
                        } else if (code == 200) {
                            break
                        } else {
                            throw Exception("HTTP error code: $code")
                        }
                    }

                    val fileLength = conn!!.contentLength
                    input = conn.inputStream
                    output = FileOutputStream(tempFile)

                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    var lastPercent = -1

                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        output.write(data, 0, count)

                        if (fileLength > 0) {
                            val percent = ((total * 100) / fileLength).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }

                    output.flush()
                    output.close()
                    output = null
                    input.close()
                    input = null
                    conn.disconnect()

                    if (tempFile.exists() && tempFile.length() > 0) {
                        if (targetFile.exists()) targetFile.delete()
                        tempFile.renameTo(targetFile)
                        onComplete(true, null)
                    } else {
                        onComplete(false, "Downloaded file is empty")
                    }
                } catch (e: Exception) {
                    onComplete(false, e.localizedMessage ?: e.toString())
                } finally {
                    try { input?.close() } catch (ignored: Exception) {}
                    try { output?.close() } catch (ignored: Exception) {}
                    try { conn?.disconnect() } catch (ignored: Exception) {}
                }
            }.start()
        }
    }

    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            val modelFile = getModelFile(context)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return false
            }

            env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                } catch (e: Exception) {
                    setInterOpNumThreads(2)
                    setIntraOpNumThreads(2)
                }
            }

            session = env!!.createSession(modelFile.absolutePath, sessionOptions)

            // Read labels
            labels = try {
                context.assets.open("labels.txt").bufferedReader().readLines()
            } catch (e: Exception) {
                emptyList()
            }

            isInitialized = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun classify(bitmap: Bitmap, topK: Int = 5): List<MushroomPrediction> {
        if (!isInitialized && !initialize()) {
            return emptyList()
        }

        val sess = session ?: return emptyList()
        val environment = env ?: return emptyList()

        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val floatBuffer = FloatBuffer.allocate(1 * 3 * 224 * 224)
        val pixels = IntArray(224 * 224)
        resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

        // R Channel
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16 and 0xFF) / 255.0f
            floatBuffer.put((r - 0.5f) / 0.5f)
        }
        // G Channel
        for (i in pixels.indices) {
            val g = (pixels[i] shr 8 and 0xFF) / 255.0f
            floatBuffer.put((g - 0.5f) / 0.5f)
        }
        // B Channel
        for (i in pixels.indices) {
            val b = (pixels[i] and 0xFF) / 255.0f
            floatBuffer.put((b - 0.5f) / 0.5f)
        }
        floatBuffer.rewind()

        val inputName = sess.inputNames.iterator().next()
        val tensor = OnnxTensor.createTensor(environment, floatBuffer, longArrayOf(1, 3, 224, 224))

        val results = sess.run(mapOf(inputName to tensor))
        @Suppress("UNCHECKED_CAST")
        val outputTensor = results[0].value as Array<FloatArray>
        val logits = outputTensor[0]

        // Softmax
        val maxLogit = logits.maxOrNull() ?: 0f
        val expScores = logits.map { exp(it - maxLogit) }
        val sumExp = expScores.sum()
        val probabilities = expScores.map { (it / sumExp) * 100f }

        return probabilities.indices
            .sortedByDescending { probabilities[it] }
            .take(topK)
            .map { idx ->
                val raw = labels.getOrElse(idx) { "Unknown" }
                val isPoisonous = raw.contains("(poisonous)", ignoreCase = true)
                val clean = raw
                    .replace("(poisonous)", "")
                    .replace("(edible)", "")
                    .replace("(Mushrooms)", "")
                    .trim()
                    .replace("_", " ")

                val meta = MycoKnowledge.resolveMetadata(clean)
                val edibility = if (isPoisonous) {
                    if (meta.edibility == "deadly") "deadly" else "toxic"
                } else {
                    meta.edibility
                }

                MushroomPrediction(
                    species = clean,
                    scientificName = clean,
                    isPoisonous = isPoisonous,
                    confidence = probabilities[idx],
                    edibility = edibility,
                    rawLabel = raw
                )
            }
    }

    fun close() {
        try {
            session?.close()
            env?.close()
        } catch (ignored: Exception) {}
        isInitialized = false
    }
}
