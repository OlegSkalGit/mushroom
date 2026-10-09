package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.map.CountryStatus
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.map.MapDownloadManager
import com.olegskal.mushroom.map.OsmTileEngine
import com.olegskal.mushroom.map.PoiManager
import com.olegskal.mushroom.mushrooms.MushroomClassifier
import com.olegskal.mushroom.mushrooms.MushroomDataConfig
import com.olegskal.mushroom.mushrooms.MushroomDatabaseManager
import com.olegskal.mushroom.network.AppUpdateManager
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.getAppVersionName
import java.util.Locale
import kotlin.math.roundToInt

object InitialSetupDialog {

    fun showIfNeeded(activity: Activity, onFinished: () -> Unit = {}) {
        if (activity.isFinishing) return

        // 1. Якщо галка активна ("Не показувати при наступному запуску") - не показуємо
        if (AppPrefs.isInitialSetupDismissed(activity)) {
            return
        }

        // 2. Перевірка локальних компонентів
        val isWorldMissing = !MapDownloadManager.isWorldMapReady()
        val detectedCountry = MapDownloadManager.detectCurrentCountry(activity)
        val countryStatus = MapDownloadManager.getCountryStatus(detectedCountry)
        val isCountryMissingOrUpdate = countryStatus != CountryStatus.READY
        val isDbMissingOrUpdate = MushroomDatabaseManager.checkDatabaseStatus(activity) != MushroomDatabaseManager.DataStatus.READY
        val isModelMissingOrUpdate = MushroomClassifier.checkModelStatus(activity) != MushroomClassifier.ModelStatus.READY

        // Якщо хоча б один компонент не завантажено або потребує оновлення - показуємо одразу
        if (isWorldMissing || isCountryMissingOrUpdate || isDbMissingOrUpdate || isModelMissingOrUpdate) {
            show(activity, onFinished)
            return
        }

        // 3. Якщо всі компоненти готові - перевіряємо мережеві оновлення (додаток та карта)
        val dialogShown = java.util.concurrent.atomic.AtomicBoolean(false)
        fun triggerShow() {
            if (dialogShown.compareAndSet(false, true)) {
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        show(activity, onFinished)
                    }
                }
            }
        }

        AppUpdateManager.checkUpdateStatusAsync(activity) { hasAppUpdate, _, _, _ ->
            if (hasAppUpdate) {
                triggerShow()
            }
        }

        MapDownloadManager.checkCountryUpdatesAsync(detectedCountry) { hasCountryUpdate ->
            if (hasCountryUpdate) {
                triggerShow()
            }
        }

        MapDownloadManager.checkWorldMapUpdatesAsync { hasWorldUpdate ->
            if (hasWorldUpdate) {
                triggerShow()
            }
        }
    }

    fun show(activity: Activity, onFinished: () -> Unit = {}) {
        val isUk = AppPrefs.isUk(activity)
        val dialog = Dialog(activity)
        dialog.setTitle(if (isUk) "Початкове налаштування" else "Initial Setup")
        dialog.setCanceledOnTouchOutside(false)

        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B221B"))
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }

        // 1. Header
        val titleTv = TextView(activity).apply {
            text = if (isUk) "🚀 Початкове налаштування Mushroom" else "🚀 Mushroom Initial Setup"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subtitleTv = TextView(activity).apply {
            text = if (isUk) {
                "Для повної автономної роботи в лісі без інтернету позначте необхідні компоненти:"
            } else {
                "For full offline standalone operation in the woods, select components to download:"
            }
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(subtitleTv)

        // Scroll container for components
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val cardsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun createCardBg(): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor("#263226"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#374737"))
            }
        }

        fun createCard(): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = createCardBg()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.setMargins(0, 0, 0, dp(8))
                layoutParams = p
            }
        }

        // --- COMPONENT 1: World Overview Map (world.map) ---
        val cardWorld = createCard()
        val rowWorldHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cbWorld = CheckBox(activity).apply {
            text = if (isUk) "🗺️ Оглядова карта світу (world.map)" else "🗺️ World Overview Map (world.map)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvWorldStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        rowWorldHeader.addView(cbWorld)
        rowWorldHeader.addView(tvWorldStatus)

        val tvWorldSub = TextView(activity).apply {
            text = if (isUk) "Базові контури материків, океанів та планети для огляду на будь-якому масштабі (~3.1 МБ)"
            else "Global outlines of continents, oceans, and borders at all zoom scales (~3.1 MB)"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(dp(30), 0, 0, dp(2))
        }
        cardWorld.addView(rowWorldHeader)
        cardWorld.addView(tvWorldSub)
        cardsContainer.addView(cardWorld)

        // --- COMPONENT 2: Current Country Map & Routing ---
        var selectedCountry: MapCountry = MapDownloadManager.detectCurrentCountry(activity)

        val cardCountry = createCard()
        val rowCountryHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cbCountry = CheckBox(activity).apply {
            text = if (isUk) "📍 Карта країни та офлайн-навігація" else "📍 Country Map & Offline Navigation"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvCountryStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        rowCountryHeader.addView(cbCountry)
        rowCountryHeader.addView(tvCountryStatus)

        val rowCountryPicker = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), dp(2), 0, dp(2))
        }
        val tvCountryDetected = TextView(activity).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnCountryChange = Button(activity).apply {
            text = if (isUk) "🌐 Вибрати іншу" else "🌐 Choose other"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#374151"))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
        }
        rowCountryPicker.addView(tvCountryDetected)
        rowCountryPicker.addView(btnCountryChange)

        val tvCountrySub = TextView(activity).apply {
            text = if (isUk) "Векторна карта (.map), точки POI (.poi) та пішохідна/авто навігація BRouter (.rd5)"
            else "Vector map (.map), POI points (.poi), and BRouter navigation (.rd5)"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(dp(30), 0, 0, dp(2))
        }
        cardCountry.addView(rowCountryHeader)
        cardCountry.addView(rowCountryPicker)
        cardCountry.addView(tvCountrySub)
        cardsContainer.addView(cardCountry)

        // --- COMPONENT 3: Offline Mushroom Encyclopedia (mushrooms.db) ---
        val cardDb = createCard()
        val rowDbHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cbDb = CheckBox(activity).apply {
            text = if (isUk) "🍄 Офлайн-енциклопедія грибів (mushrooms.db)" else "🍄 Offline Encyclopedia (mushrooms.db)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvDbStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        rowDbHeader.addView(cbDb)
        rowDbHeader.addView(tvDbStatus)

        val tvDbSub = TextView(activity).apply {
            text = if (isUk) "Повний каталог видів, фотографії високої якості та описи для роботи без зв'язку (~538 МБ)"
            else "Species catalog, high-res photos, and descriptions for offline use (~538 MB)"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(dp(30), 0, 0, dp(2))
        }
        cardDb.addView(rowDbHeader)
        cardDb.addView(tvDbSub)
        cardsContainer.addView(cardDb)

        // --- COMPONENT 4: AI Mushroom Classifier (model.zip) ---
        val cardModel = createCard()
        val rowModelHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cbModel = CheckBox(activity).apply {
            text = if (isUk) "🧠 AI-визначник грибів (model.zip)" else "🧠 AI Mushroom Classifier (model.zip)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvModelStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        rowModelHeader.addView(cbModel)
        rowModelHeader.addView(tvModelStatus)

        val tvModelSub = TextView(activity).apply {
            text = if (isUk) "Нейромережа для офлайн-розпізнавання грибів за фото з камери (~60 МБ)"
            else "Neural network for offline mushroom recognition by photo (~60 MB)"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(dp(30), 0, 0, dp(2))
        }
        cardModel.addView(rowModelHeader)
        cardModel.addView(tvModelSub)
        cardsContainer.addView(cardModel)

        // --- COMPONENT 5: App Version & Update Check ---
        val cardUpdate = createCard()
        val tvUpdateTitle = TextView(activity).apply {
            text = if (isUk) "🔄 Перевірка оновлень додатка" else "🔄 App Updates Check"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        val tvUpdateCurrent = TextView(activity).apply {
            val ver = activity.getAppVersionName()
            text = if (isUk) "Встановлена версія: v$ver" else "Installed version: v$ver"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(2))
        }
        val tvUpdateStatus = TextView(activity).apply {
            text = if (isUk) "⏳ Перевірка GitHub Releases..." else "⏳ Checking GitHub Releases..."
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(4))
        }
        var isDownloadingActive = false
        var updateDownloadUrl = ""
        var updateFileName = ""
        val btnAppUpdate = Button(activity).apply {
            text = if (isUk) "⬇️ Оновити додаток" else "⬇️ Update App"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D97706"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                setMargins(0, dp(6), 0, 0)
            }
            setOnClickListener {
                if (updateDownloadUrl.isNotEmpty()) {
                    isEnabled = false
                    text = if (isUk) "⏳ Завантаження APK..." else "⏳ Downloading APK..."
                    AppUpdateManager.startDownload(activity, updateDownloadUrl, updateFileName) { msg ->
                        activity.runOnUiThread {
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    AppUpdateManager.checkAndDownloadUpdate(activity, force = true)
                }
            }
        }
        cardUpdate.addView(tvUpdateTitle)
        cardUpdate.addView(tvUpdateCurrent)
        cardUpdate.addView(tvUpdateStatus)
        cardUpdate.addView(btnAppUpdate)
        cardsContainer.addView(cardUpdate)

        scrollView.addView(cardsContainer)
        root.addView(scrollView)

        var updateDownloadButtonState: (() -> Unit)? = null

        // --- REFRESH STATUSES HELPER ---
        fun refreshStatuses() {
            // 1. World Map
            val isWorldOk = MapDownloadManager.isWorldMapReady()
            val isWorldUpdate = MapDownloadManager.isWorldMapUpdateAvailable()
            if (!isWorldOk) {
                tvWorldStatus.text = if (isUk) "Не завантажено" else "Not downloaded"
                tvWorldStatus.setTextColor(Color.parseColor("#F59E0B"))
                cbWorld.isChecked = true
                cbWorld.isEnabled = true
                cbWorld.alpha = 1.0f
            } else if (isWorldUpdate) {
                tvWorldStatus.text = if (isUk) "Оновити" else "Update needed"
                tvWorldStatus.setTextColor(Color.parseColor("#F59E0B"))
                cbWorld.isChecked = true
                cbWorld.isEnabled = true
                cbWorld.alpha = 1.0f
            } else {
                tvWorldStatus.text = if (isUk) "✓ Встановлено" else "✓ Installed"
                tvWorldStatus.setTextColor(Color.parseColor("#10B981"))
                cbWorld.isChecked = false
                cbWorld.isEnabled = false
                cbWorld.alpha = 0.5f
            }

            // 2. Country
            val cName = if (isUk) selectedCountry.nameUk else selectedCountry.name
            tvCountryDetected.text = if (isUk) "Ваша країна: $cName (${selectedCountry.code})" else "Your country: $cName (${selectedCountry.code})"
            val cStatus = MapDownloadManager.getCountryStatus(selectedCountry)
            when (cStatus) {
                CountryStatus.READY -> {
                    tvCountryStatus.text = if (isUk) "✓ Встановлено" else "✓ Installed"
                    tvCountryStatus.setTextColor(Color.parseColor("#10B981"))
                    cbCountry.isChecked = false
                    cbCountry.isEnabled = false
                    cbCountry.alpha = 0.5f
                }
                CountryStatus.NEEDS_UPDATE -> {
                    tvCountryStatus.text = if (isUk) "Оновити" else "Update needed"
                    tvCountryStatus.setTextColor(Color.parseColor("#F59E0B"))
                    cbCountry.isChecked = true
                    cbCountry.isEnabled = true
                    cbCountry.alpha = 1.0f
                }
                CountryStatus.NOT_DOWNLOADED, CountryStatus.INCOMPLETE -> {
                    tvCountryStatus.text = if (isUk) "Не завантажено" else "Not downloaded"
                    tvCountryStatus.setTextColor(Color.parseColor("#F59E0B"))
                    cbCountry.isChecked = true
                    cbCountry.isEnabled = true
                    cbCountry.alpha = 1.0f
                }
            }

            // 3. Database
            val dbStatus = MushroomDatabaseManager.checkDatabaseStatus(activity)
            when (dbStatus) {
                MushroomDatabaseManager.DataStatus.READY -> {
                    tvDbStatus.text = if (isUk) "✓ Встановлено" else "✓ Installed"
                    tvDbStatus.setTextColor(Color.parseColor("#10B981"))
                    cbDb.isChecked = false
                    cbDb.isEnabled = false
                    cbDb.alpha = 0.5f
                }
                MushroomDatabaseManager.DataStatus.NEEDS_UPDATE -> {
                    tvDbStatus.text = if (isUk) "Оновити" else "Update needed"
                    tvDbStatus.setTextColor(Color.parseColor("#F59E0B"))
                    cbDb.isChecked = true
                    cbDb.isEnabled = true
                    cbDb.alpha = 1.0f
                }
                MushroomDatabaseManager.DataStatus.MISSING -> {
                    tvDbStatus.text = if (isUk) "Не завантажено" else "Not downloaded"
                    tvDbStatus.setTextColor(Color.parseColor("#F59E0B"))
                    cbDb.isChecked = true
                    cbDb.isEnabled = true
                    cbDb.alpha = 1.0f
                }
            }

            // 4. Model
            val modelStatus = MushroomClassifier.checkModelStatus(activity)
            when (modelStatus) {
                MushroomClassifier.ModelStatus.READY -> {
                    tvModelStatus.text = if (isUk) "✓ Встановлено" else "✓ Installed"
                    tvModelStatus.setTextColor(Color.parseColor("#10B981"))
                    cbModel.isChecked = false
                    cbModel.isEnabled = false
                    cbModel.alpha = 0.5f
                }
                MushroomClassifier.ModelStatus.NEEDS_UPDATE -> {
                    tvModelStatus.text = if (isUk) "Оновити" else "Update needed"
                    tvModelStatus.setTextColor(Color.parseColor("#F59E0B"))
                    cbModel.isChecked = true
                    cbModel.isEnabled = true
                    cbModel.alpha = 1.0f
                }
                MushroomClassifier.ModelStatus.MISSING -> {
                    tvModelStatus.text = if (isUk) "Не завантажено" else "Not downloaded"
                    tvModelStatus.setTextColor(Color.parseColor("#F59E0B"))
                    cbModel.isChecked = true
                    cbModel.isEnabled = true
                    cbModel.alpha = 1.0f
                }
            }
            updateDownloadButtonState?.invoke()
        }

        btnCountryChange.setOnClickListener {
            showCountryPicker(activity, selectedCountry) { picked ->
                selectedCountry = picked
                refreshStatuses()
                MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
                    if (hasUpdate) activity.runOnUiThread { refreshStatuses() }
                }
            }
        }

        // Async check update status from GitHub
        AppUpdateManager.checkUpdateStatusAsync(activity) { hasUpdate, latestName, _, downloadUrl ->
            activity.runOnUiThread {
                if (hasUpdate) {
                    updateDownloadUrl = downloadUrl
                    updateFileName = latestName
                    tvUpdateStatus.text = if (isUk) "⚠️ Доступна нова версія: $latestName" else "⚠️ New version available: $latestName"
                    tvUpdateStatus.setTextColor(Color.parseColor("#F59E0B"))
                    btnAppUpdate.text = if (isUk) "⬇️ Оновити ($latestName)" else "⬇️ Update ($latestName)"
                    btnAppUpdate.isEnabled = !isDownloadingActive
                    btnAppUpdate.setBackgroundColor(Color.parseColor(if (isDownloadingActive) "#374151" else "#D97706"))
                    btnAppUpdate.visibility = View.VISIBLE
                } else {
                    tvUpdateStatus.text = if (isUk) "✓ Встановлено найновішу версію" else "✓ Installed version is up-to-date"
                    tvUpdateStatus.setTextColor(Color.parseColor("#10B981"))
                    btnAppUpdate.visibility = View.GONE
                }
            }
        }

        // Async check country updates from Mapsforge
        MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
            if (hasUpdate) {
                activity.runOnUiThread {
                    refreshStatuses()
                }
            }
        }

        // Async check world map updates from Mapsforge
        MapDownloadManager.checkWorldMapUpdatesAsync { hasUpdate ->
            if (hasUpdate) {
                activity.runOnUiThread {
                    refreshStatuses()
                }
            }
        }

        // --- BOTTOM SECTION: DOWNLOAD BUTTON & 2 PROGRESS INDICATORS ---
        val downloadPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val btnDownloadSelected = Button(activity).apply {
            text = if (isUk) "⬇️ Завантажити обране" else "⬇️ Download Selected"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#059669"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
        }
        downloadPanel.addView(btnDownloadSelected)

        updateDownloadButtonState = {
            if (!isDownloadingActive) {
                val anyAvailable = cbWorld.isEnabled || cbCountry.isEnabled || cbDb.isEnabled || cbModel.isEnabled
                val anyChecked = cbWorld.isChecked || cbCountry.isChecked || cbDb.isChecked || cbModel.isChecked
                if (!anyAvailable) {
                    btnDownloadSelected.isEnabled = false
                    btnDownloadSelected.setBackgroundColor(Color.parseColor("#374151"))
                    btnDownloadSelected.text = if (isUk) "✓ Всі компоненти встановлено" else "✓ All components installed"
                } else {
                    btnDownloadSelected.isEnabled = anyChecked
                    btnDownloadSelected.setBackgroundColor(Color.parseColor(if (anyChecked) "#059669" else "#374151"))
                    btnDownloadSelected.text = if (isUk) "⬇️ Завантажити обране" else "⬇️ Download Selected"
                }
            }
        }

        cbWorld.setOnCheckedChangeListener { _, _ -> updateDownloadButtonState() }
        cbCountry.setOnCheckedChangeListener { _, _ -> updateDownloadButtonState() }
        cbDb.setOnCheckedChangeListener { _, _ -> updateDownloadButtonState() }
        cbModel.setOnCheckedChangeListener { _, _ -> updateDownloadButtonState() }

        refreshStatuses()

        // Progress container (hidden until download starts)
        val progressContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }

        val tvStatus = TextView(activity).apply {
            text = if (isUk) "Очікування..." else "Waiting..."
            setTextColor(Color.parseColor("#E5E7EB"))
            textSize = 12f
            setPadding(0, 0, 0, dp(3))
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        }
        progressContainer.addView(tvStatus)
        progressContainer.addView(progressBar)

        downloadPanel.addView(progressContainer)
        root.addView(downloadPanel)

        // --- FOOTER: CheckBox & Go to Map Button ---
        val footerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val cbDontShowAgain = CheckBox(activity).apply {
            text = if (isUk) "Не показувати при наступному запуску" else "Do not show on next launch"
            isChecked = false // За замовчуванням знята (як просив користувач)
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(4), 0, 0, dp(6))
        }
        footerLayout.addView(cbDontShowAgain)

        val btnProceed = Button(activity).apply {
            text = if (isUk) "Перейти до карти" else "Go to Map"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#2563EB"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
            setOnClickListener {
                if (cbDontShowAgain.isChecked) {
                    AppPrefs.setInitialSetupDismissed(activity, true)
                }
                OsmTileEngine.reloadMaps()
                PoiManager.refreshPoiFiles()
                dialog.dismiss()
                onFinished()
            }
        }
        footerLayout.addView(btnProceed)
        root.addView(footerLayout)

        dialog.setOnKeyListener { _, keyCode, _ ->
            keyCode == android.view.KeyEvent.KEYCODE_BACK && isDownloadingActive
        }

        // --- BATCH DOWNLOAD LOGIC ---
        btnDownloadSelected.setOnClickListener {
            val doWorld = cbWorld.isChecked
            val doCountry = cbCountry.isChecked
            val doDb = cbDb.isChecked
            val doModel = cbModel.isChecked

            if (!doWorld && !doCountry && !doDb && !doModel) {
                Toast.makeText(
                    activity,
                    if (isUk) "Будь ласка, оберіть хоча б один пункт" else "Please select at least one component",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            isDownloadingActive = true
            btnProceed.isEnabled = false
            btnProceed.setBackgroundColor(Color.parseColor("#374151"))
            btnAppUpdate.isEnabled = false
            btnAppUpdate.setBackgroundColor(Color.parseColor("#374151"))
            btnCountryChange.isEnabled = false
            cbWorld.isEnabled = false
            cbCountry.isEnabled = false
            cbDb.isEnabled = false
            cbModel.isEnabled = false

            // Ховаємо кнопку завантаження, показуємо прогрес-бар
            btnDownloadSelected.visibility = View.GONE
            progressContainer.visibility = View.VISIBLE

            // Розрахунок сумарного обсягу МБ для шкали прогрес-бару
            var totalExpectedBytes = 0L
            if (doWorld) totalExpectedBytes += 3_211_280L
            if (doCountry) {
                val segCount = selectedCountry.getRd5Segments().size
                totalExpectedBytes += 100_000_000L + (segCount * 2_000_000L)
            }
            if (doDb) totalExpectedBytes += MushroomDataConfig.DB_TOTAL_ARCHIVE_SIZE
            if (doModel) totalExpectedBytes += MushroomDataConfig.MODEL_TOTAL_ARCHIVE_SIZE

            val totalMb = (totalExpectedBytes / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)
            val maxScale = if (totalMb < 10) (totalMb * 10) else totalMb
            progressBar.max = maxScale
            progressBar.progress = 0
            tvStatus.text = if (isUk) "Підготовка до завантаження..." else "Preparing download..."

            data class StepProgress(
                val fileName: String,
                val bytesDownloaded: Long,
                val totalBytes: Long,
                val fileIndex: Int,
                val totalFiles: Int,
                val percent: Int
            )

            data class DownloadStep(
                val name: String,
                val execute: (
                    onProgress: (StepProgress) -> Unit,
                    onDone: (success: Boolean, errorMsg: String?) -> Unit
                ) -> Unit
            )

            val steps = mutableListOf<DownloadStep>()

            if (doWorld) {
                steps.add(DownloadStep("world.map") { onProg, onDone ->
                    MapDownloadManager.downloadWorldMap(
                        onProgress = { p ->
                            onProg(StepProgress(p.currentFileName, p.bytesDownloaded, p.totalBytes, 1, 1, p.percent))
                        },
                        onFinished = { success, msg ->
                            onDone(success, if (success) null else msg)
                        }
                    )
                })
            }

            if (doCountry) {
                val c = selectedCountry
                steps.add(DownloadStep(c.name) { onProg, onDone ->
                    MapDownloadManager.downloadCountry(
                        country = c,
                        onProgress = { p ->
                            onProg(StepProgress(p.currentFileName, p.bytesDownloaded, p.totalBytes, p.fileIndex, p.totalFiles, p.percent))
                        },
                        onFinished = { success, msg ->
                            onDone(success, if (success) null else msg)
                        }
                    )
                })
            }

            if (doDb) {
                steps.add(DownloadStep("mushrooms.db") { onProg, onDone ->
                    MushroomDatabaseManager.downloadDatabase(
                        context = activity,
                        forceDownload = true,
                        onProgress = { _, _ -> },
                        onDetailedProgress = { fileName, bytesRead, totalBytes, fileIdx, totalFiles, percent ->
                            onProg(StepProgress(fileName, bytesRead, totalBytes, fileIdx, totalFiles, percent))
                        },
                        onComplete = { success, errorMsg ->
                            onDone(success, errorMsg)
                        }
                    )
                })
            }

            if (doModel) {
                steps.add(DownloadStep("model.zip") { onProg, onDone ->
                    MushroomClassifier.downloadModel(
                        context = activity,
                        forceDownload = true,
                        onProgress = { _, _ -> },
                        onDetailedProgress = { fileName, bytesRead, totalBytes, fileIdx, totalFiles, percent ->
                            onProg(StepProgress(fileName, bytesRead, totalBytes, fileIdx, totalFiles, percent))
                        },
                        onComplete = { success, errorMsg ->
                            onDone(success, errorMsg)
                        }
                    )
                })
            }

            val totalSteps = steps.size
            var completedBaseBytes = 0L
            var lastFileName = ""
            var lastFileDownloadedBytes = 0L

            fun executeStep(index: Int) {
                if (index >= totalSteps) {
                    activity.runOnUiThread {
                        progressContainer.visibility = View.GONE
                        btnDownloadSelected.visibility = View.VISIBLE
                        isDownloadingActive = false
                        btnProceed.isEnabled = true
                        btnProceed.setBackgroundColor(Color.parseColor("#2563EB"))
                        btnAppUpdate.isEnabled = true
                        btnAppUpdate.setBackgroundColor(Color.parseColor("#D97706"))
                        btnCountryChange.isEnabled = true

                        OsmTileEngine.reloadMaps()
                        PoiManager.refreshPoiFiles()
                        refreshStatuses()

                        Toast.makeText(
                            activity,
                            if (isUk) "Усі вибрані файли успішно завантажено!" else "All selected files downloaded successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }

                val step = steps[index]

                step.execute(
                    { sp ->
                        activity.runOnUiThread {
                            val mbDone = sp.bytesDownloaded / (1024.0 * 1024.0)
                            val mbTotal = sp.totalBytes / (1024.0 * 1024.0)
                            val mbUnit = if (isUk) "МБ" else "MB"
                            tvStatus.text = String.format(
                                Locale.US,
                                "%s %.1f / %.1f %s [%d/%d] (%d%%)",
                                sp.fileName,
                                mbDone,
                                mbTotal,
                                mbUnit,
                                sp.fileIndex,
                                sp.totalFiles,
                                sp.percent
                            )

                            if (sp.fileName != lastFileName) {
                                completedBaseBytes += lastFileDownloadedBytes
                                lastFileName = sp.fileName
                                lastFileDownloadedBytes = sp.bytesDownloaded
                            } else {
                                lastFileDownloadedBytes = sp.bytesDownloaded
                            }

                            val currentTotalBytes = completedBaseBytes + sp.bytesDownloaded
                            val currentMb = currentTotalBytes / (1024.0 * 1024.0)
                            if (totalMb < 10) {
                                progressBar.progress = (currentMb * 10).toInt().coerceIn(0, maxScale)
                            } else {
                                progressBar.progress = currentMb.toInt().coerceIn(0, maxScale)
                            }
                        }
                    },
                    { success, errorMsg ->
                        activity.runOnUiThread {
                            completedBaseBytes += lastFileDownloadedBytes
                            lastFileName = ""
                            lastFileDownloadedBytes = 0L

                            if (!success) {
                                Toast.makeText(
                                    activity,
                                    "Error downloading ${step.name}: $errorMsg",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            executeStep(index + 1)
                        }
                    }
                )
            }

            executeStep(0)
        }

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.95).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.90).toInt()
        )
        dialog.show()
    }

    private fun showCountryPicker(activity: Activity, current: MapCountry, onPicked: (MapCountry) -> Unit) {
        val isUk = AppPrefs.isUk(activity)
        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val pickerDialog = Dialog(activity)
        pickerDialog.setTitle(if (isUk) "Виберіть країну" else "Select Country")

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#252525"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val searchEt = EditText(activity).apply {
            hint = if (isUk) "🔍 Пошук країни..." else "🔍 Search country..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
        }
        container.addView(searchEt)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, dp(8), 0, dp(8))
            }
        }
        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        container.addView(scroll)

        fun renderList(list: List<MapCountry>) {
            listContainer.removeAllViews()
            for (c in list) {
                val name = if (isUk) c.nameUk else c.name
                val btn = Button(activity).apply {
                    text = "$name (${c.code})"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (c.code == current.code) Color.parseColor("#065F46") else Color.parseColor("#3A3A3A"))
                    textSize = 13f
                    gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    p.setMargins(0, dp(2), 0, dp(2))
                    layoutParams = p
                    setOnClickListener {
                        onPicked(c)
                        pickerDialog.dismiss()
                    }
                }
                listContainer.addView(btn)
            }
        }

        renderList(MapDownloadManager.countries)

        searchEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                val filtered = if (q.isEmpty()) MapDownloadManager.countries else MapDownloadManager.countries.filter {
                    it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.nameUk.lowercase(Locale.getDefault()).contains(q) ||
                    it.code.lowercase(Locale.getDefault()).contains(q)
                }
                renderList(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val btnClose = Button(activity).apply {
            text = if (isUk) "Скасувати" else "Cancel"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setOnClickListener { pickerDialog.dismiss() }
        }
        container.addView(btnClose)

        pickerDialog.setContentView(container)
        pickerDialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.75).toInt()
        )
        pickerDialog.show()
    }
}
