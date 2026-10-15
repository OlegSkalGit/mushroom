package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.StatFs
import com.olegskal.mushroom.MushroomMapActivity
import com.olegskal.mushroom.address.AddressDb
import com.olegskal.mushroom.address.AddressDownloadManager
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

object InitialSetupDialog {

    private const val TAG = "InitialSetupDialog"
    private const val USER_AGENT = "Mushroom/2.0 (Android; https://github.com/OlegSkalGit/mushroom)"

    fun getCountryRegionCount(country: MapCountry): Int {
        return AddressDownloadManager.getCountryRegionCount(country.code)
    }

    enum class BlockType {
        APP,
        MAPS,
        ADDRESSES,
        MUSHROOMS
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
        var expectedBytes: Long,
        val downloadAction: (onBytes: (Long) -> Unit, onSizeDiscovered: (Long) -> Unit, isCancelled: () -> Boolean) -> Boolean
    ) {
        override fun equals(other: Any?): Boolean = other is UpdateItem && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    enum class EnqueueResult {
        ALREADY_INSTALLED,
        ALREADY_IN_QUEUE,
        ENQUEUED,
        STARTED
    }

    private val bgExecutor = Executors.newCachedThreadPool()
    private val isDownloadRunning = AtomicBoolean(false)
    private val pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<UpdateItem>()
    private val activeItem = java.util.concurrent.atomic.AtomicReference<UpdateItem?>(null)

    fun isDownloading(): Boolean = isDownloadRunning.get() || activeItem.get() != null || pendingQueue.isNotEmpty()

    fun isItemInQueue(idPrefix: String): Boolean {
        if (activeItem.get()?.id?.startsWith(idPrefix) == true) return true
        return pendingQueue.any { it.id.startsWith(idPrefix) }
    }

    fun isCountryInQueue(countryCode: String): Boolean {
        val curId = activeItem.get()?.id ?: ""
        if (curId == "map_$countryCode" || curId == "poi_$countryCode" || curId == "addr_$countryCode") return true
        return pendingQueue.any { it.id == "map_$countryCode" || it.id == "poi_$countryCode" || it.id == "addr_$countryCode" }
    }

    fun isDatabaseInQueue(): Boolean {
        if (activeItem.get()?.id?.startsWith("db_") == true) return true
        return pendingQueue.any { it.id.startsWith("db_") }
    }

    fun isModelInQueue(): Boolean {
        if (activeItem.get()?.id?.startsWith("model_") == true) return true
        return pendingQueue.any { it.id.startsWith("model_") }
    }

    /**
     * Перевірка при запуску застосунку.
     */
    fun showIfNeeded(activity: Activity, onFinished: () -> Unit = {}) {
        if (activity.isFinishing || isDownloadRunning.get()) return

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
        val hasMissingLocalFiles = checkHasMissingLocalFiles(activity, detectedCountry)
        if (hasMissingLocalFiles) {
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    show(activity, detectedCountry, onFinished)
                }
            }
            return
        }

        val dialogShown = AtomicBoolean(false)
        fun triggerShow() {
            if (dialogShown.compareAndSet(false, true)) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !isDownloadRunning.get()) {
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
        // 1. Оглядова карта
        val worldFile = File(MushroomStorageManager.mapsDir, MapDownloadManager.WORLD_MAP_FILE_NAME)
        if (!worldFile.exists() || worldFile.length() < 1024L) return true

        // 2. Карта країни
        val mapFile = File(MushroomStorageManager.mapsDir, country.mapFileName)
        if (!mapFile.exists() || mapFile.length() < 1024L) return true

        // 3. Точки інтересу POI (локації)
        val poiFile = File(MushroomStorageManager.poiDir, country.poiFileName)
        if (!poiFile.exists() || poiFile.length() < 1024L) return true

        // 4. Файли маршрутизації rd5
        for (seg in country.getRd5Segments()) {
            if (MapDownloadManager.isSegmentNeeded(seg)) return true
        }

        // 5. Енциклопедія
        val dbReady = MushroomDatabaseManager.checkDatabaseStatus(activity) == MushroomDatabaseManager.DataStatus.READY
        if (!dbReady) return true

        // 6. Файли визначника
        val modelReady = MushroomClassifier.checkModelStatus(activity) == MushroomClassifier.ModelStatus.READY
        if (!modelReady) return true

        // 7. База адрес
        val addressReady = AddressDownloadManager.hasAddressesForCountry(activity, country.code)
        if (!addressReady) return true

        // 8. Локальні точки інтересу (POI) в базі адрес
        if (AddressDownloadManager.needsPoiUpdate(activity, country)) return true

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
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        var selectedCountry = initialCountry

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

        // 3. Контейнер списку (Scrollable)
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
                dialog.dismiss()
                onFinished()
            }
        }
        buttonRow.addView(btnDownload)
        buttonRow.addView(btnLater)
        root.addView(buttonRow)

        var currentItems = mutableListOf<UpdateItem>()
        val cachedAtStart = AppUpdateManager.getCachedAppUpdate(activity)
        var appUpdateData: AppUpdateData? = if (cachedAtStart != null && cachedAtStart.hasUpdate) {
            val apkFile = AppUpdateManager.getApkFile(cachedAtStart.latestVerName)
            AppUpdateData(true, cachedAtStart.latestVerName, cachedAtStart.downloadUrl, apkFile, cachedAtStart.assetSize)
        } else null

        var countryMapNeedsUpdate = false
        var worldNeedsUpdate = false

        fun scanItems(): List<UpdateItem> {
            return scanItemsForCountry(activity, selectedCountry, appUpdateData, countryMapNeedsUpdate, worldNeedsUpdate)
        }

        // Рендеринг списку: чистий список компонентів без панелей статусів та міні прогрес-барів
        fun renderItemsList() {
            cardsContainer.removeAllViews()

            val hasAppUpdateOnly = currentItems.any { it.block == BlockType.APP }
            countryRow.visibility = if (hasAppUpdateOnly) View.GONE else View.VISIBLE
            subtitleTv.text = if (hasAppUpdateOnly) {
                if (isUk) "Доступна нова версія додатку. Оновіть додаток:"
                else "A new app version is available. Update the app:"
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

            val listCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#263226"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#374737"))
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.setMargins(0, 0, 0, dp(10))
                layoutParams = p
            }

            for (item in currentItems) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val subCat = if (isUk) item.subCategoryUk else item.subCategoryEn
                val rowTv = TextView(activity).apply {
                    text = "• $subCat: ${item.title}"
                    setTextColor(Color.parseColor("#E5E7EB"))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                row.addView(rowTv)
                listCard.addView(row)
            }

            cardsContainer.addView(listCard)
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
                refreshList()
            }
        }

        // Фонова перевірка оновлень з GitHub
        AppUpdateManager.checkUpdateStatusAsync(activity) { hasUpdate, latestName, _, downloadUrl, assetSize ->
            if (hasUpdate) {
                val apkFile = AppUpdateManager.getApkFile(latestName)
                appUpdateData = AppUpdateData(true, latestName, downloadUrl, apkFile, if (assetSize > 0L) assetSize else 25_000_000L)
                activity.runOnUiThread {
                    if (!isDownloadRunning.get()) {
                        refreshList()
                    }
                }
            }
        }

        // Фонова перевірка карти країни
        MapDownloadManager.checkCountryUpdatesAsync(selectedCountry) { hasUpdate ->
            countryMapNeedsUpdate = MapDownloadManager.isCountryMapUpdateAvailable(selectedCountry)
            if (hasUpdate) {
                activity.runOnUiThread { refreshList() }
            }
        }

        // Фонова перевірка світової карти та отримання її розміру безпосередньо з сервера
        MapDownloadManager.checkWorldMapUpdatesAsync { hasUpdate ->
            if (hasUpdate) {
                worldNeedsUpdate = true
                activity.runOnUiThread { refreshList() }
            }
        }
        MapDownloadManager.fetchRemoteFileSizeAsync(MapDownloadManager.WORLD_MAP_URL, MapDownloadManager.WORLD_MAP_FILE_NAME) { len ->
            if (len > 0L) {
                activity.runOnUiThread {
                    if (!isDownloadRunning.get()) {
                        refreshList()
                    }
                }
            }
        }

        btnDownload.setOnClickListener {
            try {
                if (currentItems.isEmpty()) return@setOnClickListener

                val itemsToProcess = ArrayList(currentItems)
                try {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    AppLogger.log(TAG, "btnDownload", false, "Error dismissing dialog: ${e.message}")
                }

                enqueueItems(activity, itemsToProcess, onFinished)
            } catch (e: Throwable) {
                AppLogger.log(TAG, "btnDownload.onClick", false, "Error in btnDownload: ${e.message}\n${e.stackTraceToString()}")
            }
        }

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        dialog.show()
    }

    fun scanCountryItems(
        context: Context,
        country: MapCountry,
        countryMapNeedsUpdate: Boolean = false,
        worldNeedsUpdate: Boolean = false
    ): List<UpdateItem> {
        val list = mutableListOf<UpdateItem>()
        val isUk = AppPrefs.isUk(context)

        // 1. Оглядова карта
        val worldFile = File(MushroomStorageManager.mapsDir, MapDownloadManager.WORLD_MAP_FILE_NAME)
        if (!worldFile.exists() || worldFile.length() < 1024L || worldNeedsUpdate || MapDownloadManager.isWorldMapUpdateAvailable()) {
            val remoteWorldSize = MapDownloadManager.getRemoteFileSize(MapDownloadManager.WORLD_MAP_FILE_NAME)
                ?: MapDownloadManager.getRemoteFileSize(MapDownloadManager.WORLD_MAP_URL)
                ?: 0L
            list.add(
                UpdateItem(
                    "map_world",
                    BlockType.MAPS,
                    "Оглядова карта", "Overview map",
                    MapDownloadManager.WORLD_MAP_FILE_NAME,
                    remoteWorldSize
                ) { onBytes, onSizeDiscovered, isCancel ->
                    downloadFileWithResume(MapDownloadManager.WORLD_MAP_URL, worldFile, -1L, onBytes, onSizeDiscovered, isCancel)
                }
            )
        }

        // 2. Карта країни
        val regCount = getCountryRegionCount(country)
        val regSuffix = if (isUk) "$regCount регіонів" else "$regCount regions"

        val mapFile = File(MushroomStorageManager.mapsDir, country.mapFileName)
        val estimatedMapSize = MapDownloadManager.getRemoteFileSize(country.mapFileName)
            ?: MapDownloadManager.getRemoteFileSize(country.mapUrl)
            ?: (if (country.code == "UA") 874_000_000L else 600_000_000L)

        val poiFile = File(MushroomStorageManager.poiDir, country.poiFileName)

        if (!mapFile.exists() || mapFile.length() < 1024L || countryMapNeedsUpdate || MapDownloadManager.isCountryMapUpdateAvailable(country)) {
            list.add(
                UpdateItem(
                    "map_${country.code}",
                    BlockType.MAPS,
                    "Карта країни", "Country map",
                    "${if (isUk) country.nameUk else country.name} ($regSuffix, ${country.mapFileName})",
                    estimatedMapSize
                ) { onBytes, onSizeDiscovered, isCancel ->
                    downloadFileWithResume(country.mapUrl, mapFile, estimatedMapSize, onBytes, onSizeDiscovered, isCancel)
                }
            )
        }

        // 3. Точки інтересу POI
        val estimatedPoiSize = MapDownloadManager.getRemoteFileSize(country.poiFileName)
            ?: MapDownloadManager.getRemoteFileSize(country.poiUrl)
            ?: (if (country.code == "UA") 35_000_000L else 20_000_000L)

        if (!poiFile.exists() || poiFile.length() < 1024L || countryMapNeedsUpdate) {
            list.add(
                UpdateItem(
                    "poi_${country.code}",
                    BlockType.MAPS,
                    "Точки інтересу POI", "Points of Interest POI",
                    "${if (isUk) country.nameUk else country.name} (${country.poiFileName})",
                    estimatedPoiSize
                ) { onBytes, onSizeDiscovered, isCancel ->
                    downloadFileWithResume(country.poiUrl, poiFile, estimatedPoiSize, onBytes, onSizeDiscovered, isCancel)
                }
            )
        }

        // 4. Файли маршрутизації
        for (seg in country.getRd5Segments()) {
            if (MapDownloadManager.isSegmentNeeded(seg)) {
                val segFile = File(MushroomStorageManager.navigationDir, seg)
                list.add(
                    UpdateItem(
                        "rd5_$seg",
                        BlockType.MAPS,
                        "Файли маршрутизації", "Routing files",
                        seg,
                        18_000_000L
                    ) { onBytes, onSizeDiscovered, isCancel ->
                        val ok = downloadFileWithResume("https://brouter.de/brouter/segments4/$seg", segFile, 18_000_000L, onBytes, onSizeDiscovered, isCancel, allowHttpErrors = true)
                        ok || (!segFile.exists() && MapDownloadManager.isSegmentUnavailable(seg))
                    }
                )
            }
        }

        return list
    }

    fun scanAddressItems(context: Context, country: MapCountry): List<UpdateItem> {
        val list = mutableListOf<UpdateItem>()
        val addressReady = AddressDownloadManager.hasAddressesForCountry(context, country.code)
        if (!addressReady) {
            val isUk = AppPrefs.isUk(context)
            val regCount = getCountryRegionCount(country)
            val regSuffix = if (isUk) "$regCount регіонів" else "$regCount regions"
            val loadedRegs = AddressDownloadManager.getLoadedRegionCount(context, country.code)
            val totalRegs = AddressDownloadManager.getCountryRegionCount(country.code)
            val regDisplay = if (loadedRegs in 1 until totalRegs) {
                if (isUk) "завантажено $loadedRegs з $totalRegs регіонів" else "$loadedRegs of $totalRegs regions"
            } else regSuffix

            list.add(
                UpdateItem(
                    "addr_${country.code}",
                    BlockType.ADDRESSES,
                    "База адрес", "Address database",
                    "${if (isUk) country.nameUk else country.name} ($regDisplay, addr.db)",
                    35_000_000L
                ) { onBytes, _, isCancel ->
                    var lastReported = 0L
                    AddressDownloadManager.ingestCountryAddresses(
                        context = context,
                        country = country,
                        onProgress = { ratio, statusMsg ->
                            val curBytes = (ratio * 35_000_000L).toLong()
                            val delta = curBytes - lastReported
                            if (delta > 0L) {
                                lastReported = curBytes
                                onBytes(delta)
                            }
                            val mapAct = context as? MushroomMapActivity
                            val rem = pendingQueue.size
                            val remSuffix = if (rem > 0) (if (isUk) " (у черзі: $rem)" else " (in queue: $rem)") else ""
                            mapAct?.updateBottomDownloadProgress(true, -1, 1000, "$statusMsg$remSuffix")
                        },
                        isCancelled = isCancel
                    )
                }
            )
        }
        if (AddressDownloadManager.needsPoiUpdate(context, country)) {
            val isUk = AppPrefs.isUk(context)
            list.add(
                UpdateItem(
                    "addr_poi_${country.code}",
                    BlockType.ADDRESSES,
                    if (isUk) "Точки інтересу (POI)" else "Points of Interest (POI)",
                    if (isUk) "Оновлення локальних POI" else "Local POI update",
                    "${if (isUk) country.nameUk else country.name} (${if (isUk) "оновити POI з локального файлу, addr.db" else "update POI from local file, addr.db"})",
                    5_000_000L
                ) { onBytes, _, isCancel ->
                    var lastReported = 0L
                    AddressDownloadManager.importPoiLocationsToAddressDb(
                        context = context,
                        country = country,
                        onProgress = { ratio, statusMsg ->
                            val curBytes = (ratio * 5_000_000L).toLong()
                            val delta = curBytes - lastReported
                            if (delta > 0L) {
                                lastReported = curBytes
                                onBytes(delta)
                            }
                            val mapAct = context as? MushroomMapActivity
                            val rem = pendingQueue.size
                            val remSuffix = if (rem > 0) (if (isUk) " (у черзі: $rem)" else " (in queue: $rem)") else ""
                            mapAct?.updateBottomDownloadProgress(true, -1, 1000, "$statusMsg$remSuffix")
                        },
                        isCancelled = isCancel
                    ) > 0
                }
            )
        }
        return list
    }

    fun scanDatabaseItems(context: Context, forceDownload: Boolean = false): List<UpdateItem> {
        val list = mutableListOf<UpdateItem>()
        val dbDir = MushroomDatabaseManager.getDatabaseDirectory(context)
        val finalDb = File(dbDir, MushroomDataConfig.DB_FILE_NAME)
        val isDbReady = !forceDownload && finalDb.exists() && finalDb.length() == MushroomDataConfig.DB_FULL_SIZE
        if (!isDbReady) {
            for (part in MushroomDataConfig.DB_PARTS) {
                val partFile = File(dbDir, part.fileName)
                if (forceDownload || !partFile.exists() || partFile.length() != part.exactSize) {
                    list.add(
                        UpdateItem(
                            "db_${part.fileName}",
                            BlockType.MUSHROOMS,
                            "Енциклопедія", "Encyclopedia",
                            part.fileName,
                            part.exactSize
                        ) { onBytes, onSizeDiscovered, isCancel ->
                            downloadFileWithResume("${MushroomDataConfig.DB_BASE_DOWNLOAD_URL}${part.fileName}", partFile, part.exactSize, onBytes, onSizeDiscovered, isCancel)
                        }
                    )
                }
            }
        }
        return list
    }

    fun scanModelItems(context: Context, forceDownload: Boolean = false): List<UpdateItem> {
        val list = mutableListOf<UpdateItem>()
        val modelReady = !forceDownload && MushroomClassifier.checkModelStatus(context) == MushroomClassifier.ModelStatus.READY
        if (!modelReady) {
            val modelDir = MushroomClassifier.getModelDirectory()
            for (part in MushroomDataConfig.MODEL_PARTS) {
                val partFile = File(modelDir, part.fileName)
                if (forceDownload || !partFile.exists() || partFile.length() != part.exactSize) {
                    list.add(
                        UpdateItem(
                            "model_${part.fileName}",
                            BlockType.MUSHROOMS,
                            "Файли визначника", "Classifier files",
                            part.fileName,
                            part.exactSize
                        ) { onBytes, onSizeDiscovered, isCancel ->
                            downloadFileWithResume("${MushroomDataConfig.MODEL_BASE_DOWNLOAD_URL}${part.fileName}", partFile, part.exactSize, onBytes, onSizeDiscovered, isCancel)
                        }
                    )
                }
            }
        }
        return list
    }

    fun scanItemsForCountry(
        context: Context,
        country: MapCountry,
        appUpdateData: AppUpdateData? = null,
        countryMapNeedsUpdate: Boolean = false,
        worldNeedsUpdate: Boolean = false
    ): List<UpdateItem> {
        val list = mutableListOf<UpdateItem>()

        // 1. ВЕРСІЯ ДОДАТКУ (якщо є оновлення, показуємо ТІЛЬКИ його)
        val upd = appUpdateData ?: run {
            val cached = AppUpdateManager.getCachedAppUpdate(context)
            if (cached != null && cached.hasUpdate) {
                val apkFile = AppUpdateManager.getApkFile(cached.latestVerName)
                AppUpdateData(true, cached.latestVerName, cached.downloadUrl, apkFile, cached.assetSize)
            } else null
        }

        if (upd != null && upd.hasUpdate) {
            list.add(
                UpdateItem(
                    "app_apk",
                    BlockType.APP,
                    "Версія додатку", "App version",
                    "Mushroom (${upd.latestVerName})",
                    upd.assetSize
                ) { onBytes, onSizeDiscovered, isCancel ->
                    AppUpdateManager.downloadApkDirect(upd.downloadUrl, upd.apkFile, onBytes, onSizeDiscovered, isCancel)
                }
            )
            return list
        }

        list.addAll(scanCountryItems(context, country, countryMapNeedsUpdate, worldNeedsUpdate))
        list.addAll(scanDatabaseItems(context))
        list.addAll(scanModelItems(context))
        list.addAll(scanAddressItems(context, country))
        return list
    }

    fun enqueueItems(
        activity: Activity,
        items: List<UpdateItem>,
        onFinished: () -> Unit = {}
    ) {
        val toAdd = items.filter { item ->
            activeItem.get()?.id != item.id && pendingQueue.none { it.id == item.id }
        }
        if (toAdd.isEmpty()) return

        for (item in toAdd) {
            pendingQueue.offer(item)
        }

        if (isDownloadRunning.compareAndSet(false, true)) {
            val totalExpected = toAdd.sumOf { it.expectedBytes }.coerceAtLeast(1L)
            checkDiskSpace(activity, totalExpected + 150_000_000L) {
                checkNetworkAndConfirm(activity, totalExpected) {
                    val mapActivity = activity as? MushroomMapActivity
                    mapActivity?.updateMapDownloadBanner()
                    mapActivity?.updateBottomDownloadProgress(true, 0, 1000)

                    bgExecutor.execute {
                        try {
                            runQueuePipeline(activity, onFinished)
                        } catch (e: Throwable) {
                            AppLogger.log(TAG, "enqueueItems", false, "Crash in pipeline: ${e.message}\n${e.stackTraceToString()}")
                        }
                    }
                }
            }
        }
    }

    fun enqueueCountry(
        activity: Activity,
        country: MapCountry,
        onFinished: () -> Unit = {}
    ): EnqueueResult {
        if (activity.isFinishing || activity.isDestroyed) return EnqueueResult.ALREADY_IN_QUEUE
        val items = scanCountryItems(activity, country) + scanAddressItems(activity, country)
        if (items.isEmpty()) {
            return EnqueueResult.ALREADY_INSTALLED
        }
        if (isCountryInQueue(country.code)) {
            return EnqueueResult.ALREADY_IN_QUEUE
        }
        val wasRunning = isDownloadRunning.get()
        enqueueItems(activity, items, onFinished)
        return if (wasRunning) EnqueueResult.ENQUEUED else EnqueueResult.STARTED
    }

    fun enqueueDatabase(
        activity: Activity,
        forceDownload: Boolean = false,
        onFinished: () -> Unit = {}
    ): EnqueueResult {
        if (activity.isFinishing || activity.isDestroyed) return EnqueueResult.ALREADY_IN_QUEUE
        if (!forceDownload && MushroomDatabaseManager.isDatabaseUpToDate(activity)) {
            return EnqueueResult.ALREADY_INSTALLED
        }
        if (isDatabaseInQueue()) {
            return EnqueueResult.ALREADY_IN_QUEUE
        }
        val items = scanDatabaseItems(activity, forceDownload)
        if (items.isEmpty()) {
            return EnqueueResult.ALREADY_INSTALLED
        }
        val wasRunning = isDownloadRunning.get()
        enqueueItems(activity, items, onFinished)
        return if (wasRunning) EnqueueResult.ENQUEUED else EnqueueResult.STARTED
    }

    fun enqueueModel(
        activity: Activity,
        forceDownload: Boolean = false,
        onFinished: () -> Unit = {}
    ): EnqueueResult {
        if (activity.isFinishing || activity.isDestroyed) return EnqueueResult.ALREADY_IN_QUEUE
        if (!forceDownload && MushroomClassifier.isModelDownloaded(activity)) {
            return EnqueueResult.ALREADY_INSTALLED
        }
        if (isModelInQueue()) {
            return EnqueueResult.ALREADY_IN_QUEUE
        }
        val items = scanModelItems(activity, forceDownload)
        if (items.isEmpty()) {
            return EnqueueResult.ALREADY_INSTALLED
        }
        val wasRunning = isDownloadRunning.get()
        enqueueItems(activity, items, onFinished)
        return if (wasRunning) EnqueueResult.ENQUEUED else EnqueueResult.STARTED
    }

    fun startCountryDownload(
        activity: Activity,
        country: MapCountry,
        onFinished: () -> Unit = {}
    ) {
        enqueueCountry(activity, country, onFinished)
    }

    fun checkDiskSpace(activity: Activity, requiredBytes: Long, onProceed: () -> Unit) {
        try {
            val checkPath = if (MushroomStorageManager.mapsDir.exists()) {
                MushroomStorageManager.mapsDir.absolutePath
            } else if (MushroomStorageManager.baseDir.exists()) {
                MushroomStorageManager.baseDir.absolutePath
            } else {
                activity.filesDir.absolutePath
            }
            val stat = StatFs(checkPath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (availableBytes < requiredBytes) {
                val isUk = AppPrefs.isUk(activity)
                val reqMb = (requiredBytes / (1024 * 1024)).coerceAtLeast(1)
                val availMb = (availableBytes / (1024 * 1024)).coerceAtLeast(0)
                val title = if (isUk) "Недостатньо пам'яті" else "Low Storage Space"
                val msg = if (isUk) {
                    "Для завантаження та розпакування компонентів потрібно щонайменше ~$reqMb МБ вільного місця.\nДоступно: ~$availMb МБ.\nЗвільніть місце на пристрої перед завантаженням."
                } else {
                    "At least ~$reqMb MB of free storage is required to download and unpack components.\nAvailable: ~$availMb MB.\nPlease free up storage space before downloading."
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                return
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "checkDiskSpace", false, "Error checking disk space: ${e.message}")
        }
        onProceed()
    }

    fun checkNetworkAndConfirm(activity: Activity, totalBytes: Long, onProceed: () -> Unit) {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isMetered = cm?.isActiveNetworkMetered == true
        if (isMetered) {
            val isUk = AppPrefs.isUk(activity)
            val totalMb = (totalBytes / (1024 * 1024)).coerceAtLeast(1)
            val title = if (isUk) "Мобільний інтернет" else "Metered Network"
            val msg = if (isUk) {
                "Ви використовуєте мобільний або тарифікований інтернет.\nОбсяг завантаження: ~$totalMb МБ.\nПродовжити завантаження чи зачекати на Wi-Fi?"
            } else {
                "You are using a mobile or metered network connection.\nDownload size: ~$totalMb MB.\nContinue download or wait for Wi-Fi?"
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton(if (isUk) "Продовжити" else "Continue") { _, _ ->
                            onProceed()
                        }
                        .setNegativeButton(if (isUk) "Скасувати" else "Cancel") { _, _ ->
                            pendingQueue.clear()
                            isDownloadRunning.set(false)
                            activeItem.set(null)
                        }
                        .show()
                }
            }
        } else {
            onProceed()
        }
    }

    private fun runQueuePipeline(
        activity: Activity,
        onFinished: () -> Unit
    ) {
        var wakeLock: PowerManager.WakeLock? = null
        val mapActivity = activity as? MushroomMapActivity
        val isUk = AppPrefs.isUk(activity)
        val cancelFlag = AtomicBoolean(false)

        try {
            try {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mushroom:ComponentDownloadWakeLock")?.apply {
                    try { acquire(60 * 60 * 1000L) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "runQueuePipeline", false, "WakeLock init warning: ${e.message}")
            }

            while (true) {
                val item = pendingQueue.poll() ?: break
                activeItem.set(item)

                val queueRem = pendingQueue.size
                var itemBytes = 0L
                var itemExpected = item.expectedBytes.coerceAtLeast(1L)

                fun formatStatus(ratio: Double): String {
                    val pct = (ratio * 100).toInt().coerceIn(0, 100)
                    val title = when {
                        item.id == "app_apk" -> if (isUk) "Оновлення Mushroom" else "Mushroom Update"
                        item.id == "map_world" -> if (isUk) "Оглядова карта світу" else "World overview map"
                        item.id.startsWith("map_") -> "${if (isUk) "Карта" else "Map"} (${item.title})"
                        item.id.startsWith("poi_") -> "${if (isUk) "Точки POI" else "POI"} (${item.title})"
                        item.id.startsWith("rd5_") -> "${if (isUk) "Маршрутизація" else "Routing"} (${item.title})"
                        item.id.startsWith("db_") -> "${if (isUk) "Енциклопедія" else "Encyclopedia"} (${item.title})"
                        item.id.startsWith("model_") -> "${if (isUk) "Визначник" else "Classifier"} (${item.title})"
                        item.id.startsWith("addr_") -> "${if (isUk) "База адрес" else "Addresses"} (${item.title})"
                        else -> item.title
                    }
                    val remStr = if (queueRem > 0) {
                        if (isUk) " (у черзі: $queueRem)" else " (in queue: $queueRem)"
                    } else ""
                    return "$title • $pct%$remStr"
                }

                activity.runOnUiThread {
                    mapActivity?.updateBottomDownloadProgress(true, 0, 1000, formatStatus(0.0))
                }

                val ok = try {
                    item.downloadAction(
                        { bRead ->
                            itemBytes += bRead
                            val ratio = (itemBytes.toDouble() / itemExpected.toDouble()).coerceIn(0.0, 1.0)
                            val pVal = (ratio * 1000).toInt()
                            val status = if (item.id.startsWith("addr_")) null else formatStatus(ratio)
                            activity.runOnUiThread {
                                mapActivity?.updateBottomDownloadProgress(true, pVal, 1000, status)
                            }
                        },
                        { actualSize ->
                            if (actualSize > 0L) {
                                itemExpected = actualSize
                                item.expectedBytes = actualSize
                                val ratio = (itemBytes.toDouble() / itemExpected.toDouble()).coerceIn(0.0, 1.0)
                                val pVal = (ratio * 1000).toInt()
                                val status = if (item.id.startsWith("addr_")) null else formatStatus(ratio)
                                activity.runOnUiThread {
                                    mapActivity?.updateBottomDownloadProgress(true, pVal, 1000, status)
                                }
                            }
                        },
                        { cancelFlag.get() }
                    )
                } catch (e: Throwable) {
                    AppLogger.log(TAG, "runQueuePipeline", false, "Error downloading ${item.id}: ${e.message}")
                    false
                }

                if (ok && !cancelFlag.get()) {
                    handleDownloadedItem(activity, item, mapActivity, isUk)
                }

                activeItem.set(null)
            }

            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    OsmTileEngine.reloadMaps()
                    PoiManager.refreshPoiFiles()
                    mapActivity?.updateMapDownloadBanner()
                    onFinished()
                    val toastMsg = if (isUk) "✓ Завантаження компонентів завершено" else "✓ Components download finished"
                    Toast.makeText(activity, toastMsg, Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Throwable) {
            AppLogger.log(TAG, "runQueuePipeline", false, "Error in queue worker: ${e.message}\n${e.stackTraceToString()}")
        } finally {
            isDownloadRunning.set(false)
            activeItem.set(null)
            if (wakeLock?.isHeld == true) {
                try { wakeLock.release() } catch (_: Exception) {}
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    mapActivity?.updateBottomDownloadProgress(false)
                    mapActivity?.updateMapDownloadBanner()
                }
            }
        }
    }

    private fun handleDownloadedItem(
        activity: Activity,
        item: UpdateItem,
        mapActivity: MushroomMapActivity?,
        isUk: Boolean
    ) {
        when {
            item.id == "app_apk" -> {
                val cached = AppUpdateManager.getCachedAppUpdate(activity)
                val apkFile = if (cached != null) AppUpdateManager.getApkFile(cached.latestVerName) else null
                if (apkFile != null && apkFile.exists()) {
                    activity.runOnUiThread {
                        AppUpdateManager.installApk(activity, apkFile)
                    }
                }
            }
            item.id == "map_world" -> {
                MapDownloadManager.markWorldMapUpToDate()
                activity.runOnUiThread {
                    mapActivity?.reloadMapsAndPois() ?: OsmTileEngine.reloadMaps()
                }
            }
            item.id.startsWith("map_") -> {
                val cCode = item.id.substringAfter("map_")
                val country = MapDownloadManager.countries.find { it.code == cCode }
                if (country != null) {
                    MapDownloadManager.markCountryMapUpToDate(country)
                }
                activity.runOnUiThread {
                    mapActivity?.reloadMapsAndPois() ?: OsmTileEngine.reloadMaps()
                }
            }
            item.id.startsWith("poi_") -> {
                val cCode = item.id.substringAfter("poi_")
                val country = MapDownloadManager.countries.find { it.code == cCode }
                if (country != null) {
                    MapDownloadManager.markCountryPoiUpToDate(country)
                }
                activity.runOnUiThread {
                    mapActivity?.reloadMapsAndPois() ?: PoiManager.refreshPoiFiles()
                }
            }
            item.id.startsWith("rd5_") -> {
                // Segment ready
            }
            item.id.startsWith("addr_") -> {
                activity.runOnUiThread {
                    AddressDb.openDatabase(activity, writable = false)
                }
            }
            item.id.startsWith("db_") -> {
                val dbDir = MushroomDatabaseManager.getDatabaseDirectory(activity)
                val allDbParts = MushroomDataConfig.DB_PARTS.map { File(dbDir, it.fileName) }
                if (allDbParts.all { it.exists() && it.length() > 0 } && !MushroomDatabaseManager.isDatabaseUpToDate(activity)) {
                    activity.runOnUiThread {
                        mapActivity?.updateBottomDownloadProgress(true, -1, 1000, if (isUk) "Розпакування енциклопедії..." else "Unpacking encyclopedia...")
                    }
                    val ok = try {
                        MushroomDatabaseManager.unpackDownloadedParts(activity, allDbParts) { unpacked, total ->
                            val ratio = (unpacked.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                            val pVal = (ratio * 1000).toInt()
                            val pct = (ratio * 100).toInt()
                            val status = "${if (isUk) "Розпакування енциклопедії..." else "Unpacking encyclopedia..."} $pct%"
                            activity.runOnUiThread {
                                mapActivity?.updateBottomDownloadProgress(true, pVal, 1000, status)
                            }
                        }
                    } catch (e: Throwable) {
                        false
                    }
                    if (ok) {
                        activity.runOnUiThread {
                            MushroomDatabaseManager.closeDatabase()
                            MushroomDatabaseManager.getDatabase(activity)
                            Toast.makeText(activity, if (isUk) "✓ Енциклопедію грибів встановлено" else "✓ Mushroom encyclopedia installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            item.id.startsWith("model_") -> {
                val modelDir = MushroomClassifier.getModelDirectory()
                val allModelParts = MushroomDataConfig.MODEL_PARTS.map { File(modelDir, it.fileName) }
                if (allModelParts.all { it.exists() && it.length() > 0 } && !MushroomClassifier.isModelDownloaded(activity)) {
                    activity.runOnUiThread {
                        mapActivity?.updateBottomDownloadProgress(true, -1, 1000, if (isUk) "Розпакування визначника..." else "Unpacking classifier...")
                    }
                    val ok = try {
                        MushroomClassifier.unpackDownloadedParts(activity, allModelParts) { unpacked, total ->
                            val ratio = (unpacked.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                            val pVal = (ratio * 1000).toInt()
                            val pct = (ratio * 100).toInt()
                            val status = "${if (isUk) "Розпакування визначника..." else "Unpacking classifier..."} $pct%"
                            activity.runOnUiThread {
                                mapActivity?.updateBottomDownloadProgress(true, pVal, 1000, status)
                            }
                        }
                    } catch (e: Throwable) {
                        false
                    }
                    if (ok) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, if (isUk) "✓ Визначник грибів встановлено" else "✓ Mushroom classifier installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    fun downloadFileWithResume(
        urlStr: String,
        destFile: File,
        expectedSize: Long = -1L,
        onBytesRead: (Long) -> Unit,
        onSizeDiscovered: (Long) -> Unit = {},
        isCancelled: () -> Boolean,
        allowHttpErrors: Boolean = false
    ): Boolean {
        val parentDir = destFile.parentFile ?: File(destFile.absolutePath).parentFile ?: File(".")
        try { parentDir.mkdirs() } catch (_: Exception) {}
        if (destFile.exists() && expectedSize > 0L && destFile.length() == expectedSize) {
            onSizeDiscovered(expectedSize)
            return true
        }

        val partFile = File(parentDir, "${destFile.name}.part")

        // Якщо .part файл вже повністю завантажений з попереднього запуску
        if (partFile.exists() && expectedSize > 0L && partFile.length() == expectedSize) {
            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
            MapDownloadManager.recordDownloadedFile(destFile.name, System.currentTimeMillis(), destFile.length())
            onSizeDiscovered(destFile.length())
            return true
        }

        var attempts = 0
        var totalBytesFromServer = -1L

        while (attempts < 3) {
            attempts++
            if (attempts > 1) {
                var waited = 0L
                val pauseMs = 2500L
                val step = 200L
                while (waited < pauseMs) {
                    if (isCancelled()) return false
                    try { Thread.sleep(step) } catch (_: Exception) { return false }
                    waited += step
                }
            }
            if (isCancelled()) return false
            var conn: HttpURLConnection? = null
            try {
                val existingLen = if (partFile.exists()) partFile.length() else 0L
                var currentUrl = urlStr
                var redirects = 0
                while (redirects < 5) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", USER_AGENT)
                        if (existingLen > 0L) {
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
                    val cr = conn.getHeaderField("Content-Range")
                    val totalFromCr = cr?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    var serverTotalSize = totalFromCr ?: (if (expectedSize > 0L) expectedSize else -1L)
                    if (serverTotalSize <= 0L && partFile.length() > 1024L) {
                        try {
                            val headConn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "HEAD"
                                connectTimeout = 6000
                                readTimeout = 6000
                                setRequestProperty("User-Agent", USER_AGENT)
                            }
                            if (headConn.responseCode == HttpURLConnection.HTTP_OK) {
                                val hl = headConn.contentLengthLong
                                if (hl > 0L) serverTotalSize = hl
                            }
                            headConn.disconnect()
                        } catch (_: Exception) {}
                    }

                    if (serverTotalSize > 0L && partFile.length() == serverTotalSize) {
                        if (destFile.exists()) destFile.delete()
                        if (!partFile.renameTo(destFile)) {
                            partFile.copyTo(destFile, overwrite = true)
                            partFile.delete()
                        }
                        val lastMod = conn.lastModified.takeIf { it > 0L } ?: System.currentTimeMillis()
                        try { destFile.setLastModified(lastMod) } catch (_: Exception) {}
                        MapDownloadManager.recordDownloadedFile(destFile.name, lastMod, destFile.length())
                        onSizeDiscovered(destFile.length())
                        return true
                    }
                    partFile.delete()
                    conn.disconnect()
                    continue
                }

                if (allowHttpErrors && code == HttpURLConnection.HTTP_NOT_FOUND) {
                    MapDownloadManager.markSegmentUnavailable(destFile.name)
                    onSizeDiscovered(0L)
                    return true
                }

                val isResume = (code == HttpURLConnection.HTTP_PARTIAL)
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    if (allowHttpErrors) {
                        return true
                    }
                    conn.disconnect()
                    continue
                }

                val serverLen = conn.contentLengthLong
                totalBytesFromServer = if (isResume) {
                    val rangeHeader = conn.getHeaderField("Content-Range")
                    val totalFromRange = rangeHeader?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    if (totalFromRange != null && totalFromRange > 0L) {
                        totalFromRange
                    } else if (serverLen > 0L) {
                        serverLen + existingLen
                    } else -1L
                } else {
                    serverLen
                }

                val finalExpectedSize = if (totalBytesFromServer > 0L) totalBytesFromServer else expectedSize
                if (finalExpectedSize > 0L) {
                    onSizeDiscovered(finalExpectedSize)
                }

                val append = isResume && existingLen > 0L
                if (!isResume && partFile.exists()) {
                    partFile.delete()
                }

                conn.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { out ->
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

                val isComplete = if (totalBytesFromServer > 0L) {
                    partFile.length() == totalBytesFromServer
                } else if (expectedSize > 0L) {
                    partFile.length() == expectedSize
                } else {
                    partFile.length() > 0L
                }

                if (isComplete) {
                    if (destFile.exists()) destFile.delete()
                    val ok = partFile.renameTo(destFile)
                    if (!ok) {
                        partFile.copyTo(destFile, overwrite = true)
                        partFile.delete()
                    }
                    val lastMod = conn.lastModified
                    if (lastMod > 0L) {
                        try { destFile.setLastModified(lastMod) } catch (_: Exception) {}
                    }
                    MapDownloadManager.recordDownloadedFile(destFile.name, lastMod, destFile.length())
                    return true
                }
            } catch (e: Exception) {
                if (allowHttpErrors && (e is java.io.FileNotFoundException || e.message?.contains("404") == true)) {
                    MapDownloadManager.markSegmentUnavailable(destFile.name)
                    onSizeDiscovered(0L)
                    return true
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        return destFile.exists() && destFile.length() > 0L &&
                (totalBytesFromServer <= 0L || destFile.length() == totalBytesFromServer)
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
