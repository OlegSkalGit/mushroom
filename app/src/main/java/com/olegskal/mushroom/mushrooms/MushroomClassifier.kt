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
    val scientificName: String,
    val isPoisonous: Boolean,
    val confidence: Float,
    val edibility: String,
    val rawLabel: String
)

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
    private var labels: List<String> = emptyList()
    private var pendingCallback: ((List<MushroomPrediction>) -> Unit)? = null

    companion object {
        val REQUIRED_FILES = listOf(
            ModelDownloadItem(
                fileName = "model.onnx",
                primaryUrl = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/model.onnx",
                fallbackUrl = "https://github.com/OlegSkalGit/mushroom/raw/main/model.onnx",
                minSize = 10 * 1024 * 1024L
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
        labels = try {
            context.assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            emptyList()
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
                        mainHandler.post {
                            onReady(success, errorMsg)
                        }
                    }

                    @JavascriptInterface
                    fun onInferenceResult(resultJson: String) {
                        mainHandler.post {
                            val cb = pendingCallback
                            pendingCallback = null
                            if (cb != null) {
                                val preds = processResultJson(resultJson, 5)
                                cb(preds)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onInferenceError(errorMsg: String) {
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
                            urlStr.startsWith("https://local.mushroom/model.onnx") -> {
                                val f = getRequiredFile(context, "model.onnx")
                                WebResourceResponse("application/octet-stream", null, FileInputStream(f))
                            }
                            else -> super.shouldInterceptRequest(view, request)
                        }
                    }
                }

                wv.loadUrl("https://local.mushroom/index.html")
            } catch (e: Exception) {
                isInitializing = false
                onReady(false, e.localizedMessage)
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
                    ort.env.wasm.numThreads = 2;
                    session = await ort.InferenceSession.create("https://local.mushroom/model.onnx", {
                      executionProviders: ['wasm']
                    });
                    AndroidBridge.onModelLoaded(true, "");
                  } catch(e) {
                    AndroidBridge.onModelLoaded(false, e.toString());
                  }
                }

                async function classifyBase64(base64Data) {
                  try {
                    const binaryString = atob(base64Data);
                    const bytes = new Uint8Array(binaryString.length);
                    for (let i = 0; i < binaryString.length; i++) {
                      bytes[i] = binaryString.charCodeAt(i);
                    }
                    const float32 = new Float32Array(bytes.buffer);
                    const tensor = new ort.Tensor('float32', float32, [1, 3, 224, 224]);
                    const feeds = {};
                    feeds[session.inputNames[0]] = tensor;
                    const results = await session.run(feeds);
                    const output = results[session.outputNames[0]].data;
                    AndroidBridge.onInferenceResult(JSON.stringify(Array.from(output)));
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

    fun classify(bitmap: Bitmap, topK: Int = 5, callback: (List<MushroomPrediction>) -> Unit) {
        if (!isSessionReady) {
            initEngine { success, error ->
                if (success) {
                    doInference(bitmap, topK, callback)
                } else {
                    mainHandler.post { callback(emptyList()) }
                }
            }
        } else {
            doInference(bitmap, topK, callback)
        }
    }

    private fun doInference(bitmap: Bitmap, topK: Int, callback: (List<MushroomPrediction>) -> Unit) {
        mainHandler.post {
            pendingCallback = callback
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val byteBuffer = ByteBuffer.allocate(1 * 3 * 224 * 224 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val pixels = IntArray(224 * 224)
            resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

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

            val base64 = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
            webView?.evaluateJavascript("classifyBase64('$base64');", null)
        }
    }

    private fun processResultJson(json: String, topK: Int): List<MushroomPrediction> {
        try {
            val arr = JSONArray(json)
            val logits = FloatArray(arr.length())
            for (i in 0 until arr.length()) {
                logits[i] = arr.getDouble(i).toFloat()
            }

            val maxLogit = logits.maxOrNull() ?: 0f
            val expScores = logits.map { exp(it - maxLogit) }
            val sumExp = expScores.sum()
            val probabilities = expScores.map { (it / sumExp) * 100f }

            return probabilities.indices
                .sortedByDescending { probabilities[it] }
                .take(topK)
                .map { idx ->
                    val raw = labels.getOrElse(idx) { "Unknown" }
                    val isDeadly = raw.contains("(deadly)", ignoreCase = true)
                    val isPoisonous = raw.contains("(poisonous)", ignoreCase = true)
                    val isCondEdible = raw.contains("(conditionally_edible)", ignoreCase = true)
                    val isEdible = raw.contains("(edible)", ignoreCase = true)

                    val clean = raw
                        .replace(Regex("""\s*\([^)]*\)\s*"""), " ")
                        .replace("_", " ")
                        .trim()

                    val meta = MycoKnowledge.resolveMetadata(clean)
                    val edibility = when {
                        isDeadly -> "deadly"
                        meta.edibility == "deadly" -> "deadly"
                        isPoisonous -> if (meta.edibility == "deadly") "deadly" else "toxic"
                        isCondEdible -> "cond-edible"
                        isEdible -> if (meta.edibility != "unknown") meta.edibility else "edible"
                        else -> meta.edibility
                    }

                    MushroomPrediction(
                        species = clean,
                        scientificName = clean,
                        isPoisonous = isPoisonous || isDeadly,
                        confidence = probabilities[idx],
                        edibility = edibility,
                        rawLabel = raw
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
