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
import java.util.zip.ZipInputStream
import kotlin.math.exp

data class MushroomPrediction(
    val species: String,
    val confidence: Float,
    val edibility: String = "unknown"
) {
    val scientificName: String get() = species
}

data class ModelDownloadItem(
    val fileName: String,
    val exactSize: Long
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
        val MODEL_ZIP_NAME get() = MushroomDataConfig.MODEL_ZIP_NAME
        val MODEL_ZIP_FULL_SIZE get() = MushroomDataConfig.MODEL_ZIP_FULL_SIZE
        val MODEL_ZIP_PRIMARY_URL get() = MushroomDataConfig.MODEL_ZIP_PRIMARY_URL
        val MODEL_ZIP_FALLBACK_URL get() = MushroomDataConfig.MODEL_ZIP_FALLBACK_URL
        val REQUIRED_FILES get() = MushroomDataConfig.MODEL_REQUIRED_FILES

        enum class ModelStatus {
            MISSING,
            NEEDS_UPDATE,
            READY
        }

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
            // 1. Перевірка наявності файлів
            for (item in REQUIRED_FILES) {
                val file = getRequiredFile(context, item.fileName)
                if (!file.exists() || file.length() == 0L) {
                    return ModelStatus.MISSING
                }
            }

            // 2. Перевірка точного збігу розмірів
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

        /**
         * Завантаження моделі з підтримкою докачування (HTTP Range Request)
         */
        fun downloadModel(
            context: Context,
            forceDownload: Boolean = false,
            onProgress: (Int) -> Unit,
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
                    if (sd.canWrite()) sd else File(context.filesDir, "model").apply { mkdirs() }
                } catch (e: Exception) {
                    File(context.filesDir, "model").apply { mkdirs() }
                }

                val zipFile = File(targetDir, "$MODEL_ZIP_NAME.tmp")

                if (forceDownload && zipFile.exists() && zipFile.length() >= MODEL_ZIP_FULL_SIZE) {
                    zipFile.delete()
                }

                val urlsToTry = listOf(MODEL_ZIP_PRIMARY_URL, MODEL_ZIP_FALLBACK_URL)
                var downloadSuccess = false
                var lastError: String? = null

                for (urlCandidate in urlsToTry) {
                    var conn: HttpURLConnection? = null
                    var input: InputStream? = null
                    var output: FileOutputStream? = null

                    try {
                        val existingLength = if (zipFile.exists()) zipFile.length() else 0L
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

                        if (responseCode != 200 && responseCode != 206) {
                            throw Exception("HTTP помилка: $responseCode")
                        }

                        val isResume = (responseCode == 206)
                        val append = isResume && existingLength > 0
                        val startingBytes = if (append) existingLength else 0L

                        val fileLength = if (isResume) {
                            val contentRange = conn!!.getHeaderField("Content-Range")
                            val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                            totalFromRange ?: (startingBytes + conn.contentLengthLong)
                        } else {
                            conn!!.contentLengthLong.takeIf { it > 0 } ?: MODEL_ZIP_FULL_SIZE
                        }

                        input = conn.inputStream
                        output = FileOutputStream(zipFile, append)

                        val data = ByteArray(16384)
                        var total: Long = startingBytes
                        var count: Int

                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            output.write(data, 0, count)

                            if (fileLength > 0) {
                                val downloadPercent = ((total * 85) / fileLength).toInt().coerceIn(0, 85)
                                onProgress(downloadPercent)
                            }
                        }

                        output.flush()
                        output.close()
                        output = null
                        input.close()
                        input = null
                        conn.disconnect()

                        if (zipFile.exists() && zipFile.length() == MODEL_ZIP_FULL_SIZE) {
                            downloadSuccess = true
                            break
                        } else {
                            throw Exception("Невірний розмір архіву: ${zipFile.length()} байт (очікувалось $MODEL_ZIP_FULL_SIZE)")
                        }
                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: e.toString()
                    } finally {
                        try { input?.close() } catch (ignored: Exception) {}
                        try { output?.close() } catch (ignored: Exception) {}
                        try { conn?.disconnect() } catch (ignored: Exception) {}
                    }
                }

                if (!downloadSuccess) {
                    onComplete(false, lastError ?: "Не вдалося завантажити $MODEL_ZIP_NAME")
                    return@Thread
                }

                onProgress(90)
                try {
                    val zipIn = ZipInputStream(FileInputStream(zipFile))
                    var entry = zipIn.nextEntry
                    val buffer = ByteArray(16384)

                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = File(entry.name).name
                            if (fileName.isNotEmpty()) {
                                val targetFile = File(targetDir, fileName)
                                if (targetFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                                    val tempOut = File(targetDir, "$fileName.tmp")
                                    FileOutputStream(tempOut).use { outStream ->
                                        var len: Int
                                        while (zipIn.read(buffer).also { len = it } != -1) {
                                            outStream.write(buffer, 0, len)
                                        }
                                        outStream.flush()
                                    }
                                    if (targetFile.exists()) targetFile.delete()
                                    tempOut.renameTo(targetFile)
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                    zipIn.close()
                } catch (e: Exception) {
                    try { if (zipFile.exists()) zipFile.delete() } catch (ignored: Exception) {}
                    onComplete(false, "Помилка розпакування: ${e.localizedMessage ?: e.toString()}")
                    return@Thread
                }

                try {
                    if (zipFile.exists()) zipFile.delete()
                } catch (ignored: Exception) {}

                onProgress(98)

                if (!isModelDownloaded(context)) {
                    onComplete(false, "Перевірка розмірів розпакованих файлів не вдалася")
                    return@Thread
                }

                onProgress(100)
                MycoKnowledge.reloadLabels(context)
                onComplete(true, null)
            }.start()
        }
    }

    init {
        loadClasses()
        MycoKnowledge.init(context)
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
                    list.add(obj.optString("species", "РќРµРІС–РґРѕРјРёР№ РІРёРґ"))
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
                    val species = classes.getOrElse(idx) { "Р’РёРґ #$idx" }
                    val meta = MycoKnowledge.resolveMetadata(species)
                    MushroomPrediction(
                        species = species,
                        confidence = meanProbs[idx] * 100f,
                        edibility = meta.edibility
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
