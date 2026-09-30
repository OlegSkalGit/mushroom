package com.olegskal.mushroom.mushrooms

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.ui.PhotoZoomDialog
import com.olegskal.mushroom.ui.UiUtils

class MushroomClassifierTab(
    private val activity: Activity,
    private var currentLang: String = "uk",
    private val onSearchInEncyclopedia: (String) -> Unit
) {

    companion object {
        const val REQ_CAMERA = 301
        const val REQ_GALLERY = 302
        var isWarningDismissed: Boolean = false
    }

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setPadding(16, 16, 16, 16)
    }

    private val previewImageView: ImageView
    private val placeholderTv: TextView
    private val badgeZoom: TextView
    private val btnAnalyze: Button
    private val warningContainer: LinearLayout
    private val tvWarning: TextView
    private val tvStatus: TextView
    private val modelStatusRow: LinearLayout
    private val tvModelStatus: TextView
    private val btnDownloadModel: Button
    private val thumbnailsBar: LinearLayout
    private val resultsContainer: LinearLayout
    private val classifier = MushroomClassifier(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ClassifierPhoto(
        val original: Bitmap,
        var current: Bitmap,
        var isCropped: Boolean = false
    )

    private val loadedPhotos = mutableListOf<ClassifierPhoto>()
    private var activeIndex = 0

    private fun showPhotoZoom() {
        if (loadedPhotos.isEmpty()) return
        PhotoZoomDialog.showWithCrop(
            activity = activity,
            originalBitmaps = loadedPhotos.map { it.original },
            isCroppedList = loadedPhotos.map { it.isCropped }.toMutableList(),
            initialIndex = activeIndex,
            currentLang = currentLang
        ) { idx, newBitmap, isCropped ->
            if (idx in loadedPhotos.indices) {
                loadedPhotos[idx].current = newBitmap
                loadedPhotos[idx].isCropped = isCropped
                updatePhotosUi()
                resultsContainer.removeAllViews()
                tvStatus.visibility = View.GONE
            }
        }
    }

    init {
        val density = activity.resources.displayMetrics.density

        // 1. Inaccuracy Warning (Red) - dismissible with '✕' until app process restart
        warningContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2A1215"))
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
            visibility = if (isWarningDismissed) View.GONE else View.VISIBLE
        }

        tvWarning = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF5252"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        updateWarningText()
        warningContainer.addView(tvWarning)

        val btnDismissWarning = TextView(activity).apply {
            text = "✕"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF8A80"))
            gravity = Gravity.CENTER
            val p = (6 * density).toInt()
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener {
                isWarningDismissed = true
                warningContainer.visibility = View.GONE
            }
        }
        warningContainer.addView(btnDismissWarning)
        view.addView(warningContainer)

        // 2. Model Status Row (hidden if model is ready; if missing: "Модель розпізнавання відсутня" + [Завантажити])
        modelStatusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.parseColor("#231C14"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        tvModelStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        modelStatusRow.addView(tvModelStatus)

        btnDownloadModel = UiUtils.createStyledButton(activity, if (currentLang == "uk") "Завантажити" else "Download") {
            promptAndDownloadModel { success ->
                if (success) {
                    updateModelStatusBadge()
                }
            }
        }.apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            val padBtnH = (12 * density).toInt()
            val padBtnV = (4 * density).toInt()
            setPadding(padBtnH, padBtnV, padBtnH, padBtnV)
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(6, 0, 0, 0)
            }
        }
        modelStatusRow.addView(btnDownloadModel)
        view.addView(modelStatusRow)
        updateModelStatusBadge()

        // 3. Thumbnails Row (up to 3 photos)
        thumbnailsBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        view.addView(thumbnailsBar)
        updateThumbnailsUi()

        // 4. Selected Photo Preview Card
        val previewHeight = (180 * density).toInt()
        val previewFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight).apply {
                setMargins(0, 0, 0, 10)
            }
            setBackgroundColor(Color.parseColor("#111915"))
            isClickable = true
            setOnClickListener {
                if (loadedPhotos.isEmpty()) {
                    showAddPhotoDialog()
                } else {
                    showPhotoZoom()
                }
            }
        }

        previewImageView = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                if (loadedPhotos.isNotEmpty()) {
                    showPhotoZoom()
                }
            }
        }
        previewFrame.addView(previewImageView)

        badgeZoom = TextView(activity).apply {
            text = if (currentLang == "uk") "🔍 Збільшити" else "🔍 Zoom"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setBackgroundColor(Color.parseColor("#99000000"))
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins((10 * density).toInt(), 0, 0, (10 * density).toInt())
            }
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                if (loadedPhotos.isNotEmpty()) {
                    showPhotoZoom()
                }
            }
        }
        previewFrame.addView(badgeZoom)

        placeholderTv = TextView(activity).apply {
            text = if (currentLang == "uk") {
                "Оберіть або сфотографуйте гриб\n(можна до 5 фото для комбінованого аналізу)"
            } else {
                "Take or pick mushroom photos\n(up to 5 photos for ensemble analysis)"
            }
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        previewFrame.addView(placeholderTv)
        view.addView(previewFrame)

        // 5. Analyze Button
        btnAnalyze = UiUtils.createStyledButton(activity, if (currentLang == "uk") "🔍 Визначити гриб" else "🔍 Identify Mushroom") {
            startClassificationFlow()
        }.apply {
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
        }
        view.addView(btnAnalyze)

        // 7. Status text
        tvStatus = TextView(activity).apply {
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 4, 0, 8)
        }
        view.addView(tvStatus)

        // 8. Results Scroll Container
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
        val status = MushroomClassifier.checkModelStatus(activity)
        when (status) {
            MushroomClassifier.ModelStatus.READY -> {
                modelStatusRow.visibility = View.GONE
            }
            MushroomClassifier.ModelStatus.NEEDS_UPDATE -> {
                modelStatusRow.visibility = View.VISIBLE
                tvModelStatus.text = if (currentLang == "uk") {
                    "⚠️ Розмір файлів моделі застарілий. Потрібно оновити модель."
                } else {
                    "⚠️ Model files outdated. Model update required."
                }
                btnDownloadModel.text = if (currentLang == "uk") "Оновити" else "Update"
            }
            MushroomClassifier.ModelStatus.MISSING -> {
                modelStatusRow.visibility = View.VISIBLE
                tvModelStatus.text = if (currentLang == "uk") "Модель розпізнавання відсутня" else "Recognition model missing"
                btnDownloadModel.text = if (currentLang == "uk") "Завантажити" else "Download"
            }
        }
    }

    private fun updateWarningText() {
        tvWarning.text = if (currentLang == "uk") {
            "⚠️ УВАГА: Метод розпізнавання не є 100% точним! Ніколи не вживайте гриби, покладаючись лише на визначник."
        } else {
            "⚠️ WARNING: Recognition method is not 100% accurate! Never consume mushrooms relying solely on the classifier."
        }
    }

    fun setLanguage(lang: String) {
        currentLang = lang
        updateAnalyzeButtonText()
        updateModelStatusBadge()
        updateWarningText()
        badgeZoom.text = if (currentLang == "uk") "🔍 Збільшити" else "🔍 Zoom"
        placeholderTv.text = if (currentLang == "uk") {
            "Оберіть або сфотографуйте гриб\n(можна до 5 фото для комбінованого аналізу)"
        } else {
            "Take or pick mushroom photos\n(up to 5 photos for ensemble analysis)"
        }
        warningContainer.visibility = if (isWarningDismissed) View.GONE else View.VISIBLE
        updatePhotosUi()
    }

    fun addInputBitmap(bitmap: Bitmap) {
        if (loadedPhotos.size < 5) {
            loadedPhotos.add(ClassifierPhoto(original = bitmap, current = bitmap, isCropped = false))
            activeIndex = loadedPhotos.size - 1
            updatePhotosUi()
            resultsContainer.removeAllViews()
            tvStatus.visibility = View.GONE
        } else {
            val msg = if (currentLang == "uk") "Вже додано максимум 5 фото" else "Maximum 5 photos reached"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun addInputBitmaps(bitmaps: List<Bitmap>) {
        val remaining = 5 - loadedPhotos.size
        if (remaining > 0) {
            for (b in bitmaps.take(remaining)) {
                loadedPhotos.add(ClassifierPhoto(original = b, current = b, isCropped = false))
            }
            activeIndex = loadedPhotos.size - 1
            updatePhotosUi()
            resultsContainer.removeAllViews()
            tvStatus.visibility = View.GONE
        } else {
            val msg = if (currentLang == "uk") "Вже додано максимум 5 фото" else "Maximum 5 photos reached"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun setInputBitmap(bitmap: Bitmap) {
        loadedPhotos.clear()
        addInputBitmap(bitmap)
    }

    fun setInputBitmaps(bitmaps: List<Bitmap>) {
        loadedPhotos.clear()
        addInputBitmaps(bitmaps)
    }

    fun clearPhotos() {
        loadedPhotos.clear()
        activeIndex = 0
        updatePhotosUi()
        resultsContainer.removeAllViews()
        tvStatus.visibility = View.GONE
    }

    private fun removePhoto(idx: Int) {
        if (idx in loadedPhotos.indices) {
            loadedPhotos.removeAt(idx)
            if (activeIndex >= loadedPhotos.size) {
                activeIndex = maxOf(0, loadedPhotos.size - 1)
            }
            updatePhotosUi()
            resultsContainer.removeAllViews()
            tvStatus.visibility = View.GONE
        }
    }

    private fun updatePhotosUi() {
        if (loadedPhotos.isNotEmpty()) {
            val idx = activeIndex.coerceIn(0, loadedPhotos.size - 1)
            previewImageView.setImageBitmap(loadedPhotos[idx].current)
            previewImageView.visibility = View.VISIBLE
            badgeZoom.visibility = View.VISIBLE
            placeholderTv.visibility = View.GONE
        } else {
            previewImageView.setImageBitmap(null)
            previewImageView.visibility = View.GONE
            badgeZoom.visibility = View.GONE
            placeholderTv.visibility = View.VISIBLE
        }
        updateThumbnailsUi()
        updateAnalyzeButtonText()
    }

    private fun updateThumbnailsUi() {
        thumbnailsBar.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val size = (44 * density).toInt()

        for (i in 0 until 5) {
            if (i < loadedPhotos.size) {
                val isActive = (i == activeIndex)
                val isCropped = loadedPhotos[i].isCropped
                val slot = FrameLayout(activity).apply {
                    val p = (2 * density).toInt()
                    setPadding(p, p, p, p)
                    setBackgroundColor(if (isActive) Color.parseColor("#10B981") else Color.parseColor("#374151"))
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins(0, 0, (6 * density).toInt(), 0)
                    }
                    isClickable = true
                    setOnClickListener {
                        activeIndex = i
                        updatePhotosUi()
                    }
                }

                val iv = ImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(loadedPhotos[i].current)
                }
                slot.addView(iv)

                if (isCropped) {
                    val cropBadge = TextView(activity).apply {
                        text = "✂"
                        setTextColor(Color.WHITE)
                        textSize = 8.5f
                        setBackgroundColor(Color.parseColor("#CC059669"))
                        val p = (2 * density).toInt()
                        setPadding(p, 0, p, 0)
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.BOTTOM or Gravity.START
                        }
                    }
                    slot.addView(cropBadge)
                }

                val btnDel = TextView(activity).apply {
                    text = "✕"
                    setTextColor(Color.WHITE)
                    textSize = 9f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundColor(Color.parseColor("#EF4444"))
                    gravity = Gravity.CENTER
                    val btnSize = (16 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                        gravity = Gravity.TOP or Gravity.RIGHT
                    }
                    setOnClickListener {
                        removePhoto(i)
                    }
                }
                slot.addView(btnDel)
                thumbnailsBar.addView(slot)
            } else if (i == loadedPhotos.size) {
                val slot = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#1B2A22"))
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins(0, 0, (6 * density).toInt(), 0)
                    }
                    isClickable = true
                    setOnClickListener {
                        showAddPhotoDialog()
                    }
                }

                val plusTv = TextView(activity).apply {
                    text = "➕"
                    textSize = 12f
                    gravity = Gravity.CENTER
                }
                val labelTv = TextView(activity).apply {
                    text = "${i + 1}"
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 9.5f
                    gravity = Gravity.CENTER
                }
                slot.addView(plusTv)
                slot.addView(labelTv)
                thumbnailsBar.addView(slot)
            }
        }

        val counterTv = TextView(activity).apply {
            text = "${loadedPhotos.size} / 5"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(6, 0, 0, 0)
        }
        thumbnailsBar.addView(counterTv)

        if (loadedPhotos.isNotEmpty()) {
            val spacer = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }
            thumbnailsBar.addView(spacer)

            val btnClearAll = TextView(activity).apply {
                text = "🗑️"
                textSize = 18f
                gravity = Gravity.CENTER
                val p = (6 * density).toInt()
                setPadding(p, p, p, p)
                isClickable = true
                setOnClickListener {
                    clearPhotos()
                }
            }
            thumbnailsBar.addView(btnClearAll)
        }
    }

    private fun updateAnalyzeButtonText() {
        val count = loadedPhotos.size
        btnAnalyze.text = if (currentLang == "uk") {
            if (count > 1) "🔍 Визначити гриб ($count фото)" else "🔍 Визначити гриб"
        } else {
            if (count > 1) "🔍 Identify Mushroom ($count photos)" else "🔍 Identify Mushroom"
        }
    }

    private fun showAddPhotoDialog() {
        if (loadedPhotos.size >= 5) {
            val msg = if (currentLang == "uk") "Вже додано максимум 5 фото" else "Maximum 5 photos reached"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }
        val options = if (currentLang == "uk") {
            arrayOf("📷 Камера", "🖼️ Галерея")
        } else {
            arrayOf("📷 Camera", "🖼️ Gallery")
        }
        AlertDialog.Builder(activity)
            .setTitle(if (currentLang == "uk") "Додати фото гриба (до 5)" else "Add Mushroom Photo (up to 5)")
            .setItems(options) { _, which ->
                if (which == 0) {
                    dispatchCameraIntent()
                } else {
                    dispatchGalleryIntent()
                }
            }
            .setNegativeButton(if (currentLang == "uk") "Скасувати" else "Cancel", null)
            .show()
    }

    private fun dispatchCameraIntent() {
        if (loadedPhotos.size >= 5) {
            val msg = if (currentLang == "uk") "Вже додано максимум 5 фото" else "Maximum 5 photos reached"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }
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
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val title = if (currentLang == "uk") "Оберіть фото грибів" else "Select Mushroom Photos"
        val chooser = Intent.createChooser(intent, title).apply {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.startActivityForResult(chooser, REQ_GALLERY)
    }

    private fun startClassificationFlow() {
        if (loadedPhotos.isEmpty()) {
            val msg = if (currentLang == "uk") "Будь ласка, спочатку зробіть або оберіть фото гриба" else "Please take or choose a mushroom photo first"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }

        val status = MushroomClassifier.checkModelStatus(activity)
        if (status == MushroomClassifier.ModelStatus.READY) {
            executeClassification()
        } else {
            val isUpdate = (status == MushroomClassifier.ModelStatus.NEEDS_UPDATE)
            promptAndDownloadModel(isUpdate = isUpdate) { success ->
                if (success) {
                    executeClassification()
                } else if (isUpdate && MushroomClassifier.isModelAvailable(activity)) {
                    executeClassification()
                }
            }
        }
    }

    private fun promptAndDownloadModel(isUpdate: Boolean = false, onReady: (Boolean) -> Unit) {
        val title = if (isUpdate) {
            if (currentLang == "uk") "Потрібно оновити модель нейромережі" else "Update Recognition Model"
        } else {
            if (currentLang == "uk") "Завантаження моделі розпізнавання" else "Download Recognition Model"
        }

        val msg = if (isUpdate) {
            if (currentLang == "uk") {
                "Розмір встановлених файлів моделі не співпадає з актуальною версією.\n\n" +
                "Оновлення містить актуальні моделі (BEiT та Swin INT8 .ort) та компоненти.\n\n" +
                "Оновити модель зараз (архів ~139 МБ, 2 томи)?"
            } else {
                "Installed model files differ from current version.\n\n" +
                "Update contains fresh neural models (BEiT & Swin INT8 .ort) and runtime.\n\n" +
                "Update model now (archive ~139 MB, 2 volumes)?"
            }
        } else {
            if (currentLang == "uk") {
                "Для автономного визначення грибів потрібен архів моделі (~139 МБ, 2 томи). Після завантаження його буде автоматично розпаковано. Завантажити зараз?"
            } else {
                "Offline mushroom recognition requires model archive (~139 MB, 2 volumes). It will be unpacked automatically. Download now?"
            }
        }

        val positiveBtn = if (isUpdate) {
            if (currentLang == "uk") "Оновити модель" else "Update"
        } else {
            if (currentLang == "uk") "Завантажити" else "Download"
        }

        val negativeBtn = if (isUpdate) {
            if (currentLang == "uk") "Пізніше" else "Later"
        } else {
            if (currentLang == "uk") "Скасувати" else "Cancel"
        }

        val dialogBuilder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(isUpdate)
            .setNegativeButton(negativeBtn) { d, _ ->
                d.dismiss()
                onReady(false)
            }
            .setPositiveButton(positiveBtn) { d, _ ->
                d.dismiss()
                showDownloadProgressDialog(isUpdate, onReady)
            }

        dialogBuilder.show()
    }

    private fun showDownloadProgressDialog(isUpdate: Boolean, onReady: (Boolean) -> Unit) {
        val progressDialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 24, 30, 24)
            setBackgroundColor(Color.parseColor("#1B2A22"))
        }

        val tvTitle = TextView(activity).apply {
            text = if (isUpdate) {
                if (currentLang == "uk") "Оновлення нейромережі..." else "Updating model..."
            } else {
                if (currentLang == "uk") "Завантаження нейромережі..." else "Downloading model..."
            }
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

        val tvSubtitle = TextView(activity).apply {
            text = ""
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(0, 4, 0, 0)
        }

        progressDialogView.addView(tvTitle)
        progressDialogView.addView(progressBar)
        progressDialogView.addView(tvPercent)
        progressDialogView.addView(tvSubtitle)

        val dialog = AlertDialog.Builder(activity)
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        MushroomClassifier.downloadModel(
            context = activity,
            forceDownload = isUpdate,
            onProgress = { percent, statusText ->
                mainHandler.post {
                    progressBar.progress = percent
                    tvPercent.text = "$percent%"
                    tvSubtitle.text = statusText
                }
            },
            onComplete = { success, errorMsg ->
                mainHandler.post {
                    dialog.dismiss()
                    if (success) {
                        updateModelStatusBadge()
                        val okMsg = if (isUpdate) {
                            if (currentLang == "uk") "Модель успішно оновлено!" else "Model updated successfully!"
                        } else {
                            if (currentLang == "uk") "Модель успішно завантажена!" else "Model downloaded successfully!"
                        }
                        Toast.makeText(activity, okMsg, Toast.LENGTH_SHORT).show()
                        onReady(true)
                    } else {
                        Toast.makeText(activity, "Download failed: $errorMsg", Toast.LENGTH_LONG).show()
                        onReady(false)
                    }
                }
            }
        )
    }

    private fun executeClassification() {
        val count = loadedPhotos.size
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = if (currentLang == "uk") {
            if (count > 1) "⏳ Виконується аналіз нейромережами (ансамбль з $count фото)..." else "⏳ Виконується аналіз нейромережами..."
        } else {
            if (count > 1) "⏳ Running neural analysis (ensemble of $count photos)..." else "⏳ Running neural analysis..."
        }
        btnAnalyze.isEnabled = false
        resultsContainer.removeAllViews()

        classifier.classify(loadedPhotos.map { it.current }, topK = 5) { predictions ->
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
                val err = classifier.lastError
                text = if (err != null) {
                    if (currentLang == "uk") "⚠️ Помилка моделі: $err" else "⚠️ Model error: $err"
                } else {
                    if (currentLang == "uk") "Не вдалося розпізнати гриб. Спробуйте інший ракурс або чіткіше освітлення." else "Could not identify species. Try another angle or better lighting."
                }
                setTextColor(if (err != null) Color.parseColor("#F87171") else Color.parseColor("#9CA3AF"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }
            resultsContainer.addView(tvEmpty)
            return
        }

        val count = loadedPhotos.size
        val headerTv = TextView(activity).apply {
            text = if (currentLang == "uk") {
                if (count > 1) "🎯 Результати (ансамбль з $count фото):" else "🎯 Ймовірні кандидати (Top-5):"
            } else {
                if (count > 1) "🎯 Results (ensemble of $count photos):" else "🎯 Top Candidates (Top-5):"
            }
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        resultsContainer.addView(headerTv)

        val hintTv = TextView(activity).apply {
            text = if (currentLang == "uk") "💡 Натисніть на картку для копіювання назви та пошуку в енциклопедії" else "💡 Tap card to copy name & search in encyclopedia"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(0, 0, 0, 10)
        }
        resultsContainer.addView(hintTv)

        predictions.forEachIndexed { index, item ->
            val isTop = index == 0

            val onCardAction = {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Mushroom Name", item.species)
                clipboard.setPrimaryClip(clip)

                val msg = if (currentLang == "uk") "Скопійовано: ${item.species} → Енциклопедія" else "Copied: ${item.species} → Encyclopedia"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

                onSearchInEncyclopedia(item.species)
            }

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (isTop) Color.parseColor("#22382C") else Color.parseColor("#1B2A22"))
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 10)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onCardAction() }
            }

            // Top row: rank, name, edibility badge, confidence %
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
                    setMargins(0, 0, 6, 0)
                }
            }
            topRow.addView(rankBadge)

            val nameTv = TextView(activity).apply {
                text = item.species
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 0)
                }
            }
            topRow.addView(nameTv)

            val edibility = item.edibility
            val edibilityBadge = TextView(activity).apply {
                text = MycoKnowledge.getEdibilityLabel(edibility, currentLang)
                setTextColor(Color.WHITE)
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(MycoKnowledge.getEdibilityColor(edibility))
                val padH = (6 * density).toInt()
                val padV = (2 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 6, 0)
                }
            }
            topRow.addView(edibilityBadge)

            val hymeniumBadge = TextView(activity).apply {
                text = MycoKnowledge.getHymeniumIcon(item.hymenium)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 8, 0)
                }
            }
            topRow.addView(hymeniumBadge)

            val confTv = TextView(activity).apply {
                text = "%.1f%%".format(item.confidence)
                setTextColor(if (item.confidence > 50f) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
            }
            topRow.addView(confTv)
            card.addView(topRow)

            // Confidence progress bar
            val pb = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = item.confidence.toInt().coerceIn(0, 100)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * density).toInt()).apply {
                    setMargins(0, 6, 0, 0)
                }
            }
            card.addView(pb)

            resultsContainer.addView(card)
        }
    }

    fun onDestroy() {
        classifier.close()
    }
}
