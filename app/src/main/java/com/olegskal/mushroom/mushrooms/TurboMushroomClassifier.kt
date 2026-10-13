package com.olegskal.mushroom.mushrooms

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.exp

class TurboMushroomClassifier(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var env: OrtEnvironment? = null
    private var sessionBeit: OrtSession? = null
    private var sessionSwin: OrtSession? = null
    private var isInitialized = false
    private var classes: List<String> = emptyList()
    var lastError: String? = null
        private set

    init {
        loadClasses()
        MycoKnowledge.init(context)
    }

    fun loadClasses() {
        if (classes.isNotEmpty()) return
        try {
            val file = MushroomClassifier.getRequiredFile(context, "classes.json")
            if (file.exists() && file.length() > 0) {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val arr = JSONArray(reader.readText())
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        list.add(arr.getJSONObject(i).optString("species", "Невідомий вид"))
                    }
                    classes = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun initSessions(): Boolean {
        if (isInitialized && sessionBeit != null && sessionSwin != null) return true
        try {
            val beitFile = MushroomClassifier.getRequiredFile(context, "beit_base_384_arm_int8.ort")
            val swinFile = MushroomClassifier.getRequiredFile(context, "swin_base_384_arm_int8.ort")
            if (!beitFile.exists() || !swinFile.exists()) {
                lastError = "Model files missing: BEiT=${beitFile.exists()}, Swin=${swinFile.exists()}"
                return false
            }

            val e = OrtEnvironment.getEnvironment()
            env = e

            val opts = OrtSession.SessionOptions().apply {
                val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                setIntraOpNumThreads(cores)
                addConfigEntry("session.load_model_format", "ORT")
            }

            sessionBeit = e.createSession(beitFile.absolutePath, opts)
            sessionSwin = e.createSession(swinFile.absolutePath, opts)

            isInitialized = true
            lastError = null
            return true
        } catch (t: Throwable) {
            lastError = "Turbo init error: ${t.message ?: t.toString()}"
            return false
        }
    }

    fun classify(
        bitmaps: List<Bitmap>,
        topK: Int = 5,
        callback: (List<MushroomPrediction>) -> Unit
    ) {
        if (bitmaps.isEmpty()) {
            mainHandler.post { callback(emptyList()) }
            return
        }

        executor.execute {
            try {
                if (!initSessions()) {
                    mainHandler.post { callback(emptyList()) }
                    return@execute
                }

                val sBeit = sessionBeit!!
                val sSwin = sessionSwin!!
                val e = env!!

                val batchSize = bitmaps.size
                val beitInputName = sBeit.inputNames.iterator().next()
                val swinInputName = sSwin.inputNames.iterator().next()

                val numClasses = if (classes.isNotEmpty()) classes.size else 2829
                val beitMeanProbs = FloatArray(numClasses)
                val swinMeanProbs = FloatArray(numClasses)

                val pixels = IntArray(384 * 384)
                val shape = longArrayOf(1L, 3L, 384L, 384L)

                for (bmp in bitmaps) {
                    val resized = Bitmap.createScaledBitmap(bmp, 384, 384, true)
                    resized.getPixels(pixels, 0, 384, 0, 0, 384, 384)
                    if (resized != bmp) resized.recycle()

                    val floatBuffer = FloatBuffer.allocate(3 * 384 * 384)
                    // R
                    for (i in pixels.indices) {
                        val r = (pixels[i] shr 16 and 0xFF) / 255.0f
                        floatBuffer.put((r - 0.485f) / 0.229f)
                    }
                    // G
                    for (i in pixels.indices) {
                        val g = (pixels[i] shr 8 and 0xFF) / 255.0f
                        floatBuffer.put((g - 0.456f) / 0.224f)
                    }
                    // B
                    for (i in pixels.indices) {
                        val b = (pixels[i] and 0xFF) / 255.0f
                        floatBuffer.put((b - 0.406f) / 0.225f)
                    }
                    floatBuffer.rewind()

                    val tensor = OnnxTensor.createTensor(e, floatBuffer, shape)

                    // BEiT
                    val beitResult = sBeit.run(mapOf(beitInputName to tensor))
                    val beitLogits = extractLogits(beitResult[0].value, numClasses)
                    accumulateSoftmax(beitLogits, beitMeanProbs, batchSize)
                    beitResult.close()

                    // Swin
                    val swinResult = sSwin.run(mapOf(swinInputName to tensor))
                    val swinLogits = extractLogits(swinResult[0].value, numClasses)
                    accumulateSoftmax(swinLogits, swinMeanProbs, batchSize)
                    swinResult.close()

                    tensor.close()
                }

                val predictions = blendPredictions(beitMeanProbs, swinMeanProbs, topK)
                mainHandler.post { callback(predictions) }
            } catch (t: Throwable) {
                lastError = "Turbo run error: ${t.message ?: t.toString()}"
                mainHandler.post { callback(emptyList()) }
            }
        }
    }

    private fun extractLogits(value: Any?, expectedSize: Int): FloatArray {
        return when (value) {
            is Array<*> -> {
                val first = value.firstOrNull()
                if (first is FloatArray) first else FloatArray(expectedSize)
            }
            is FloatArray -> value
            else -> FloatArray(expectedSize)
        }
    }

    private fun accumulateSoftmax(logits: FloatArray, accumulator: FloatArray, batchSize: Int) {
        val numClasses = logits.size
        var maxLogit = -Float.MAX_VALUE
        for (i in 0 until numClasses) {
            if (logits[i] > maxLogit) maxLogit = logits[i]
        }
        var sumExp = 0.0
        val expScores = FloatArray(numClasses)
        for (i in 0 until numClasses) {
            val e = exp(logits[i] - maxLogit)
            expScores[i] = e
            sumExp += e
        }
        if (sumExp > 0.0) {
            val invSum = 1.0f / sumExp.toFloat()
            for (i in 0 until numClasses) {
                accumulator[i] += (expScores[i] * invSum) / batchSize
            }
        }
    }

    private fun blendPredictions(
        beitProbs: FloatArray,
        swinProbs: FloatArray,
        topK: Int
    ): List<MushroomPrediction> {
        val topBeit = beitProbs.indices.sortedByDescending { beitProbs[it] }.take(topK).toSet()
        val topSwin = swinProbs.indices.sortedByDescending { swinProbs[it] }.take(topK).toSet()
        val candidates = (topBeit + topSwin).map { idx ->
            val pBeit = beitProbs[idx]
            val pSwin = swinProbs[idx]
            Triple(idx, (pBeit + pSwin) / 2f, Pair(pBeit, pSwin))
        }.filter { it.second > 0f }
         .sortedByDescending { it.second }
         .take(topK)

        loadClasses()
        return candidates.map { c ->
            val species = classes.getOrElse(c.first) { "Вид #${c.first}" }
            val meta = MycoKnowledge.resolveMetadata(species)
            MushroomPrediction(
                species = species,
                confidence = c.second * 100f,
                edibility = meta.edibility,
                hymenium = meta.hymenium,
                beitConfidence = c.third.first * 100f,
                swinConfidence = c.third.second * 100f
            )
        }
    }

    fun close() {
        executor.execute {
            try {
                sessionBeit?.close()
                sessionSwin?.close()
                env?.close()
            } catch (_: Exception) {}
            sessionBeit = null
            sessionSwin = null
            env = null
            isInitialized = false
        }
    }
}
