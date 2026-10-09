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
    val edibility: String = "unknown",
    val hymenium: String = "other",
    val beitConfidence: Float = 0f,
    val swinConfidence: Float = 0f
) {
    val scientificName: String get() = species
}

class MushroomClassifier(private val context: Context) {

    // ВИНЕСЕНО НА РІВЕНЬ КЛАСУ (тепер доступно як MushroomClassifier.ModelStatus)
    enum class ModelStatus {
        MISSING,
        NEEDS_UPDATE,
        READY
    }

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
         * Завантаження багатотомного архіву моделі з підтримкою докачування (HTTP Range Request)
         */
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

                // 1. Завантаження всіх томів .z01, .zip
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

                // 2. Потокове розпакування через MultiVolumeZipInputStream
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

        /**
         * Потокове розпакування багатотомного архіву AI-моделі та валідація файлів.
         */
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

    init {
        loadClasses()
        MycoKnowledge.init(context)
    }

    private fun loadClasses() {
        if (classes.isNotEmpty()) return
        try {
            context.assets.open("classes.json").bufferedReader(Charsets.UTF_8).use { reader ->
                val jsonStr = reader.readText()
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
                    fun onDualInferenceResult(beitJson: String, swinJson: String, batchSize: Int) {
                        lastError = null
                        mainHandler.post {
                            val cb = pendingCallback
                            pendingCallback = null
                            if (cb != null) {
                                val preds = processDualResultJson(beitJson, swinJson, batchSize, pendingTopK)
                                cb(preds)
                            }
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
                            urlStr.startsWith("https://local.mushroom/beit_base_384_arm_int8.ort") -> {
                                val f = getRequiredFile(context, "beit_base_384_arm_int8.ort")
                                WebResourceResponse("application/octet-stream", null, FileInputStream(f))
                            }
                            urlStr.startsWith("https://local.mushroom/swin_base_384_arm_int8.ort") -> {
                                val f = getRequiredFile(context, "swin_base_384_arm_int8.ort")
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
                let sessionBeit = null;
                let sessionSwin = null;

                async function initSession() {
                  try {
                    ort.env.wasm.wasmPaths = "https://local.mushroom/";
                    ort.env.wasm.numThreads = 2;

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
                    AndroidBridge.onModelLoaded(true, "");
                  } catch(e) {
                    console.error("InitSession error:", e);
                    AndroidBridge.onModelLoaded(false, e.toString());
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

                async function classifyBatchBase64(base64Data, batchSize) {
                  try {
                    const binaryString = atob(base64Data);
                    const bytes = new Uint8Array(binaryString.length);
                    for (let i = 0; i < binaryString.length; i++) {
                      bytes[i] = binaryString.charCodeAt(i);
                    }
                    const floatsPerImage = 3 * 384 * 384;

                    const [outBeit, outSwin] = await Promise.all([
                      runSingleModel(sessionBeit, bytes, batchSize, floatsPerImage),
                      runSingleModel(sessionSwin, bytes, batchSize, floatsPerImage)
                    ]);

                    AndroidBridge.onDualInferenceResult(JSON.stringify(outBeit), JSON.stringify(outSwin), batchSize);
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

            val base64 = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
            webView?.evaluateJavascript("classifyBatchBase64('$base64', $batchSize);", null)
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

    private fun processResultJson(json: String, batchSize: Int, topK: Int): List<MushroomPrediction> {
        try {
            loadClasses()
            val numClasses = if (classes.isNotEmpty()) classes.size else 2829
            val arr = JSONArray(json)
            val meanProbs = calculateBatchProbs(arr, batchSize, numClasses)

            return meanProbs.indices
                .sortedByDescending { meanProbs[it] }
                .take(topK)
                .map { idx ->
                    val species = classes.getOrElse(idx) { "Вид #$idx" }
                    val meta = MycoKnowledge.resolveMetadata(species)
                    MushroomPrediction(
                        species = species,
                        confidence = meanProbs[idx] * 100f,
                        edibility = meta.edibility,
                        hymenium = meta.hymenium
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
