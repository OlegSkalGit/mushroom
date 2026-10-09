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
        MISSING,     // "Відсутній"
        UPDATE,      // "Оновлення"
        DOWNLOADING, // "Завантаження"
        DONE         // "Готово"
    }

    data class AppUpdateData(
        val hasUpdate: Boolean,
        val latestVerName: String,
        val downloadUrl: String,
        val apkFile: File,
        val assetSize: Long
    )

    data class UpdateItem(
        val id: String,
        val block: BlockType,
        val subCategoryUk: String,
        val subCategoryEn: String,
        val title: String,
        var status: ItemStatus,
        var expectedBytes: Long,
        val downloadAction: (onBytes: (Long) -> Unit, onSizeDiscovered: (Long) -> Unit, isCancelled: () -> Boolean) -> Boolean
    ) {
        override fun equals(other: Any?): Boolean = other is UpdateItem && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    private val bgExecutor = Executors.newCachedThreadPool()

    /**
     * Показує стартовий екран кожен раз при старті додатку,
     * якщо є хоча б один компонент для завантаження або оновлення.
     */
    fun showIfNeeded(activity: Activity, onFinished: () -> Unit = {}) {
        if (activity.isFinishing) return

        // 1. Спочатку перевіряємо наявність оновлення додатку в кеші
        val cachedUpd = AppUpdateManager.getCachedAppUpdate(activity)
        if (cachedUpd != null && cachedUpd.hasUpdate) {
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    show(activity, MapDownloadManager.detectCurrentCountry(activity), onFinished)
                }
            }
            return
        }

        val detectedCountry = MapDownloadManager.detectCurrentCountry(activity)

        // 2. Локальна перевірка наявності базових файлів (якщо оновлення додатку відсутнє)
        val hasMissingLocalFiles = checkHasMissingLocalFiles(activity, detectedCountry)
        if (hasMissingLocalFiles) {
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    show(activity, detectedCountry, onFinished)
                }
            }
            return
        }

        // 3. Асинхронно перевіряємо наявність оновлень у мережі
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

        AppUpdateManager.checkUpdateStatusAsync(activity) { hasAppUpdate, _, _, _, _ ->
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
        val cachedAtStart = AppUpdateManager.getCachedAppUpdate(activity)
        var appUpdateData: AppUpdateData? = if (cachedAtStart != null && cachedAtStart.hasUpdate) {
            val dlDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
            val apkFile = File(dlDir, "${cachedAtStart.latestVerName}.apk")
            AppUpdateData(true, cachedAtStart.latestVerName, cachedAtStart.downloadUrl, apkFile, cachedAtStart.assetSize)
        } else null
        var countryMapNeedsUpdate = false
        var countryPoiNeedsUpdate = false
        var worldNeedsUpdate = false

        // Сканування доступних для оновлення файлів
        fun scanItems(): List<UpdateItem> {
            val list = mutableListOf<UpdateItem>()

            // ПРІОРИТЕТ 1: ОНОВЛЕННЯ ДОДАТКУ
            // Якщо є оновлення додатку — оновлюємо спочатку ТІЛЬКИ додаток!
            val upd = appUpdateData ?: run {
                val cached = AppUpdateManager.getCachedAppUpdate(activity)
                if (cached != null && cached.hasUpdate) {
                    val dlDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                    val apkFile = File(dlDir, "${cached.latestVerName}.apk")
                    AppUpdateData(true, cached.latestVerName, cached.downloadUrl, apkFile, cached.assetSize)
                } else null
            }

            if (upd != null && upd.hasUpdate) {
                list.add(
                    UpdateItem(
                        "app_apk",
                        BlockType.MUSHROOMS,
                        "Додаток", "App",
                        "Mushroom (${upd.latestVerName})",
                        ItemStatus.UPDATE,
                        upd.assetSize
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        AppUpdateManager.downloadApkDirect(upd.downloadUrl, upd.apkFile, onBytes, onSizeDiscovered, isCancel)
                    }
                )
                return list // Інші компоненти НЕ показуються, поки додаток не оновлено
            }

            // --- БЛОК 1: КАРТИ ---
            // 1. Світ
            val worldFile = File(MushroomStorageManager.mapsDir, MapDownloadManager.WORLD_MAP_FILE_NAME)
            if (!worldFile.exists() || worldFile.length() < 1024L) {
                list.add(
                    UpdateItem(
                        "map_world",
                        BlockType.MAPS,
                        "Світ", "World",
                        MapDownloadManager.WORLD_MAP_FILE_NAME,
                        ItemStatus.MISSING,
                        3_211_280L
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        downloadFileDirect("https://download.mapsforge.org/maps/v5/world/world.map", worldFile, onBytes, onSizeDiscovered, isCancel)
                    }
                )
            } else if (worldNeedsUpdate || MapDownloadManager.isWorldMapUpdateAvailable()) {
                list.add(
                    UpdateItem(
                        "map_world",
                        BlockType.MAPS,
                        "Світ", "World",
                        MapDownloadManager.WORLD_MAP_FILE_NAME,
                        ItemStatus.UPDATE,
                        3_211_280L
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        downloadFileDirect("https://download.mapsforge.org/maps/v5/world/world.map", worldFile, onBytes, onSizeDiscovered, isCancel)
                    }
                )
            }

            // 2. Країна
            val c = selectedCountry
            val mapFile = File(MushroomStorageManager.mapsDir, c.mapFileName)
            val estimatedMapSize = MapDownloadManager.getRemoteFileSize(c.mapFileName)
                ?: MapDownloadManager.getRemoteFileSize(c.mapUrl)
                ?: (if (c.code == "UA") 874_000_000L else 600_000_000L)

            if (!mapFile.exists() || mapFile.length() < 1024L) {
                list.add(
                    UpdateItem(
                        "map_${c.code}",
                        BlockType.MAPS,
                        "Країна", "Country",
                        "${if (isUk) c.nameUk else c.name} (${c.mapFileName})",
                        ItemStatus.MISSING,
                        estimatedMapSize
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        downloadFileDirect(c.mapUrl, mapFile, onBytes, onSizeDiscovered, isCancel)
                    }
                )
            } else if (countryMapNeedsUpdate || MapDownloadManager.isCountryMapUpdateAvailable(c)) {
                list.add(
                    UpdateItem(
                        "map_${c.code}",
                        BlockType.MAPS,
                        "Країна", "Country",
                        "${if (isUk) c.nameUk else c.name} (${c.mapFileName})",
                        ItemStatus.UPDATE,
                        estimatedMapSize
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        downloadFileDirect(c.mapUrl, mapFile, onBytes, onSizeDiscovered, isCancel)
                    }
                )
            }

            // 3. Навігація (файли) rd5
            for (seg in c.getRd5Segments()) {
                val segFile = File(MushroomStorageManager.navigationDir, seg)
                if (!segFile.exists() || segFile.length() < 1024L) {
                    list.add(
                        UpdateItem(
                            "rd5_$seg",
                            BlockType.MAPS,
                            "Навігація (файли)", "Navigation (files)",
                            seg,
                            ItemStatus.MISSING,
                            18_000_000L
                        ) { onBytes, onSizeDiscovered, isCancel ->
                            downloadFileDirect("https://brouter.de/brouter/segments4/$seg", segFile, onBytes, onSizeDiscovered, isCancel, allowHttpErrors = true)
                        }
                    )
                }
            }

            // 4. Локації POI
            val poiFile = File(MushroomStorageManager.poiDir, c.poiFileName)
            val estimatedPoiSize = MapDownloadManager.getRemoteFileSize(c.poiFileName)
                ?: MapDownloadManager.getRemoteFileSize(c.poiUrl)
                ?: (if (c.code == "UA") 175_000_000L else 100_000_000L)

            if (!poiFile.exists() || poiFile.length() < 1024L) {
                list.add(
                    UpdateItem(
                        "poi_${c.code}",
                        BlockType.MAPS,
                        "Локації", "Locations",
                        c.poiFileName,
                        ItemStatus.MISSING,
                        estimatedPoiSize
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        downloadFileDirect(c.poiUrl, poiFile, onBytes, onSizeDiscovered, isCancel)
                    }
                )
            } else if (countryPoiNeedsUpdate || MapDownloadManager.isCountryPoiUpdateAvailable(c)) {
                list.add(
                    UpdateItem(
                        "poi_${c.code}",
                        BlockType.MAPS,
                        "Локації", "Locations",
                        c.poiFileName,
                        ItemStatus.UPDATE,
                        estimatedPoiSize
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        downloadFileDirect(c.poiUrl, poiFile, onBytes, onSizeDiscovered, isCancel)
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
                                "db_${part.fileName}",
                                BlockType.MUSHROOMS,
                                "Енциклопедія - архіви", "Encyclopedia - archives",
                                part.fileName,
                                st,
                                part.exactSize
                            ) { onBytes, onSizeDiscovered, isCancel ->
                                downloadPartWithResume("${MushroomDataConfig.DB_BASE_DOWNLOAD_URL}${part.fileName}", partFile, part.exactSize, onBytes, onSizeDiscovered, isCancel)
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
                                "model_${part.fileName}",
                                BlockType.MUSHROOMS,
                                "Визначник - архіви", "Classifier - archives",
                                part.fileName,
                                st,
                                part.exactSize
                            ) { onBytes, onSizeDiscovered, isCancel ->
                                downloadPartWithResume("${MushroomDataConfig.MODEL_BASE_DOWNLOAD_URL}${part.fileName}", partFile, part.exactSize, onBytes, onSizeDiscovered, isCancel)
                            }
                        )
                    }
                }
            }

            return list
        }

        val badgeViews = mutableMapOf<String, TextView>()
        val rowViews = mutableMapOf<String, View>()
        val progressLayoutViews = mutableMapOf<String, View>()
        val itemProgressBars = mutableMapOf<String, ProgressBar>()
        val itemPercentViews = mutableMapOf<String, TextView>()

        fun updateItemBadge(item: UpdateItem) {
            activity.runOnUiThread {
                val badge = badgeViews[item.id] ?: return@runOnUiThread
                val progLayout = progressLayoutViews[item.id] ?: return@runOnUiThread
                val bg = (badge.background as? GradientDrawable) ?: GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    badge.background = this
                }
                when (item.status) {
                    ItemStatus.DONE -> {
                        badge.visibility = View.VISIBLE
                        progLayout.visibility = View.GONE
                        badge.text = if (isUk) "Готово" else "Done"
                        bg.setColor(Color.parseColor("#10B981"))
                    }
                    ItemStatus.DOWNLOADING -> {
                        badge.visibility = View.GONE
                        progLayout.visibility = View.VISIBLE
                    }
                    ItemStatus.MISSING -> {
                        badge.visibility = View.VISIBLE
                        progLayout.visibility = View.GONE
                        badge.text = if (isUk) "Відсутній" else "Missing"
                        bg.setColor(Color.parseColor("#EF4444"))
                    }
                    ItemStatus.UPDATE -> {
                        badge.visibility = View.VISIBLE
                        progLayout.visibility = View.GONE
                        badge.text = if (isUk) "Оновлення" else "Update"
                        bg.setColor(Color.parseColor("#0284C7"))
                    }
                }
            }
        }

        fun updateItemProgress(item: UpdateItem, currentBytes: Long, totalBytes: Long) {
            activity.runOnUiThread {
                val pBar = itemProgressBars[item.id] ?: return@runOnUiThread
                val tvPct = itemPercentViews[item.id] ?: return@runOnUiThread
                val progLayout = progressLayoutViews[item.id]
                if (progLayout?.visibility != View.VISIBLE) {
                    badgeViews[item.id]?.visibility = View.GONE
                    progLayout?.visibility = View.VISIBLE
                }
                if (totalBytes > 0L) {
                    val ratio = (currentBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                    val pVal = (ratio * 100).toInt()
                    pBar.progress = pVal
                    tvPct.text = "$pVal%"
                } else {
                    pBar.progress = 0
                    tvPct.text = "..."
                }
            }
        }

        fun scrollToItem(item: UpdateItem) {
            activity.runOnUiThread {
                val row = rowViews[item.id] ?: return@runOnUiThread
                val rect = android.graphics.Rect()
                row.getDrawingRect(rect)
                scrollView.offsetDescendantRectToMyCoords(row, rect)
                scrollView.smoothScrollTo(0, (rect.top - dp(36)).coerceAtLeast(0))
            }
        }

        // Рендеринг карток списку
        fun renderItemsList() {
            cardsContainer.removeAllViews()
            badgeViews.clear()
            rowViews.clear()
            progressLayoutViews.clear()
            itemProgressBars.clear()
            itemPercentViews.clear()

            val hasAppUpdateOnly = currentItems.any { it.subCategoryUk == "Додаток" }
            countryRow.visibility = if (hasAppUpdateOnly) View.GONE else View.VISIBLE
            subtitleTv.text = if (hasAppUpdateOnly) {
                if (isUk) "Доступна нова версія додатку. Спочатку оновіть додаток:"
                else "A new app version is available. Please update the app first:"
            } else {
                if (isUk) "Доступні файли та оновлення для офлайн-роботи:"
                else "Available files and updates for offline operation:"
            }

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
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, dp(8), 0)
                    }
                }

                // Контейнер статусу (бейдж або прогрес-бар для поточної операції)
                val statusContainer = FrameLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(88), dp(22))
                }

                val badgeTv = TextView(activity).apply {
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                    }
                    when (item.status) {
                        ItemStatus.DONE -> {
                            text = if (isUk) "Готово" else "Done"
                            bg.setColor(Color.parseColor("#10B981"))
                        }
                        ItemStatus.DOWNLOADING -> {
                            text = if (isUk) "Завантаження" else "Downloading"
                            bg.setColor(Color.parseColor("#F59E0B"))
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
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                val progressLayout = FrameLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    visibility = if (item.status == ItemStatus.DOWNLOADING) View.VISIBLE else View.GONE
                }

                val bgBar = GradientDrawable().apply {
                    setColor(Color.parseColor("#374151"))
                    cornerRadius = dp(4).toFloat()
                }
                val progressShape = GradientDrawable().apply {
                    setColor(Color.parseColor("#0284C7"))
                    cornerRadius = dp(4).toFloat()
                }
                val clipProgress = android.graphics.drawable.ClipDrawable(progressShape, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
                val pBarDrawable = android.graphics.drawable.LayerDrawable(arrayOf(bgBar, clipProgress)).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }

                val pBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = false
                    max = 100
                    progress = 0
                    progressDrawable = pBarDrawable
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                val tvPercent = TextView(activity).apply {
                    text = "0%"
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                progressLayout.addView(pBar)
                progressLayout.addView(tvPercent)

                if (item.status == ItemStatus.DOWNLOADING) {
                    badgeTv.visibility = View.GONE
                    progressLayout.visibility = View.VISIBLE
                } else {
                    badgeTv.visibility = View.VISIBLE
                    progressLayout.visibility = View.GONE
                }

                statusContainer.addView(badgeTv)
                statusContainer.addView(progressLayout)

                badgeViews[item.id] = badgeTv
                progressLayoutViews[item.id] = progressLayout
                itemProgressBars[item.id] = pBar
                itemPercentViews[item.id] = tvPercent
                rowViews[item.id] = row

                row.addView(rowTitleTv)
                row.addView(statusContainer)
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
                countryMapNeedsUpdate = false
                countryPoiNeedsUpdate = false
                refreshList()
                MapDownloadManager.fetchRemoteFileSizeAsync(selectedCountry.mapUrl, selectedCountry.mapFileName) { len ->
                    activity.runOnUiThread {
                        val itm = currentItems.find { it.id == "map_${selectedCountry.code}" }
                        if (itm != null && !isDownloadingActive) {
                            itm.expectedBytes = len
                        }
                    }
                }
                MapDownloadManager.fetchRemoteFileSizeAsync(selectedCountry.poiUrl, selectedCountry.poiFileName) { len ->
                    activity.runOnUiThread {
                        val itm = currentItems.find { it.id == "poi_${selectedCountry.code}" }
                        if (itm != null && !isDownloadingActive) {
                            itm.expectedBytes = len
                        }
                    }
                }
                for (seg in selectedCountry.getRd5Segments()) {
                    MapDownloadManager.fetchRemoteFileSizeAsync("https://brouter.de/brouter/segments4/$seg", seg) { len ->
                        activity.runOnUiThread {
                            val itm = currentItems.find { it.id == "rd5_$seg" }
                            if (itm != null && !isDownloadingActive) {
                                itm.expectedBytes = len
                            }
                        }
                    }
                }
                MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
                    countryMapNeedsUpdate = MapDownloadManager.isCountryMapUpdateAvailable(selectedCountry)
                    countryPoiNeedsUpdate = MapDownloadManager.isCountryPoiUpdateAvailable(selectedCountry)
                    if (hasUpdate) {
                        activity.runOnUiThread { refreshList() }
                    }
                }
            }
        }

        // Фонова перевірка оновлень з GitHub
        AppUpdateManager.checkUpdateStatusAsync(activity) { hasUpdate, latestName, _, downloadUrl, assetSize ->
            if (hasUpdate) {
                val dlDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                val apkFile = File(dlDir, "$latestName.apk")
                appUpdateData = AppUpdateData(true, latestName, downloadUrl, apkFile, if (assetSize > 0L) assetSize else 25_000_000L)
                activity.runOnUiThread {
                    if (!isDownloadingActive) {
                        refreshList()
                    }
                }
            }
        }

        // Фонова перевірка карти країни
        MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
            countryMapNeedsUpdate = MapDownloadManager.isCountryMapUpdateAvailable(selectedCountry)
            countryPoiNeedsUpdate = MapDownloadManager.isCountryPoiUpdateAvailable(selectedCountry)
            if (hasUpdate) {
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

        // Фонове уточнення розмірів обраної країни
        MapDownloadManager.fetchRemoteFileSizeAsync(selectedCountry.mapUrl, selectedCountry.mapFileName) { len ->
            activity.runOnUiThread {
                val itm = currentItems.find { it.id == "map_${selectedCountry.code}" }
                if (itm != null && !isDownloadingActive) {
                    itm.expectedBytes = len
                }
            }
        }
        MapDownloadManager.fetchRemoteFileSizeAsync(selectedCountry.poiUrl, selectedCountry.poiFileName) { len ->
            activity.runOnUiThread {
                val itm = currentItems.find { it.id == "poi_${selectedCountry.code}" }
                if (itm != null && !isDownloadingActive) {
                    itm.expectedBytes = len
                }
            }
        }
        for (seg in selectedCountry.getRd5Segments()) {
            MapDownloadManager.fetchRemoteFileSizeAsync("https://brouter.de/brouter/segments4/$seg", seg) { len ->
                activity.runOnUiThread {
                    val itm = currentItems.find { it.id == "rd5_$seg" }
                    if (itm != null && !isDownloadingActive) {
                        itm.expectedBytes = len
                    }
                }
            }
        }

        // --- ДІЯ: ЗАВАНТАЖЕННЯ ---
        btnDownload.setOnClickListener {
            if (currentItems.isEmpty()) return@setOnClickListener

            isDownloadingActive = true
            buttonRow.visibility = View.GONE
            btnCountryChange.visibility = View.GONE
            progressContainer.visibility = View.VISIBLE

            var totalExpectedBytes = currentItems.sumOf { it.expectedBytes }.coerceAtLeast(1L)
            var totalDownloadedBytes = 0L
            val startTimeMs = System.currentTimeMillis()
            var lastUiUpdateMs = 0L

            val dbDir = MushroomDatabaseManager.getDatabaseDirectory(activity)
            val allDbParts = MushroomDataConfig.DB_PARTS.map { File(dbDir, it.fileName) }
            val willUnpackDb = currentItems.any { it.subCategoryUk.contains("Енциклопедія") } ||
                    (!MushroomDatabaseManager.isDatabaseUpToDate(activity) && allDbParts.all { it.exists() && it.length() > 0 })

            val modelDir = MushroomClassifier.getModelDirectory()
            val allModelParts = MushroomDataConfig.MODEL_PARTS.map { File(modelDir, it.fileName) }
            val willUnpackModel = currentItems.any { it.subCategoryUk.contains("Визначник") } ||
                    (!MushroomClassifier.isModelDownloaded(activity) && allModelParts.all { it.exists() && it.length() > 0 })

            val hasUnpack = willUnpackDb || willUnpackModel
            val maxDownloadProgress = if (hasUnpack) 850 else 1000

            fun updateProgressUi() {
                val now = System.currentTimeMillis()
                if (now - lastUiUpdateMs < 300 && totalDownloadedBytes < totalExpectedBytes) return
                lastUiUpdateMs = now

                val elapsedSec = (now - startTimeMs) / 1000.0

                // Знаменник ніколи не повинен бути меншим за викачане + очікуваний залишок незавершених файлів
                val unfinishedRemaining = currentItems
                    .filter { it.status == ItemStatus.MISSING || it.status == ItemStatus.UPDATE }
                    .sumOf { it.expectedBytes }

                val effectiveTotal = maxOf(totalExpectedBytes, totalDownloadedBytes + unfinishedRemaining)
                val progressRatio = (totalDownloadedBytes.toDouble() / effectiveTotal.toDouble()).coerceIn(0.0, 1.0)
                val currentProgressVal = (progressRatio * maxDownloadProgress).toInt()

                val timeStr: String
                if (elapsedSec > 1.2 && totalDownloadedBytes > 60_000L) {
                    val speed = totalDownloadedBytes / elapsedSec
                    val remainingBytes = (effectiveTotal - totalDownloadedBytes).coerceAtLeast(unfinishedRemaining)
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
                        val s = if (remainingSec == 0L && currentItems.any { it.status != ItemStatus.DONE }) 1L else remainingSec
                        if (isUk) "Залишилось: ~$s с" else "Remaining: ~$s sec"
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
                val hasAppOnly = currentItems.any { it.subCategoryUk == "Додаток" }
                if (hasAppOnly) {
                    val apkItem = currentItems.first()
                    apkItem.status = ItemStatus.DOWNLOADING
                    updateItemBadge(apkItem)
                    scrollToItem(apkItem)

                    var apkDownloadedBytes = 0L
                    var lastApkUiMs = 0L

                    val okApk = apkItem.downloadAction(
                        { bytesRead ->
                            apkDownloadedBytes += bytesRead
                            totalDownloadedBytes += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastApkUiMs >= 80 || apkDownloadedBytes >= apkItem.expectedBytes) {
                                lastApkUiMs = now
                                updateItemProgress(apkItem, apkDownloadedBytes, apkItem.expectedBytes)
                            }
                            updateProgressUi()
                        },
                        { actualSize ->
                            if (actualSize > 0L) {
                                totalExpectedBytes = actualSize
                                apkItem.expectedBytes = actualSize
                                updateItemProgress(apkItem, apkDownloadedBytes, apkItem.expectedBytes)
                                updateProgressUi()
                            }
                        },
                        { cancelFlag.get() }
                    )
                    if (okApk) {
                        apkItem.status = ItemStatus.DONE
                        updateItemBadge(apkItem)
                    } else {
                        apkItem.status = ItemStatus.MISSING
                        updateItemBadge(apkItem)
                    }

                    val apkFile = appUpdateData?.apkFile ?: run {
                        val cached = AppUpdateManager.getCachedAppUpdate(activity)
                        if (cached != null) {
                            val dlDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                            File(dlDir, "${cached.latestVerName}.apk")
                        } else null
                    }

                    try { Thread.sleep(400L) } catch (_: Exception) {}

                    activity.runOnUiThread {
                        progressBar.progress = 1000
                        tvTimeRemaining.text = if (isUk) "Готово! Встановлення оновлення..." else "Done! Installing update..."
                        isDownloadingActive = false
                        dialog.dismiss()
                        if (apkFile != null && apkFile.exists()) {
                            AppUpdateManager.installApk(activity, apkFile)
                        }
                    }
                    return@execute
                }

                // Завантаження компонентів (карти, бази, моделі)
                var hasFailures = false

                for (item in currentItems) {
                    if (cancelFlag.get()) break
                    item.status = ItemStatus.DOWNLOADING
                    updateItemBadge(item)
                    scrollToItem(item)

                    var itemDownloadedBytes = 0L
                    var lastItemUiMs = 0L

                    val ok = item.downloadAction(
                        { bytesRead ->
                            itemDownloadedBytes += bytesRead
                            totalDownloadedBytes += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastItemUiMs >= 80 || itemDownloadedBytes >= item.expectedBytes) {
                                lastItemUiMs = now
                                updateItemProgress(item, itemDownloadedBytes, item.expectedBytes)
                            }
                            updateProgressUi()
                        },
                        { actualSize ->
                            if (actualSize >= 0L && actualSize != item.expectedBytes) {
                                val diff = actualSize - item.expectedBytes
                                totalExpectedBytes = (totalExpectedBytes + diff).coerceAtLeast(1L)
                                item.expectedBytes = actualSize
                                updateItemProgress(item, itemDownloadedBytes, item.expectedBytes)
                                updateProgressUi()
                            }
                        },
                        { cancelFlag.get() }
                    )
                    if (ok) {
                        item.status = ItemStatus.DONE
                    } else {
                        item.status = ItemStatus.MISSING
                        AppLogger.log(TAG, "download", false, "Failed downloading ${item.title}")
                        if (item.block != BlockType.MAPS || !item.title.endsWith(".rd5")) {
                            hasFailures = true
                        }
                    }
                    updateItemBadge(item)
                }

                // Розпакування бази даних якщо всі архіви на місці
                val allDbExist = allDbParts.all { it.exists() && it.length() > 0 }
                if (allDbExist && !MushroomDatabaseManager.isDatabaseUpToDate(activity) && !cancelFlag.get()) {
                    val dbStart = 850
                    val dbEnd = if (willUnpackModel) 930 else 1000
                    activity.runOnUiThread {
                        progressBar.progress = dbStart
                        tvTimeRemaining.text = if (isUk) "Розпакування бази даних..." else "Unpacking database..."
                    }
                    MushroomDatabaseManager.unpackDownloadedParts(activity, allDbParts) { unpacked, total ->
                        val ratio = (unpacked.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                        val pVal = dbStart + (ratio * (dbEnd - dbStart)).toInt()
                        val pct = (ratio * 100).toInt()
                        activity.runOnUiThread {
                            progressBar.progress = pVal
                            tvTimeRemaining.text = if (isUk) "Розпакування бази даних... ($pct%)" else "Unpacking database... ($pct%)"
                        }
                    }
                }

                // Розпакування моделі якщо всі архіви на місці
                val allModelExist = allModelParts.all { it.exists() && it.length() > 0 }
                if (allModelExist && !MushroomClassifier.isModelDownloaded(activity) && !cancelFlag.get()) {
                    val modelStart = if (willUnpackDb) 930 else 850
                    val modelEnd = 1000
                    activity.runOnUiThread {
                        progressBar.progress = modelStart
                        tvTimeRemaining.text = if (isUk) "Розпакування нейромережі..." else "Unpacking model..."
                    }
                    MushroomClassifier.unpackDownloadedParts(activity, allModelParts) { unpacked, total ->
                        val ratio = (unpacked.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                        val pVal = modelStart + (ratio * (modelEnd - modelStart)).toInt()
                        val pct = (ratio * 100).toInt()
                        activity.runOnUiThread {
                            progressBar.progress = pVal
                            tvTimeRemaining.text = if (isUk) "Розпакування нейромережі... ($pct%)" else "Unpacking model... ($pct%)"
                        }
                    }
                }

                try { Thread.sleep(400L) } catch (_: Exception) {}

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
        onSizeDiscovered: (Long) -> Unit = {},
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
                if (allowHttpErrors) {
                    onSizeDiscovered(0L)
                    return true
                }
                return false
            }

            val serverLen = conn.contentLengthLong
            if (serverLen > 0L) {
                onSizeDiscovered(serverLen)
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
        onSizeDiscovered: (Long) -> Unit = {},
        isCancelled: () -> Boolean
    ): Boolean {
        destFile.parentFile?.mkdirs()
        if (destFile.exists() && destFile.length() == expectedSize) {
            onSizeDiscovered(expectedSize)
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
                var currentUrl = urlStr
                var redirects = 0
                while (redirects < 5) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "MushroomApp/1.0 (Android)")
                        if (existingLen > 0) {
                            setRequestProperty("Range", "bytes=$existingLen-")
                        }
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
                if (conn == null) continue

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

                if (expectedSize > 0L) {
                    onSizeDiscovered(expectedSize)
                } else {
                    val sLen = conn.contentLengthLong
                    if (sLen > 0L) onSizeDiscovered(if (isResume) sLen + existingLen else sLen)
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
