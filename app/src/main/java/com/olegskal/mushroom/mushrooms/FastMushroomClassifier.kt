package com.olegskal.mushroom.mushrooms

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp

class FastMushroomClassifier(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isSessionReady = false
    private var isInitializing = false
    private var classes: List<String> = emptyList()
    private var pendingCallback: ((List<MushroomPrediction>) -> Unit)? = null
    private var pendingTopK = 5
    private var tPreprocessMs: Long = 0
    private var tTransferStartMs: Long = 0

    val numThreads: Int = calculateOptimalThreads()

    var lastError: String? = null
        private set

    companion object {
        fun calculateOptimalThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores <= 4) maxOf(1, cores - 1) else 4
        }

        fun logExperiment(message: String) {
            try {
                val logDir = MushroomClassifier.getModelDirectory()
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "fast_classifier.log")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val timestamp = sdf.format(Date())
                FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                    writer.write("[$timestamp] $message\n")
                }
            } catch (e: Exception) {
                android.util.Log.e("FastClassifier", "Failed to write log: ${e.message}")
            }
        }
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    fun initEngine(onReady: (Boolean, String?) -> Unit) {
        if (isSessionReady) {
            onReady(true, null)
            return
        }
        if (isInitializing) return
        isInitializing = true

        mainHandler.post {
            try {
                val wv = WebView(context)
                webView = wv
                val settings = wv.settings
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.domStorageEnabled = true

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onModelLoaded(success: Boolean, errorMsg: String, diagJson: String) {
                        isSessionReady = success
                        isInitializing = false
                        if (!success) {
                            lastError = errorMsg
                            logExperiment("INIT ERROR: $errorMsg | Markers: $diagJson")
                        } else {
                            lastError = null
                            logExperiment("ENGINE READY: Markers: $diagJson | Kotlin targetThreads=$numThreads (CPU cores: ${Runtime.getRuntime().availableProcessors()})")
                        }
                        mainHandler.post { onReady(success, errorMsg) }
                    }

                    @JavascriptInterface
                    fun onDualInferenceResult(
                        beitJson: String,
                        swinJson: String,
                        batchSize: Int,
                        jsDecodeMs: Long,
                        beitMs: Long,
                        swinMs: Long,
                        diagJson: String
                    ) {
                        val tEnd = System.currentTimeMillis()
                        val totalMs = tEnd - tTransferStartMs + tPreprocessMs
                        val bridgeTransferMs = tEnd - tTransferStartMs - (beitMs + swinMs) - jsDecodeMs
                        lastError = null

                        mainHandler.post {
                            val cb = pendingCallback
                            pendingCallback = null
                            if (cb != null) {
                                val tPostStart = System.currentTimeMillis()
                                val preds = processDualResultJson(beitJson, swinJson, batchSize, pendingTopK)
                                val postMs = System.currentTimeMillis() - tPostStart

                                val topInfo = preds.firstOrNull()?.let { "${it.species} (${String.format(Locale.US, "%.1f", it.confidence)}%)" } ?: "None"
                                val logText = buildString {
                                    appendLine("--- FAST MULTI-THREAD CLASSIFICATION ---")
                                    appendLine("Batch: $batchSize photo(s) | Markers: $diagJson")
                                    appendLine("[1] Preprocessing: ${tPreprocessMs}ms")
                                    appendLine("[2] Bridge Transfer: ${maxOf(0, bridgeTransferMs)}ms (JS decode: ${jsDecodeMs}ms)")
                                    appendLine("[3] BEiT Inference: ${beitMs}ms")
                                    appendLine("[4] Swin Inference: ${swinMs}ms")
                                    appendLine("[5] Postprocessing: ${postMs}ms")
                                    appendLine("TOTAL TIME: ${totalMs + postMs}ms | Top-1: $topInfo")
                                    appendLine("----------------------------------------")
                                }
                                logExperiment(logText)
                                cb(preds)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onInferenceError(errorMsg: String) {
                        lastError = errorMsg
                        logExperiment("INFERENCE ERROR: $errorMsg")
                        mainHandler.post {
                            val cb = pendingCallback
                            pendingCallback = null
                            cb?.invoke(emptyList())
                        }
                    }
                }, "AndroidBridge")

                fun makeResponse(mime: String, encoding: String?, stream: java.io.InputStream): WebResourceResponse {
                    val resp = WebResourceResponse(mime, encoding, stream)
                    resp.responseHeaders = mapOf(
                        "Cross-Origin-Opener-Policy" to "same-origin",
                        "Cross-Origin-Embedder-Policy" to "require-corp",
                        "Cross-Origin-Resource-Policy" to "cross-origin",
                        "Access-Control-Allow-Origin" to "*"
                    )
                    return resp
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val urlStr = request.url.toString()
                        return when {
                            urlStr.startsWith("https://local.mushroom/fast_index.html") -> {
                                val html = getFastRunnerHtml()
                                makeResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                            }
                            urlStr.startsWith("https://local.mushroom/ort.wasm.min.js") -> {
                                val f = MushroomClassifier.getRequiredFile(context, "ort.wasm.min.js")
                                makeResponse("application/javascript", "UTF-8", FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/ort-wasm-simd-threaded.mjs") -> {
                                val f = MushroomClassifier.getRequiredFile(context, "ort-wasm-simd-threaded.mjs")
                                makeResponse("application/javascript", "UTF-8", FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/ort-wasm-simd-threaded.wasm") -> {
                                val f = MushroomClassifier.getRequiredFile(context, "ort-wasm-simd-threaded.wasm")
                                makeResponse("application/wasm", null, FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/beit_base_384_arm_int8.ort") -> {
                                val f = MushroomClassifier.getRequiredFile(context, "beit_base_384_arm_int8.ort")
                                makeResponse("application/octet-stream", null, FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/swin_base_384_arm_int8.ort") -> {
                                val f = MushroomClassifier.getRequiredFile(context, "swin_base_384_arm_int8.ort")
                                makeResponse("application/octet-stream", null, FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/classes.json") -> {
                                val f = MushroomClassifier.getRequiredFile(context, "classes.json")
                                makeResponse("application/json", "UTF-8", FileInputStream(f))
                            }
                            else -> super.shouldInterceptRequest(view, request)
                        }
                    }
                }

                wv.loadUrl("https://local.mushroom/fast_index.html")
            } catch (e: Exception) {
                isInitializing = false
                lastError = e.localizedMessage ?: e.toString()
                logExperiment("INIT EXCEPTION: $lastError")
                onReady(false, lastError)
            }
        }
    }

    private fun getFastRunnerHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <script src="https://local.mushroom/ort.wasm.min.js"></script>
            </head>
            <body>
              <script>
                let sessionBeit = null;
                let sessionSwin = null;

                function getMarkers() {
                  return {
                    crossOriginIsolated: typeof crossOriginIsolated !== 'undefined' ? crossOriginIsolated : false,
                    hasSharedArrayBuffer: typeof SharedArrayBuffer !== 'undefined',
                    hardwareConcurrency: navigator.hardwareConcurrency || 0,
                    ortEnvThreads: ort.env.wasm.numThreads
                  };
                }

                async function initSession() {
                  try {
                    ort.env.wasm.wasmPaths = "https://local.mushroom/";
                    ort.env.wasm.numThreads = $numThreads;

                    const [s1, s2] = await Promise.all([
                      ort.InferenceSession.create("https://local.mushroom/beit_base_384_arm_int8.ort", {
                        executionProviders: ['wasm']
                      }),
                      ort.InferenceSession.create("https://local.mushroom/swin_base_384_arm_int8.ort", {
                        executionProviders: ['wasm']
                      })
                    ]);
                    sessionBeit = s1;
                    sessionSwin = s2;
                    AndroidBridge.onModelLoaded(true, "", JSON.stringify(getMarkers()));
                  } catch(e) {
                    AndroidBridge.onModelLoaded(false, e.toString(), JSON.stringify(getMarkers()));
                  }
                }

                async function runSingleModel(session, bytes, batchSize, floatsPerImage) {
                  const allOutputs = [];
                  for (let b = 0; b < batchSize; b++) {
                    const byteOffset = b * floatsPerImage * 4;
                    const float32 = new Float32Array(bytes.buffer, byteOffset, floatsPerImage);
                    const tensor = new ort.Tensor('float32', float32, [1, 3, 384, 384]);
                    const feeds = {};
                    feeds[session.inputNames[0]] = tensor;
                    const results = await session.run(feeds);
                    const output = results[session.outputNames[0]].data;
                    for (let i = 0; i < output.length; i++) {
                      allOutputs.push(output[i]);
                    }
                  }
                  return allOutputs;
                }

                async function classifyBatchBase64Fast(base64Data, batchSize) {
                  try {
                    const tDecodeStart = performance.now();
                    const binaryString = atob(base64Data);
                    const bytes = new Uint8Array(binaryString.length);
                    for (let i = 0; i < binaryString.length; i++) {
                      bytes[i] = binaryString.charCodeAt(i);
                    }
                    const decodeMs = Math.round(performance.now() - tDecodeStart);
                    const floatsPerImage = 3 * 384 * 384;

                    const tBeit = performance.now();
                    const outBeit = await runSingleModel(sessionBeit, bytes, batchSize, floatsPerImage);
                    const beitMs = Math.round(performance.now() - tBeit);

                    const tSwin = performance.now();
                    const outSwin = await runSingleModel(sessionSwin, bytes, batchSize, floatsPerImage);
                    const swinMs = Math.round(performance.now() - tSwin);

                    AndroidBridge.onDualInferenceResult(
                      JSON.stringify(outBeit),
                      JSON.stringify(outSwin),
                      batchSize,
                      decodeMs,
                      beitMs,
                      swinMs,
                      JSON.stringify(getMarkers())
                    );
                  } catch(e) {
                    AndroidBridge.onInferenceError(e.toString());
                  }
                }

                initSession();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    fun classify(bitmaps: List<Bitmap>, topK: Int = 5, callback: (List<MushroomPrediction>) -> Unit) {
        if (bitmaps.isEmpty()) {
            callback(emptyList())
            return
        }
        lastError = null
        if (!isSessionReady) {
            initEngine { success, err ->
                if (success) {
                    doInference(bitmaps, topK, callback)
                } else {
                    lastError = err ?: "Fast engine initialization failed"
                    mainHandler.post { callback(emptyList()) }
                }
            }
        } else {
            doInference(bitmaps, topK, callback)
        }
    }

    private fun doInference(bitmaps: List<Bitmap>, topK: Int, callback: (List<MushroomPrediction>) -> Unit) {
        mainHandler.post {
            pendingCallback = callback
            pendingTopK = topK
            val tStart = System.currentTimeMillis()
            val batchSize = bitmaps.size
            val byteBuffer = ByteBuffer.allocate(batchSize * 3 * 384 * 384 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val pixels = IntArray(384 * 384)

            for (bmp in bitmaps) {
                val resized = Bitmap.createScaledBitmap(bmp, 384, 384, true)
                resized.getPixels(pixels, 0, 384, 0, 0, 384, 384)

                // R Channel
                for (i in pixels.indices) {
                    val r = (pixels[i] shr 16 and 0xFF) / 255.0f
                    byteBuffer.putFloat((r - 0.485f) / 0.229f)
                }
                // G Channel
                for (i in pixels.indices) {
                    val g = (pixels[i] shr 8 and 0xFF) / 255.0f
                    byteBuffer.putFloat((g - 0.456f) / 0.224f)
                }
                // B Channel
                for (i in pixels.indices) {
                    val b = (pixels[i] and 0xFF) / 255.0f
                    byteBuffer.putFloat((b - 0.406f) / 0.225f)
                }
                if (resized != bmp) {
                    resized.recycle()
                }
            }

            tPreprocessMs = System.currentTimeMillis() - tStart
            tTransferStartMs = System.currentTimeMillis()
            val base64 = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
            webView?.evaluateJavascript("classifyBatchBase64Fast('$base64', $batchSize);", null)
        }
    }

    private fun calculateBatchProbs(arr: JSONArray, batchSize: Int, numClasses: Int): FloatArray {
        val actualBatch = maxOf(1, batchSize)
        val meanProbs = FloatArray(numClasses)

        for (b in 0 until actualBatch) {
            val offset = b * numClasses
            var maxLogit = -Float.MAX_VALUE
            for (c in 0 until numClasses) {
                val idx = offset + c
                if (idx < arr.length()) {
                    val logit = arr.getDouble(idx).toFloat()
                    if (logit > maxLogit) maxLogit = logit
                }
            }

            var sumExp = 0.0
            val expScores = FloatArray(numClasses)
            for (c in 0 until numClasses) {
                val idx = offset + c
                val logit = if (idx < arr.length()) arr.getDouble(idx).toFloat() else 0f
                val e = exp(logit - maxLogit)
                expScores[c] = e
                sumExp += e
            }

            if (sumExp > 0.0) {
                val invSumExp = 1.0f / sumExp.toFloat()
                for (c in 0 until numClasses) {
                    meanProbs[c] += (expScores[c] * invSumExp) / actualBatch
                }
            }
        }
        return meanProbs
    }

    private fun processDualResultJson(
        beitJson: String,
        swinJson: String,
        batchSize: Int,
        topK: Int
    ): List<MushroomPrediction> {
        try {
            loadClasses()
            val numClasses = if (classes.isNotEmpty()) classes.size else 2829
            val beitArr = JSONArray(beitJson)
            val swinArr = JSONArray(swinJson)

            val beitProbs = calculateBatchProbs(beitArr, batchSize, numClasses)
            val swinProbs = calculateBatchProbs(swinArr, batchSize, numClasses)

            val topBeitIndices = beitProbs.indices
                .sortedByDescending { beitProbs[it] }
                .take(topK)
                .toSet()

            val topSwinIndices = swinProbs.indices
                .sortedByDescending { swinProbs[it] }
                .take(topK)
                .toSet()

            val candidateIndices = topBeitIndices + topSwinIndices

            data class Candidate(
                val index: Int,
                val avgProb: Float,
                val beitProb: Float,
                val swinProb: Float
            )

            val candidates = candidateIndices.map { idx ->
                val pBeit = if (idx in topBeitIndices) beitProbs[idx] else 0f
                val pSwin = if (idx in topSwinIndices) swinProbs[idx] else 0f
                val pAvg = (pBeit + pSwin) / 2f
                Candidate(idx, pAvg, pBeit, pSwin)
            }.filter { it.avgProb > 0f }
                .sortedByDescending { it.avgProb }
                .take(topK)

            return candidates.map { c ->
                val species = classes.getOrElse(c.index) { "Вид #${c.index}" }
                val meta = MycoKnowledge.resolveMetadata(species)
                MushroomPrediction(
                    species = species,
                    confidence = c.avgProb * 100f,
                    edibility = meta.edibility,
                    hymenium = meta.hymenium,
                    beitConfidence = c.beitProb * 100f,
                    swinConfidence = c.swinProb * 100f
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }
}
