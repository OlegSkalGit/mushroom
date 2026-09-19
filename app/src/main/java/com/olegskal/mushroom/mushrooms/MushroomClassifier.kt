package com.olegskal.mushroom.mushrooms

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

data class MushroomPrediction(
    val species: String,
    val confidence: Float
) {
    val scientificName: String get() = species
}

data class ModelDownloadItem(
    val fileName: String,
    val primaryUrl: String,
    val fallbackUrl: String,
    val minSize: Long
)

class MushroomClassifier(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isSessionReady = false
    private var isInitializing = false
    private var classes: List<String> = emptyList()
    private var pendingCallback: ((List<MushroomPrediction>) -> Unit)? = null
    private var pendingTopK = 5
    var lastError: String? = null
        private set

    companion object {
        private const val BROKEN_MODEL2_SIZE = 280274191L

        val REQUIRED_FILES = listOf(
            ModelDownloadItem(
                fileName = "model2.onnx",
                primaryUrl = "https://media.githubusercontent.com/media/OlegSkalGit/mushroom/main/model2.onnx",
                fallbackUrl = "https://github.com/OlegSkalGit/mushroom/raw/main/model2.onnx",
                minSize = 281 * 1024 * 1024L
            ),
            ModelDownloadItem(
                fileName = "classes.json",
                primaryUrl = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/classes.json",
                fallbackUrl = "https://github.com/OlegSkalGit/mushroom/raw/main/classes.json",
                minSize = 50 * 1024L
            ),
            ModelDownloadItem(
                fileName = "ort.min.js",
                primaryUrl = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/ort.min.js",
                fallbackUrl = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.17.0/dist/ort.min.js",
                minSize = 100 * 1024L
            ),
            ModelDownloadItem(
                fileName = "ort-wasm-simd.wasm",
                primaryUrl = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/ort-wasm-simd.wasm",
                fallbackUrl = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.17.0/dist/ort-wasm-simd.wasm",
                minSize = 2 * 1024 * 1024L
            )
        )

        fun cleanBrokenModelIfPresent(context: Context) {
            val file = getRequiredFile(context, "model2.onnx")
            if (file.exists() && file.length() == BROKEN_MODEL2_SIZE) {
                try {
                    file.delete()
                } catch (ignored: Exception) {}
            }
        }

        fun getModelDirectory(): File {
            val sdDir = File(Environment.getExternalStorageDirectory(), "mushroom/model")
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
                val extFile = File(extFiles, "model/$fileName")
                if (extFile.exists() && extFile.length() > 0) return extFile
            }

            val internal = File(context.filesDir, "model/$fileName")
            if (internal.exists() && internal.length() > 0) return internal

            return primary
        }

        fun isModelDownloaded(context: Context): Boolean {
            cleanBrokenModelIfPresent(context)
            for (item in REQUIRED_FILES) {
                val file = getRequiredFile(context, item.fileName)
                if (!file.exists() || file.length() < item.minSize) {
                    return false
                }
            }
            return true
        }

        fun downloadModel(
            context: Context,
            onProgress: (Int) -> Unit,
            onComplete: (Boolean, String?) -> Unit
        ) {
            Thread {
                if (isModelDownloaded(context)) {
                    onComplete(true, null)
                    return@Thread
                }

                val targetDir = try {
                    val sd = getModelDirectory()
                    if (!sd.exists()) sd.mkdirs()
                    if (sd.canWrite()) sd else File(context.filesDir, "model").apply { mkdirs() }
                } catch (e: Exception) {
                    File(context.filesDir, "model").apply { mkdirs() }
                }

                val totalItems = REQUIRED_FILES.size
                var downloadedCount = 0

                for ((idx, item) in REQUIRED_FILES.withIndex()) {
                    val targetFile = File(targetDir, item.fileName)
                    if (targetFile.exists() && targetFile.length() >= item.minSize) {
                        downloadedCount++
                        val basePercent = ((downloadedCount * 100) / totalItems)
                        onProgress(basePercent)
                        continue
                    }

                    val tempFile = File(targetDir, "${item.fileName}.tmp")
                    val urlsToTry = listOf(item.primaryUrl, item.fallbackUrl)
                    var fileSuccess = false
                    var lastError: String? = null

                    for (urlCandidate in urlsToTry) {
                        var conn: HttpURLConnection? = null
                        var input: InputStream? = null
                        var output: FileOutputStream? = null

                        try {
                            var currentUrl = urlCandidate
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
                                    throw Exception("HTTP $code from $currentUrl")
                                }
                            }

                            val fileLength = conn!!.contentLengthLong
                            input = conn.inputStream
                            output = FileOutputStream(tempFile)

                            val data = ByteArray(16384)
                            var total: Long = 0
                            var count: Int

                            while (input.read(data).also { count = it } != -1) {
                                total += count
                                output.write(data, 0, count)

                                if (fileLength > 0) {
                                    val filePercent = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                                    val overallPercent = ((idx * 100 + filePercent) / totalItems)
                                    onProgress(overallPercent)
                                }
                            }

                            output.flush()
                            output.close()
                            output = null
                            input.close()
                            input = null
                            conn.disconnect()

                            if (tempFile.exists() && tempFile.length() >= item.minSize) {
                                if (targetFile.exists()) targetFile.delete()
                                tempFile.renameTo(targetFile)
                                fileSuccess = true
                                downloadedCount++
                                break
                            } else {
                                throw Exception("Downloaded file ${item.fileName} is incomplete (${tempFile.length()} bytes)")
                            }
                        } catch (e: Exception) {
                            lastError = e.localizedMessage ?: e.toString()
                        } finally {
                            try { input?.close() } catch (ignored: Exception) {}
                            try { output?.close() } catch (ignored: Exception) {}
                            try { conn?.disconnect() } catch (ignored: Exception) {}
                        }
                    }

                    if (!fileSuccess) {
                        onComplete(false, lastError ?: "Failed to download ${item.fileName}")
                        return@Thread
                    }
                }

                onProgress(100)
                onComplete(true, null)
            }.start()
        }
    }

    init {
        loadClasses()
    }

    private fun loadClasses() {
        if (classes.isNotEmpty()) return
        try {
            val file = getRequiredFile(context, "classes.json")
            if (file.exists() && file.length() > 0) {
                val jsonStr = file.readText(Charsets.UTF_8)
                val arr = JSONArray(jsonStr)
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(obj.optString("species", "Невідомий вид"))
                }
                classes = list
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

        if (isInitializing) {
            return
        }
        isInitializing = true

        mainHandler.post {
            try {
                val wv = WebView(context)
                webView = wv
                val settings = wv.settings
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onModelLoaded(success: Boolean, errorMsg: String) {
                        isSessionReady = success
                        isInitializing = false
                        if (!success) {
                            lastError = errorMsg
                            android.util.Log.e("MushroomClassifier", "Model load error: $errorMsg")
                        } else {
                            lastError = null
                        }
                        mainHandler.post {
                            onReady(success, errorMsg)
                        }
                    }

                    @JavascriptInterface
                    fun onInferenceResult(resultJson: String, batchSize: Int) {
                        lastError = null
                        mainHandler.post {
                            val cb = pendingCallback
                            pendingCallback = null
                            if (cb != null) {
                                val preds = processResultJson(resultJson, batchSize, pendingTopK)
                                cb(preds)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onInferenceError(errorMsg: String) {
                        lastError = errorMsg
                        android.util.Log.e("MushroomClassifier", "Inference error: $errorMsg")
                        mainHandler.post {
                            val cb = pendingCallback
                            pendingCallback = null
                            cb?.invoke(emptyList())
                        }
                    }
                }, "AndroidBridge")

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val urlStr = request.url.toString()
                        return when {
                            urlStr.startsWith("https://local.mushroom/index.html") -> {
                                val html = getRunnerHtml()
                                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                            }
                            urlStr.startsWith("https://local.mushroom/ort.min.js") -> {
                                val f = getRequiredFile(context, "ort.min.js")
                                WebResourceResponse("application/javascript", "UTF-8", FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/ort-wasm-simd.wasm") -> {
                                val f = getRequiredFile(context, "ort-wasm-simd.wasm")
                                WebResourceResponse("application/wasm", null, FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/model2.onnx") -> {
                                val f = getRequiredFile(context, "model2.onnx")
                                WebResourceResponse("application/octet-stream", null, FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/classes.json") -> {
                                val f = getRequiredFile(context, "classes.json")
                                WebResourceResponse("application/json", "UTF-8", FileInputStream(f))
                            }
                            else -> super.shouldInterceptRequest(view, request)
                        }
                    }
                }

                wv.loadUrl("https://local.mushroom/index.html")
            } catch (e: Exception) {
                isInitializing = false
                lastError = e.localizedMessage ?: e.toString()
                onReady(false, lastError)
            }
        }
    }

    private fun getRunnerHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <script src="https://local.mushroom/ort.min.js"></script>
            </head>
            <body>
              <script>
                let session = null;
                async function initSession() {
                  try {
                    ort.env.wasm.wasmPaths = "https://local.mushroom/";
                    ort.env.wasm.numThreads = 1;
                    session = await ort.InferenceSession.create("https://local.mushroom/model2.onnx", {
                      executionProviders: ['wasm']
                    });
                    AndroidBridge.onModelLoaded(true, "");
                  } catch(e) {
                    console.error("InitSession error:", e);
                    AndroidBridge.onModelLoaded(false, e.toString());
                  }
                }

                async function classifyBatchBase64(base64Data, batchSize) {
                  try {
                    const binaryString = atob(base64Data);
                    const bytes = new Uint8Array(binaryString.length);
                    for (let i = 0; i < binaryString.length; i++) {
                      bytes[i] = binaryString.charCodeAt(i);
                    }
                    const float32 = new Float32Array(bytes.buffer);
                    const tensor = new ort.Tensor('float32', float32, [batchSize, 3, 384, 384]);
                    const feeds = {};
                    feeds[session.inputNames[0]] = tensor;
                    const results = await session.run(feeds);
                    const output = results[session.outputNames[0]].data;
                    AndroidBridge.onInferenceResult(JSON.stringify(Array.from(output)), batchSize);
                  } catch(e) {
                    console.error("Inference error:", e);
                    AndroidBridge.onInferenceError(e.toString());
                  }
                }

                initSession();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    fun classify(bitmap: Bitmap, topK: Int = 5, callback: (List<MushroomPrediction>) -> Unit) {
        classify(listOf(bitmap), topK, callback)
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
                    lastError = err ?: "Engine initialization failed"
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
            val batchSize = bitmaps.size
            val byteBuffer = ByteBuffer.allocate(batchSize * 3 * 384 * 384 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val pixels = IntArray(384 * 384)

            for (bmp in bitmaps) {
                val resized = Bitmap.createScaledBitmap(bmp, 384, 384, true)
                resized.getPixels(pixels, 0, 384, 0, 0, 384, 384)

                // R Channel
                for (i in pixels.indices) {
                    val r = (pixels[i] shr 16 and 0xFF) / 255.0f
                    byteBuffer.putFloat((r - 0.5f) / 0.5f)
                }
                // G Channel
                for (i in pixels.indices) {
                    val g = (pixels[i] shr 8 and 0xFF) / 255.0f
                    byteBuffer.putFloat((g - 0.5f) / 0.5f)
                }
                // B Channel
                for (i in pixels.indices) {
                    val b = (pixels[i] and 0xFF) / 255.0f
                    byteBuffer.putFloat((b - 0.5f) / 0.5f)
                }
            }

            val base64 = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
            webView?.evaluateJavascript("classifyBatchBase64('$base64', $batchSize);", null)
        }
    }

    private fun processResultJson(json: String, batchSize: Int, topK: Int): List<MushroomPrediction> {
        try {
            loadClasses()
            val arr = JSONArray(json)
            val numClasses = if (classes.isNotEmpty()) classes.size else 2829
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
                    for (c in 0 until numClasses) {
                        meanProbs[c] += (expScores[c] / sumExp.toFloat()) / actualBatch
                    }
                }
            }

            return meanProbs.indices
                .sortedByDescending { meanProbs[it] }
                .take(topK)
                .map { idx ->
                    val species = classes.getOrElse(idx) { "Вид #$idx" }
                    MushroomPrediction(
                        species = species,
                        confidence = meanProbs[idx] * 100f
                    )
                }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    fun close() {
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            } catch (ignored: Exception) {}
            isSessionReady = false
            isInitializing = false
        }
    }
}
