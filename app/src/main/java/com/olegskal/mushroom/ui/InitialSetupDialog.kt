package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.map.MapDownloadManager
import com.olegskal.mushroom.map.OsmTileEngine
import com.olegskal.mushroom.map.PoiManager
import com.olegskal.mushroom.mushrooms.MushroomClassifier
import com.olegskal.mushroom.mushrooms.MushroomDataConfig
import com.olegskal.mushroom.mushrooms.MushroomDatabaseManager
import com.olegskal.mushroom.network.AppUpdateManager
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

object InitialSetupDialog {

    private const val TAG = "InitialSetupDialog"
    private const val USER_AGENT = "Mushroom/2.0 (Android; https://github.com/OlegSkalGit/mushroom)"

    enum class BlockType {
        MAPS,
        MUSHROOMS
    }

    enum class ItemStatus {
        MISSING, // "Відсутній"
        UPDATE,  // "Оновлення"
        DONE     // "Готово"
    }

    data class AppUpdateData(
        val hasUpdate: Boolean,
        val latestVerName: String,
        val downloadUrl: String,
        val apkFile: File,
        val assetSize: Long
    )

    data class UpdateItem(
        val block: BlockType,
        val subCategoryUk: String,
        val subCategoryEn: String,
        val title: String,
        var status: ItemStatus,
        val expectedBytes: Long,
        val downloadAction: (onBytes: (Long) -> Unit, isCancelled: () -> Boolean) -> Boolean
    )

    private val bgExecutor = Executors.newCachedThreadPool()

    /**
     * Показує стартовий екран кожен раз при старті додатку,
     * якщо є хоча б один компонент для завантаження або оновлення.
     */
    fun showIfNeeded(activity: Activity, onFinished: () -> Unit = {}) {
        if (activity.isFinishing) return

        val detectedCountry = MapDownloadManager.detectCurrentCountry(activity)

        // 1. Швидка локальна перевірка наявності базових файлів
        val hasMissingLocalFiles = checkHasMissingLocalFiles(activity, detectedCountry)
        if (hasMissingLocalFiles) {
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    show(activity, detectedCountry, onFinished)
                }
            }
            return
        }

        // 2. Якщо локально все є — асинхронно перевіряємо наявність оновлень у мережі
        val dialogShown = AtomicBoolean(false)
        fun triggerShow() {
            if (dialogShown.compareAndSet(false, true)) {
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        show(activity, detectedCountry, onFinished)
                    }
                }
            }
        }

        AppUpdateManager.checkUpdateStatusAsync(activity) { hasAppUpdate, _, _, _ ->
            if (hasAppUpdate) triggerShow()
        }

        MapDownloadManager.checkCountryUpdatesAsync(detectedCountry) { hasCountryUpdate ->
            if (hasCountryUpdate) triggerShow()
        }

        MapDownloadManager.checkWorldMapUpdatesAsync { hasWorldUpdate ->
            if (hasWorldUpdate) triggerShow()
        }
    }

    private fun checkHasMissingLocalFiles(activity: Activity, country: MapCountry): Boolean {
        // Світ
        val worldFile = File(MushroomStorageManager.mapsDir, MapDownloadManager.WORLD_MAP_FILE_NAME)
        if (!worldFile.exists() || worldFile.length() < 1024L) return true

        // Карта країни
        val mapFile = File(MushroomStorageManager.mapsDir, country.mapFileName)
        if (!mapFile.exists() || mapFile.length() < 1024L) return true

        // Локації POI
        val poiFile = File(MushroomStorageManager.poiDir, country.poiFileName)
        if (!poiFile.exists() || poiFile.length() < 1024L) return true

        // Сегменти роутингу rd5
        for (seg in country.getRd5Segments()) {
            val segFile = File(MushroomStorageManager.navigationDir, seg)
            if (!segFile.exists() || segFile.length() < 1024L) return true
        }

        // База даних
        val dbReady = MushroomDatabaseManager.checkDatabaseStatus(activity) == MushroomDatabaseManager.DataStatus.READY
        if (!dbReady) return true

        // Модель
        val modelReady = MushroomClassifier.checkModelStatus(activity) == MushroomClassifier.ModelStatus.READY
        if (!modelReady) return true

        return false
    }

    fun show(
        activity: Activity,
        initialCountry: MapCountry = MapDownloadManager.detectCurrentCountry(activity),
        onFinished: () -> Unit = {}
    ) {
        val isUk = AppPrefs.isUk(activity)
        val dialog = Dialog(activity)
        val dialogTitle = if (isUk) "Оновлення додатку" else "App Updates"
        dialog.setTitle(dialogTitle)
        dialog.setCanceledOnTouchOutside(false)

        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        var selectedCountry = initialCountry
        var isDownloadingActive = false
        val cancelFlag = AtomicBoolean(false)

        dialog.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                isDownloadingActive // Блокуємо назад при завантаженні
            } else false
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B221B"))
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }

        // 1. Заголовок
        val titleTv = TextView(activity).apply {
            text = "🚀 $dialogTitle"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subtitleTv = TextView(activity).apply {
            text = if (isUk) "Доступні файли та оновлення для офлайн-роботи:"
            else "Available files and updates for offline operation:"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(subtitleTv)

        // 2. Блок вибору країни
        val countryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val tvCountry = TextView(activity).apply {
            val cName = if (isUk) selectedCountry.nameUk else selectedCountry.name
            text = if (isUk) "Країна: $cName (${selectedCountry.code})" else "Country: $cName (${selectedCountry.code})"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnCountryChange = Button(activity).apply {
            text = if (isUk) "🌐 Інша країна" else "🌐 Other country"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#374151"))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        }
        countryRow.addView(tvCountry)
        countryRow.addView(btnCountryChange)
        root.addView(countryRow)

        // 3. Контейнер списку оновлень (Scrollable)
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val cardsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(cardsContainer)
        root.addView(scrollView)

        // 4. Панель керування: Кнопки в рядок 50% / 50%
        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val btnDownload = Button(activity).apply {
            text = if (isUk) "Завантажити" else "Download"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#059669"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }

        val btnLater = Button(activity).apply {
            text = if (isUk) "Пізніше" else "Later"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnClickListener {
                OsmTileEngine.reloadMaps()
                PoiManager.refreshPoiFiles()
                dialog.dismiss()
                onFinished()
            }
        }
        buttonRow.addView(btnDownload)
        buttonRow.addView(btnLater)
        root.addView(buttonRow)

        // 5. Прогрес-бар та час (приховані до натискання «Завантажити»)
        val progressContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(4))
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        }
        val tvTimeRemaining = TextView(activity).apply {
            text = if (isUk) "Обчислення часу..." else "Estimating time..."
            setTextColor(Color.parseColor("#E5E7EB"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        progressContainer.addView(progressBar)
        progressContainer.addView(tvTimeRemaining)
        root.addView(progressContainer)

        var currentItems = mutableListOf<UpdateItem>()
        var appUpdateData: AppUpdateData? = null
        var countryNeedsUpdate = false
        var worldNeedsUpdate = false

        // Сканування доступних для оновлення файлів
        fun scanItems(): List<UpdateItem> {
            val list = mutableListOf<UpdateItem>()

            // --- БЛОК 1: КАРТИ ---
            // 1. Світ
            val worldFile = File(MushroomStorageManager.mapsDir, MapDownloadManager.WORLD_MAP_FILE_NAME)
            if (!worldFile.exists() || worldFile.length() < 1024L) {
                list.add(
                    UpdateItem(
                        BlockType.MAPS,
                        "Світ", "World",
                        MapDownloadManager.WORLD_MAP_FILE_NAME,
                        ItemStatus.MISSING,
                        3_211_280L
                    ) { onBytes, isCancel ->
                        downloadFileDirect("https://download.mapsforge.org/maps/v5/world/world.map", worldFile, onBytes, isCancel)
                    }
                )
            } else if (worldNeedsUpdate || MapDownloadManager.isWorldMapUpdateAvailable()) {
                list.add(
                    UpdateItem(
                        BlockType.MAPS,
                        "Світ", "World",
                        MapDownloadManager.WORLD_MAP_FILE_NAME,
                        ItemStatus.UPDATE,
                        3_211_280L
                    ) { onBytes, isCancel ->
                        downloadFileDirect("https://download.mapsforge.org/maps/v5/world/world.map", worldFile, onBytes, isCancel)
                    }
                )
            }

            // 2. Країна
            val c = selectedCountry
            val mapFile = File(MushroomStorageManager.mapsDir, c.mapFileName)
            if (!mapFile.exists() || mapFile.length() < 1024L) {
                list.add(
                    UpdateItem(
                        BlockType.MAPS,
                        "Країна", "Country",
                        "${if (isUk) c.nameUk else c.name} (${c.mapFileName})",
                        ItemStatus.MISSING,
                        100_000_000L
                    ) { onBytes, isCancel ->
                        downloadFileDirect(c.mapUrl, mapFile, onBytes, isCancel)
                    }
                )
            } else if (countryNeedsUpdate) {
                list.add(
                    UpdateItem(
                        BlockType.MAPS,
                        "Країна", "Country",
                        "${if (isUk) c.nameUk else c.name} (${c.mapFileName})",
                        ItemStatus.UPDATE,
                        100_000_000L
                    ) { onBytes, isCancel ->
                        downloadFileDirect(c.mapUrl, mapFile, onBytes, isCancel)
                    }
                )
            }

            // 3. Навігація (файли) rd5
            for (seg in c.getRd5Segments()) {
                val segFile = File(MushroomStorageManager.navigationDir, seg)
                if (!segFile.exists() || segFile.length() < 1024L) {
                    list.add(
                        UpdateItem(
                            BlockType.MAPS,
                            "Навігація (файли)", "Navigation (files)",
                            seg,
                            ItemStatus.MISSING,
                            2_000_000L
                        ) { onBytes, isCancel ->
                            downloadFileDirect("https://brouter.de/brouter/segments4/$seg", segFile, onBytes, isCancel, allowHttpErrors = true)
                        }
                    )
                }
            }

            // 4. Локації POI
            val poiFile = File(MushroomStorageManager.poiDir, c.poiFileName)
            if (!poiFile.exists() || poiFile.length() < 1024L) {
                list.add(
                    UpdateItem(
                        BlockType.MAPS,
                        "Локації", "Locations",
                        c.poiFileName,
                        ItemStatus.MISSING,
                        10_000_000L
                    ) { onBytes, isCancel ->
                        downloadFileDirect(c.poiUrl, poiFile, onBytes, isCancel)
                    }
                )
            } else if (countryNeedsUpdate) {
                list.add(
                    UpdateItem(
                        BlockType.MAPS,
                        "Локації", "Locations",
                        c.poiFileName,
                        ItemStatus.UPDATE,
                        10_000_000L
                    ) { onBytes, isCancel ->
                        downloadFileDirect(c.poiUrl, poiFile, onBytes, isCancel)
                    }
                )
            }

            // --- БЛОК 2: ГРИБИ ---
            // 1. Енциклопедія - архіви (mushrooms.db / DB_PARTS)
            val dbDir = MushroomDatabaseManager.getDatabaseDirectory(activity)
            val finalDb = File(dbDir, MushroomDataConfig.DB_FILE_NAME)
            val isDbReady = finalDb.exists() && finalDb.length() == MushroomDataConfig.DB_FULL_SIZE
            if (!isDbReady) {
                for (part in MushroomDataConfig.DB_PARTS) {
                    val partFile = File(dbDir, part.fileName)
                    if (!partFile.exists() || partFile.length() != part.exactSize) {
                        val st = if (finalDb.exists()) ItemStatus.UPDATE else ItemStatus.MISSING
                        list.add(
                            UpdateItem(
                                BlockType.MUSHROOMS,
                                "Енциклопедія - архіви", "Encyclopedia - archives",
                                part.fileName,
                                st,
                                part.exactSize
                            ) { onBytes, isCancel ->
                                downloadPartWithResume("${MushroomDataConfig.DB_BASE_DOWNLOAD_URL}${part.fileName}", partFile, part.exactSize, onBytes, isCancel)
                            }
                        )
                    }
                }
            }

            // 2. Визначник - архіви (model.zip / MODEL_PARTS)
            val modelReady = MushroomClassifier.checkModelStatus(activity) == MushroomClassifier.ModelStatus.READY
            if (!modelReady) {
                val modelDir = MushroomClassifier.getModelDirectory()
                for (part in MushroomDataConfig.MODEL_PARTS) {
                    val partFile = File(modelDir, part.fileName)
                    if (!partFile.exists() || partFile.length() != part.exactSize) {
                        val st = if (MushroomClassifier.checkModelStatus(activity) == MushroomClassifier.ModelStatus.NEEDS_UPDATE) ItemStatus.UPDATE else ItemStatus.MISSING
                        list.add(
                            UpdateItem(
                                BlockType.MUSHROOMS,
                                "Визначник - архіви", "Classifier - archives",
                                part.fileName,
                                st,
                                part.exactSize
                            ) { onBytes, isCancel ->
                                downloadPartWithResume("${MushroomDataConfig.MODEL_BASE_DOWNLOAD_URL}${part.fileName}", partFile, part.exactSize, onBytes, isCancel)
                            }
                        )
                    }
                }
            }

            // 3. Додаток (новий APK з GitHub)
            val upd = appUpdateData
            if (upd != null && upd.hasUpdate) {
                list.add(
                    UpdateItem(
                        BlockType.MUSHROOMS,
                        "Додаток", "App",
                        "Mushroom (${upd.latestVerName})",
                        ItemStatus.UPDATE,
                        upd.assetSize
                    ) { onBytes, isCancel ->
                        AppUpdateManager.downloadApkDirect(upd.downloadUrl, upd.apkFile, onBytes, isCancel)
                    }
                )
            }

            return list
        }

        val badgeViews = mutableMapOf<UpdateItem, TextView>()

        fun updateItemBadge(item: UpdateItem) {
            activity.runOnUiThread {
                val badge = badgeViews[item] ?: return@runOnUiThread
                val bg = (badge.background as? GradientDrawable) ?: GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    badge.background = this
                }
                when (item.status) {
                    ItemStatus.DONE -> {
                        badge.text = if (isUk) "Готово" else "Done"
                        bg.setColor(Color.parseColor("#10B981"))
                    }
                    ItemStatus.MISSING -> {
                        badge.text = if (isUk) "Відсутній" else "Missing"
                        bg.setColor(Color.parseColor("#EF4444"))
                    }
                    ItemStatus.UPDATE -> {
                        badge.text = if (isUk) "Оновлення" else "Update"
                        bg.setColor(Color.parseColor("#0284C7"))
                    }
                }
            }
        }

        // Рендеринг карток списку
        fun renderItemsList() {
            cardsContainer.removeAllViews()
            badgeViews.clear()

            if (currentItems.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = if (isUk) "✓ Всі компоненти встановлені та актуальні" else "✓ All components are installed and up to date"
                    setTextColor(Color.parseColor("#10B981"))
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(12), dp(20), dp(12), dp(20))
                    gravity = Gravity.CENTER
                }
                cardsContainer.addView(emptyTv)
                btnDownload.isEnabled = false
                btnDownload.setBackgroundColor(Color.parseColor("#374151"))
                btnLater.text = if (isUk) "Закрити" else "Close"
                return
            }

            btnDownload.isEnabled = true
            btnDownload.setBackgroundColor(Color.parseColor("#059669"))
            btnLater.text = if (isUk) "Пізніше" else "Later"

            fun createBlockCard(headerTitle: String): Pair<LinearLayout, LinearLayout> {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#263226"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#374737"))
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    p.setMargins(0, 0, 0, dp(10))
                    layoutParams = p
                }
                val tvHead = TextView(activity).apply {
                    text = headerTitle
                    setTextColor(Color.WHITE)
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(6))
                }
                val itemsLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                card.addView(tvHead)
                card.addView(itemsLayout)
                return Pair(card, itemsLayout)
            }

            fun addItemRow(container: LinearLayout, item: UpdateItem) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                val subCat = if (isUk) item.subCategoryUk else item.subCategoryEn
                val rowTitleTv = TextView(activity).apply {
                    text = "$subCat: ${item.title}"
                    setTextColor(Color.parseColor("#E5E7EB"))
                    textSize = 12.5f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val badgeTv = TextView(activity).apply {
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    val bg = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                    }
                    when (item.status) {
                        ItemStatus.DONE -> {
                            text = if (isUk) "Готово" else "Done"
                            bg.setColor(Color.parseColor("#10B981"))
                        }
                        ItemStatus.MISSING -> {
                            text = if (isUk) "Відсутній" else "Missing"
                            bg.setColor(Color.parseColor("#EF4444"))
                        }
                        ItemStatus.UPDATE -> {
                            text = if (isUk) "Оновлення" else "Update"
                            bg.setColor(Color.parseColor("#0284C7"))
                        }
                    }
                    background = bg
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                badgeViews[item] = badgeTv
                row.addView(rowTitleTv)
                row.addView(badgeTv)
                container.addView(row)
            }

            val mapItems = currentItems.filter { it.block == BlockType.MAPS }
            if (mapItems.isNotEmpty()) {
                val (card, container) = createBlockCard(if (isUk) "🗺️ Карти" else "🗺️ Maps")
                for (it in mapItems) addItemRow(container, it)
                cardsContainer.addView(card)
            }

            val mushroomItems = currentItems.filter { it.block == BlockType.MUSHROOMS }
            if (mushroomItems.isNotEmpty()) {
                val (card, container) = createBlockCard(if (isUk) "🍄 Гриби" else "🍄 Mushrooms")
                for (it in mushroomItems) addItemRow(container, it)
                cardsContainer.addView(card)
            }
        }

        fun refreshList() {
            currentItems = scanItems().toMutableList()
            renderItemsList()
        }

        refreshList()

        // Вибір іншої країни
        btnCountryChange.setOnClickListener {
            showCountryPicker(activity, selectedCountry) { picked ->
                selectedCountry = picked
                val cName = if (isUk) selectedCountry.nameUk else selectedCountry.name
                tvCountry.text = if (isUk) "Країна: $cName (${selectedCountry.code})" else "Country: $cName (${selectedCountry.code})"
                countryNeedsUpdate = false
                refreshList()
                MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
                    if (hasUpdate) {
                        countryNeedsUpdate = true
                        activity.runOnUiThread { refreshList() }
                    }
                }
            }
        }

        // Фонова перевірка оновлень з GitHub
        AppUpdateManager.checkUpdateStatusAsync(activity) { hasUpdate, latestName, _, downloadUrl ->
            if (hasUpdate) {
                val dlDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                val apkFile = File(dlDir, "$latestName.apk")
                appUpdateData = AppUpdateData(true, latestName, downloadUrl, apkFile, 25_000_000L)
                activity.runOnUiThread { refreshList() }
            }
        }

        // Фонова перевірка карти країни
        MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
            if (hasUpdate) {
                countryNeedsUpdate = true
                activity.runOnUiThread { refreshList() }
            }
        }

        // Фонова перевірка світової карти
        MapDownloadManager.checkWorldMapUpdatesAsync { hasUpdate ->
            if (hasUpdate) {
                worldNeedsUpdate = true
                activity.runOnUiThread { refreshList() }
            }
        }

        // --- ДІЯ: ЗАВАНТАЖЕННЯ ---
        btnDownload.setOnClickListener {
            if (currentItems.isEmpty()) return@setOnClickListener

            isDownloadingActive = true
            buttonRow.visibility = View.GONE
            btnCountryChange.visibility = View.GONE
            progressContainer.visibility = View.VISIBLE

            val totalExpectedBytes = currentItems.sumOf { it.expectedBytes }.coerceAtLeast(1L)
            var totalDownloadedBytes = 0L
            val startTimeMs = System.currentTimeMillis()
            var lastUiUpdateMs = 0L

            fun updateProgressUi() {
                val now = System.currentTimeMillis()
                if (now - lastUiUpdateMs < 400 && totalDownloadedBytes < totalExpectedBytes) return
                lastUiUpdateMs = now

                val elapsedSec = (now - startTimeMs) / 1000.0
                val progressRatio = (totalDownloadedBytes.toDouble() / totalExpectedBytes.toDouble()).coerceIn(0.0, 1.0)
                val currentProgressVal = (progressRatio * 1000).toInt()

                val timeStr: String
                if (elapsedSec > 1.2 && totalDownloadedBytes > 60_000L) {
                    val speed = totalDownloadedBytes / elapsedSec
                    val remainingBytes = (totalExpectedBytes - totalDownloadedBytes).coerceAtLeast(0L)
                    val remainingSec = if (speed > 1024) (remainingBytes / speed).roundToLong() else -1L

                    timeStr = if (remainingSec < 0) {
                        if (isUk) "Обчислення часу..." else "Estimating time..."
                    } else if (remainingSec >= 3600) {
                        val h = remainingSec / 3600
                        val m = (remainingSec % 3600) / 60
                        if (isUk) "Залишилось: ~$h год $m хв" else "Remaining: ~$h h $m min"
                    } else if (remainingSec >= 60) {
                        val m = remainingSec / 60
                        val s = remainingSec % 60
                        if (isUk) "Залишилось: ~$m хв $s с" else "Remaining: ~$m min $s sec"
                    } else {
                        if (isUk) "Залишилось: ~$remainingSec с" else "Remaining: ~$remainingSec sec"
                    }
                } else {
                    timeStr = if (isUk) "Обчислення часу..." else "Estimating time..."
                }

                activity.runOnUiThread {
                    progressBar.progress = currentProgressVal
                    tvTimeRemaining.text = timeStr
                }
            }

            bgExecutor.execute {
                // Розбиваємо чергу: ресурси (карти, бази, моделі) першими, додаток (APK) — у самому кінці
                val resourceItems = currentItems.filter { it.subCategoryUk != "Додаток" }
                val apkItem = currentItems.find { it.subCategoryUk == "Додаток" }

                var hasFailures = false

                for (item in resourceItems) {
                    if (cancelFlag.get()) break
                    val ok = item.downloadAction(
                        { bytesRead ->
                            totalDownloadedBytes += bytesRead
                            updateProgressUi()
                        },
                        { cancelFlag.get() }
                    )
                    if (ok) {
                        item.status = ItemStatus.DONE
                        updateItemBadge(item)
                    } else {
                        AppLogger.log(TAG, "download", false, "Failed downloading ${item.title}")
                        if (item.block != BlockType.MAPS || !item.title.endsWith(".rd5")) {
                            hasFailures = true
                        }
                    }
                }

                // Розпакування бази даних якщо всі архіви на місці
                val dbDir = MushroomDatabaseManager.getDatabaseDirectory(activity)
                val allDbParts = MushroomDataConfig.DB_PARTS.map { File(dbDir, it.fileName) }
                val allDbExist = allDbParts.all { it.exists() && it.length() > 0 }
                if (allDbExist && !MushroomDatabaseManager.isDatabaseUpToDate(activity)) {
                    activity.runOnUiThread {
                        tvTimeRemaining.text = if (isUk) "Розпакування бази даних..." else "Unpacking database..."
                    }
                    MushroomDatabaseManager.unpackDownloadedParts(activity, allDbParts)
                }

                // Розпакування моделі якщо всі архіви на місці
                val modelDir = MushroomClassifier.getModelDirectory()
                val allModelParts = MushroomDataConfig.MODEL_PARTS.map { File(modelDir, it.fileName) }
                val allModelExist = allModelParts.all { it.exists() && it.length() > 0 }
                if (allModelExist && !MushroomClassifier.isModelDownloaded(activity)) {
                    activity.runOnUiThread {
                        tvTimeRemaining.text = if (isUk) "Розпакування нейромережі..." else "Unpacking model..."
                    }
                    MushroomClassifier.unpackDownloadedParts(activity, allModelParts)
                }

                // Якщо є оновлення додатку — запускаємо його в самому кінці
                if (apkItem != null && !cancelFlag.get()) {
                    val okApk = apkItem.downloadAction(
                        { bytesRead ->
                            totalDownloadedBytes += bytesRead
                            updateProgressUi()
                        },
                        { cancelFlag.get() }
                    )
                    if (okApk) {
                        apkItem.status = ItemStatus.DONE
                        updateItemBadge(apkItem)
                    }
                    val apkFile = appUpdateData?.apkFile
                    if (apkFile != null && apkFile.exists()) {
                        AppUpdateManager.installApk(activity, apkFile)
                    }
                }

                try { Thread.sleep(600L) } catch (_: Exception) {}

                activity.runOnUiThread {
                    progressBar.progress = 1000
                    tvTimeRemaining.text = if (isUk) "Готово!" else "Done!"
                    isDownloadingActive = false

                    if (hasFailures) {
                        Toast.makeText(
                            activity,
                            if (isUk) "Деякі файли не вдалося завантажити" else "Some files failed to download",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    OsmTileEngine.reloadMaps()
                    PoiManager.refreshPoiFiles()

                    dialog.dismiss()
                    onFinished()
                }
            }
        }

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.95).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.90).toInt()
        )
        dialog.show()
    }

    private fun downloadFileDirect(
        urlStr: String,
        destFile: File,
        onBytesRead: (Long) -> Unit,
        isCancelled: () -> Boolean,
        allowHttpErrors: Boolean = false
    ): Boolean {
        var conn: HttpURLConnection? = null
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.${System.currentTimeMillis()}.tmp")

        return try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc != null) {
                        currentUrl = loc
                        redirects++
                        continue
                    }
                }
                break
            }

            if (conn == null || conn.responseCode != HttpURLConnection.HTTP_OK) {
                if (allowHttpErrors) return true
                return false
            }

            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { out ->
                    val buffer = ByteArray(16384)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        if (isCancelled()) {
                            tmpFile.delete()
                            return false
                        }
                        out.write(buffer, 0, len)
                        onBytesRead(len.toLong())
                    }
                    out.flush()
                }
            }

            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (destFile.exists()) destFile.delete()
                val ok = tmpFile.renameTo(destFile)
                if (!ok) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                val lastMod = conn.lastModified
                if (lastMod > 0L) {
                    try { destFile.setLastModified(lastMod) } catch (_: Exception) {}
                }
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            tmpFile.delete()
            if (allowHttpErrors) true else false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun downloadPartWithResume(
        urlStr: String,
        destFile: File,
        expectedSize: Long,
        onBytesRead: (Long) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        if (destFile.exists() && destFile.length() == expectedSize) {
            return true
        }
        if (destFile.exists() && destFile.length() > expectedSize) {
            destFile.delete()
        }

        var attempts = 0
        while (attempts < 3) {
            attempts++
            if (isCancelled()) return false
            var conn: HttpURLConnection? = null
            try {
                val existingLen = if (destFile.exists()) destFile.length() else 0L
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "MushroomApp/1.0 (Android)")
                    if (existingLen > 0) {
                        setRequestProperty("Range", "bytes=$existingLen-")
                    }
                }
                conn.connect()
                val code = conn.responseCode
                if (code == 416) {
                    destFile.delete()
                    conn.disconnect()
                    continue
                }
                val isResume = (code == 206)
                if (code != 200 && code != 206) {
                    conn.disconnect()
                    continue
                }
                val append = isResume && existingLen > 0
                conn.inputStream.use { input ->
                    FileOutputStream(destFile, append).use { out ->
                        val buffer = ByteArray(32768)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            if (isCancelled()) return false
                            out.write(buffer, 0, len)
                            onBytesRead(len.toLong())
                        }
                        out.flush()
                    }
                }
                if (expectedSize <= 0L || destFile.length() == expectedSize) {
                    return true
                }
            } catch (_: Exception) {
                // повторна спроба
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return destFile.exists() && (expectedSize <= 0L || destFile.length() == expectedSize)
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
