package com.olegskal.mushroom.mushrooms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.ui.UiUtils

class MushroomClassifierTab(
    private val activity: Activity,
    private var currentLang: String = "uk",
    private val onOpenEncyclopediaDetails: (String) -> Unit
) {

    companion object {
        const val REQ_CAMERA = 301
        const val REQ_GALLERY = 302
    }

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setPadding(16, 16, 16, 16)
    }

    private val previewImageView: ImageView
    private val btnAnalyze: Button
    private val tvStatus: TextView
    private val tvModelStatus: TextView
    private val resultsContainer: LinearLayout
    private val classifier = MushroomClassifier(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentSelectedBitmap: Bitmap? = null

    init {
        val density = activity.resources.displayMetrics.density

        // 1. Actions Row: Camera & Gallery
        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        val btnCamera = UiUtils.createStyledButton(activity, if (currentLang == "uk") "📷 Камера" else "📷 Camera") {
            dispatchCameraIntent()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
            setBackgroundColor(Color.parseColor("#1B2A22"))
        }

        val btnGallery = UiUtils.createStyledButton(activity, if (currentLang == "uk") "🖼️ Галерея" else "🖼️ Gallery") {
            dispatchGalleryIntent()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 0, 0)
            }
            setBackgroundColor(Color.parseColor("#1B2A22"))
        }

        actionsRow.addView(btnCamera)
        actionsRow.addView(btnGallery)
        view.addView(actionsRow)

        // 2. Model Local Storage Status Indicator
        tvModelStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        view.addView(tvModelStatus)
        updateModelStatusBadge()

        // 3. Selected Photo Preview Card
        val previewHeight = (200 * density).toInt()
        val previewFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight).apply {
                setMargins(0, 0, 0, 12)
            }
            setBackgroundColor(Color.parseColor("#111915"))
        }

        previewImageView = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewFrame.addView(previewImageView)

        val placeholderTv = TextView(activity).apply {
            text = if (currentLang == "uk") "Оберіть або сфотографуйте гриб\n(шапинка, гіменофор знизу, ніжка)" else "Take or pick a photo of the mushroom\n(cap, hymenophore underneath, stem)"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        previewFrame.addView(placeholderTv)
        view.addView(previewFrame)

        // 4. Analyze Button
        btnAnalyze = UiUtils.createStyledButton(activity, if (currentLang == "uk") "🔍 Визначити гриб" else "🔍 Identify Mushroom") {
            startClassificationFlow()
        }.apply {
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 12)
            }
        }
        view.addView(btnAnalyze)

        // 5. Status text
        tvStatus = TextView(activity).apply {
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 6, 0, 12)
        }
        view.addView(tvStatus)

        // 6. Results Scroll Container
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        resultsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(resultsContainer)
        view.addView(scrollView)
    }

    fun updateModelStatusBadge() {
        val isReady = MushroomClassifier.isModelDownloaded(activity)
        if (isReady) {
            tvModelStatus.text = if (currentLang == "uk") "✅ AI модель готова (локальне сховище)" else "✅ AI model ready (local storage)"
            tvModelStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvModelStatus.text = if (currentLang == "uk") "⬇️ AI модель відсутня (~87 МБ для завантаження)" else "⬇️ AI model not found (~87 MB to download)"
            tvModelStatus.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    fun setLanguage(lang: String) {
        currentLang = lang
        btnAnalyze.text = if (currentLang == "uk") "🔍 Визначити гриб" else "🔍 Identify Mushroom"
        updateModelStatusBadge()
    }

    fun setInputBitmap(bitmap: Bitmap) {
        currentSelectedBitmap = bitmap
        previewImageView.setImageBitmap(bitmap)
        resultsContainer.removeAllViews()
        tvStatus.visibility = View.GONE
        updateModelStatusBadge()
    }

    private fun dispatchCameraIntent() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivityForResult(intent, REQ_CAMERA)
        } else {
            Toast.makeText(activity, "Camera app not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchGalleryIntent() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        activity.startActivityForResult(Intent.createChooser(intent, "Select Mushroom Photo"), REQ_GALLERY)
    }

    private fun startClassificationFlow() {
        val bmp = currentSelectedBitmap
        if (bmp == null) {
            val msg = if (currentLang == "uk") "Будь ласка, спочатку зробіть або оберіть фото гриба" else "Please take or choose a mushroom photo first"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }

        if (!MushroomClassifier.isModelDownloaded(activity)) {
            promptAndDownloadModel { success ->
                if (success) {
                    executeClassification(bmp)
                }
            }
        } else {
            executeClassification(bmp)
        }
    }

    private fun promptAndDownloadModel(onReady: (Boolean) -> Unit) {
        val title = if (currentLang == "uk") "Завантаження моделі розпізнавання" else "Download Recognition Model"
        val msg = if (currentLang == "uk") "Для автономного визначення грибів потрібна квантована модель (~87 МБ). Завантажити зараз?" else "Quantized vision model (~87 MB) is required for offline identification. Download now?"

        val dialogBuilder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setNegativeButton(if (currentLang == "uk") "Скасувати" else "Cancel") { d, _ ->
                d.dismiss()
                onReady(false)
            }
            .setPositiveButton(if (currentLang == "uk") "Завантажити" else "Download") { d, _ ->
                d.dismiss()
                showDownloadProgressDialog(onReady)
            }

        dialogBuilder.show()
    }

    private fun showDownloadProgressDialog(onReady: (Boolean) -> Unit) {
        val progressDialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 24, 30, 24)
            setBackgroundColor(Color.parseColor("#1B2A22"))
        }

        val tvTitle = TextView(activity).apply {
            text = if (currentLang == "uk") "Завантаження нейромережі..." else "Downloading model..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        val tvPercent = TextView(activity).apply {
            text = "0%"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            gravity = Gravity.RIGHT
        }

        progressDialogView.addView(tvTitle)
        progressDialogView.addView(progressBar)
        progressDialogView.addView(tvPercent)

        val dialog = AlertDialog.Builder(activity)
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        MushroomClassifier.downloadModel(
            context = activity,
            onProgress = { percent ->
                mainHandler.post {
                    progressBar.progress = percent
                    tvPercent.text = "$percent%"
                }
            },
            onComplete = { success, errorMsg ->
                mainHandler.post {
                    dialog.dismiss()
                    if (success) {
                        updateModelStatusBadge()
                        Toast.makeText(activity, if (currentLang == "uk") "Модель успішно завантажена!" else "Model downloaded successfully!", Toast.LENGTH_SHORT).show()
                        onReady(true)
                    } else {
                        Toast.makeText(activity, "Download failed: $errorMsg", Toast.LENGTH_LONG).show()
                        onReady(false)
                    }
                }
            }
        )
    }

    private fun executeClassification(bitmap: Bitmap) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = if (currentLang == "uk") "Аналізуємо гриб за допомогою Vision Transformer..." else "Analyzing mushroom with Vision Transformer..."
        btnAnalyze.isEnabled = false
        resultsContainer.removeAllViews()

        classifier.classify(bitmap, topK = 5) { predictions ->
            btnAnalyze.isEnabled = true
            tvStatus.visibility = View.GONE
            renderPredictions(predictions)
        }
    }

    private fun renderPredictions(predictions: List<MushroomPrediction>) {
        resultsContainer.removeAllViews()
        val density = activity.resources.displayMetrics.density

        if (predictions.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = if (currentLang == "uk") "Не вдалося розпізнати гриб. Спробуйте інший ракурс або чіткіше освітлення." else "Could not identify species. Try another angle or better lighting."
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }
            resultsContainer.addView(tvEmpty)
            return
        }

        val headerTv = TextView(activity).apply {
            text = if (currentLang == "uk") "🎯 Ймовірні кандидати:" else "🎯 Top Candidates:"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        resultsContainer.addView(headerTv)

        predictions.forEachIndexed { index, item ->
            val isTop = index == 0

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (isTop) Color.parseColor("#22382C") else Color.parseColor("#1B2A22"))
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 10)
                }
            }

            // Top row: rank, name, confidence %
            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val rankBadge = TextView(activity).apply {
                text = if (isTop) "⭐ #${index + 1}" else "#${index + 1}"
                setTextColor(if (isTop) Color.parseColor("#F59E0B") else Color.LTGRAY)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 8, 0)
                }
            }
            topRow.addView(rankBadge)

            val nameTv = TextView(activity).apply {
                text = item.species
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(nameTv)

            val confTv = TextView(activity).apply {
                text = "%.1f%%".format(item.confidence)
                setTextColor(if (item.confidence > 50f) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }
            topRow.addView(confTv)
            card.addView(topRow)

            // Confidence progress bar
            val pb = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = item.confidence.toInt()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * density).toInt()).apply {
                    setMargins(0, 6, 0, 8)
                }
            }
            card.addView(pb)

            // Status badge & action button row
            val bottomRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val edibilityBadge = TextView(activity).apply {
                text = MycoKnowledge.getEdibilityLabel(item.edibility, currentLang)
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(MycoKnowledge.getEdibilityColor(item.edibility))
                setPadding(10, 4, 10, 4)
            }
            bottomRow.addView(edibilityBadge)

            val spacer = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }
            bottomRow.addView(spacer)

            val btnOpenCard = Button(activity).apply {
                text = if (currentLang == "uk") "👉 В Енциклопедію" else "👉 In Encyclopedia"
                setTextColor(Color.parseColor("#10B981"))
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    onOpenEncyclopediaDetails(item.scientificName)
                }
            }
            bottomRow.addView(btnOpenCard)

            card.addView(bottomRow)
            resultsContainer.addView(card)
        }
    }

    fun onDestroy() {
        classifier.close()
    }
}
