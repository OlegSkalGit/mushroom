package com.olegskal.mushroom

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.map.MapDownloadManager
import com.olegskal.mushroom.map.OsmTileEngine
import com.olegskal.mushroom.math.ProcessedLocationMetrics
import com.olegskal.mushroom.math.GeoMath
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.network.AppUpdateManager
import com.olegskal.mushroom.service.MushroomTrackingService
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.ui.*
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.GeoDataExchange
import com.olegskal.mushroom.util.LocationUtils
import com.olegskal.mushroom.util.ServiceUtils
import java.util.Locale
import kotlin.math.*

class MushroomMapActivity : Activity(), SensorEventListener {

    companion object {
        private const val REQ_CODE_IMPORT_GPX = 1010
        const val MIN_MAP_ZOOM = 2.0f
        const val MAX_MAP_ZOOM = 18.0f
        const val MIN_BASE_ZOOM = 2
        const val MAX_BASE_ZOOM = 18
    }

    private lateinit var mapView: MushroomMapView
    private lateinit var dbHelper: DatabaseHelper

    // UI overlays
    private lateinit var tvRecordingBadge: TextView
    private lateinit var btnCenter: CenterLocationButton
    private lateinit var compassButton: CompassButton
    private lateinit var btnAddMarker: FlagMarkerButton
    private lateinit var btnRecordTrack: TrackRecordButton
    private lateinit var btnMenu: Button

    // Compass & motion sensors
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null

    private val rotMatrix = FloatArray(9)
    private val orientAngles = FloatArray(3)
    private val gravityVals = FloatArray(3)
    private val magVals = FloatArray(3)
    private var hasGravity = false
    private var hasMag = false
    private var currentFilteredAzimuth = 0f
    private var lastCompassUiTime = 0L

    private var currentMetrics: ProcessedLocationMetrics? = null
    private var isFollowLocation: Boolean = true

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isTileRedrawPending = false
    private val tileRedrawRunnable = Runnable {
        isTileRedrawPending = false
        mapView.invalidate()
    }
    private val periodicRefreshRunnable = object : Runnable {
        override fun run() {
            updateLiveStats()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.olegskal.mushroom.mushrooms.MushroomClassifierTab.isWarningDismissed = false
        OsmTileEngine.appContext = applicationContext
        if (!MushroomTrackingService.isRunning) {
            val serviceIntent = Intent(this, MushroomTrackingService::class.java)
            ServiceUtils.startTrackingService(this, serviceIntent)
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        MushroomStorageManager.initStorage()
        dbHelper = DatabaseHelper(this)
        dbHelper.restoreDataFromExternalStorageIfDbEmpty()

        AppUpdateManager.checkAndDownloadUpdate(this)
        OsmTileEngine.purgeBlockedTiles()

        AppPrefs.restoreFromExternalStorage(this)
        val hasSavedLoc = AppPrefs.hasSavedMapLocation(this)
        isFollowLocation = !hasSavedLoc && AppPrefs.getFollowUser(this)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Map View
        mapView = MushroomMapView(this)
        val initialLoc = LocationUtils.getLastKnownLocationCascade(this)
        if (initialLoc != null) {
            mapView.currentLocation = initialLoc
            if (isFollowLocation) {
                mapView.setCenter(initialLoc.latitude, initialLoc.longitude)
            }
        }
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Top Header Bar
        val topInfoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99121212"))
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnSize = (44 * resources.displayMetrics.density).toInt()
        val btnMargin = (4 * resources.displayMetrics.density).toInt()
        val ctrlParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
            setMargins(btnMargin, 0, btnMargin, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        btnMenu = Button(this).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener {
                showMainMenuDialog()
            }
        }

        btnAddMarker = FlagMarkerButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Create Marker"
            setOnClickListener {
                val loc = mapView.currentLocation
                val lat = if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) loc.latitude else mapView.mapCenterLat
                val lon = if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) loc.longitude else mapView.mapCenterLon
                val alt = loc?.altitude ?: 0.0
                ItemEditDialog.showAddMarker(
                    this@MushroomMapActivity,
                    dbHelper,
                    lat,
                    lon,
                    altitude = alt
                ) {
                    mapView.reloadMarkers()
                }
            }
        }

        btnRecordTrack = TrackRecordButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Record Track"
            val s = MushroomTrackingService.instance
            setRecording(s?.isRecording == true)
            setOnClickListener {
                toggleTrackRecording()
            }
        }

        btnCenter = CenterLocationButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Center on Current Location"
            setOnClickListener {
                isFollowLocation = true
                AppPrefs.setFollowUser(this@MushroomMapActivity, true)
                mapView.centerOnCurrentLocation()
            }
        }

        compassButton = CompassButton(this).apply {
            layoutParams = ctrlParams
            setBearing(-mapView.mapBearing)
            setOnClickListener {
                if (mapView.alignToNorth()) {
                    val msg = if (AppPrefs.getAppLang(this@MushroomMapActivity) == "uk") "Карту вирівняно на північ" else "Map aligned to North"
                    Toast.makeText(this@MushroomMapActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        topHeaderRow.addView(btnMenu)
        topHeaderRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        topHeaderRow.addView(btnAddMarker)
        topHeaderRow.addView(btnRecordTrack)
        topHeaderRow.addView(btnCenter)
        topHeaderRow.addView(compassButton)
        topInfoPanel.addView(topHeaderRow)

        tvRecordingBadge = TextView(this).apply {
            text = "⏺️ TRACK RECORDING: 0.00 km (00:00)"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, 6, 0, 0)
        }
        topInfoPanel.addView(tvRecordingBadge)

        rootLayout.addView(topInfoPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        setContentView(rootLayout)

        OsmTileEngine.appContext = applicationContext
        OsmTileEngine.onTileReadyListener = {
            if (!isTileRedrawPending) {
                isTileRedrawPending = true
                uiHandler.postDelayed(tileRedrawRunnable, 35L)
            }
        }

        handleIncomingIntent(intent)
        updateUiLanguage()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    fun openGpxFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "*/*"))
            }
            startActivityForResult(Intent.createChooser(intent, "Select GPX File"), REQ_CODE_IMPORT_GPX)
        } catch (e: Exception) {
            Toast.makeText(this, "Error selecting file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_IMPORT_GPX && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                importGpxFromUri(uri)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    importGpxFromUri(streamUri)
                    intent.action = null
                    return
                }

                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    val coords = GeoDataExchange.parseCoordinates(text)
                    if (coords != null) {
                        onReceivedCoordinates(coords.first, coords.second)
                    } else {
                        Toast.makeText(this, "No coordinates found in shared text", Toast.LENGTH_SHORT).show()
                    }
                    intent.action = null
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (uri.scheme == "geo") {
                    val coords = GeoDataExchange.parseCoordinates(uri.toString())
                    if (coords != null) {
                        onReceivedCoordinates(coords.first, coords.second)
                    }
                } else {
                    importGpxFromUri(uri)
                }
                intent.action = null
            }
        }
    }

    private fun onReceivedCoordinates(lat: Double, lon: Double) {
        isFollowLocation = false
        mapView.setCenter(lat, lon)
        ItemEditDialog.showAddMarker(
            this,
            dbHelper,
            lat,
            lon,
            initialName = "Received Marker",
            initialType = "📍 Found Location"
        ) {
            mapView.reloadMarkers()
        }
        Toast.makeText(this, String.format(Locale.US, "Received coordinates: %.5f, %.5f", lat, lon), Toast.LENGTH_LONG).show()
    }

    private fun importGpxFromUri(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
            if (stream == null) {
                Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
                return
            }
            val fileName = getFileNameFromUri(uri) ?: "Imported Track"
            val cleanTitle = fileName.removeSuffix(".gpx").removeSuffix(".xml")
            val track = stream.use { GeoDataExchange.parseGpx(it, cleanTitle) }
            if (track != null && track.points.isNotEmpty()) {
                dbHelper.insertTrack(track)
                mapView.reloadTracks()
                isFollowLocation = false
                mapView.fitTrackBounds(track)
                val km = track.distanceMeters / 1000f
                Toast.makeText(this, "Imported track \"${track.title}\" (length: ${String.format(Locale.US, "%.2f", km)} km)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "No valid GPX track found in file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLogger.log("MushroomMapActivity", "importGpxFromUri", false, "Error importing GPX: ${e.message}")
            Toast.makeText(this, "Error reading GPX: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx)
                    }
                }
            } catch (_: Exception) {}
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    fun startTrackRecording(title: String, color: Int) {
        if (!ServiceUtils.isIgnoringBatteryOptimizations(this)) {
            ServiceUtils.requestIgnoreBatteryOptimizations(this)
        }
        val s = MushroomTrackingService.instance
        if (s != null) {
            s.startTrackRecording(title, color)
            updateRecordingUi(true, 0f, 0L)
            mapView.reloadTracks()
        } else {
            val intent = Intent(this, MushroomTrackingService::class.java).apply {
                action = MushroomTrackingService.ACTION_START_RECORDING
                putExtra(MushroomTrackingService.EXTRA_TRACK_TITLE, title)
                putExtra(MushroomTrackingService.EXTRA_TRACK_COLOR, color)
            }
            startService(intent)
            updateRecordingUi(true, 0f, 0L)
        }
    }

    fun stopTrackRecording() {
        val s = MushroomTrackingService.instance
        s?.stopTrackRecording()
        updateRecordingUi(false, 0f, 0L)
        mapView.reloadTracks()
    }

    fun toggleTrackRecording() {
        val s = MushroomTrackingService.instance
        if (s?.isRecording == true) {
            stopTrackRecording()
        } else {
            ItemEditDialog.showCreateTrack(this) { title, color ->
                startTrackRecording(title, color)
            }
        }
    }

    private fun updateRecordingUi(isRec: Boolean, distMeters: Float, durationSec: Long) {
        if (::btnRecordTrack.isInitialized) {
            btnRecordTrack.setRecording(isRec)
        }
        if (isRec) {
            tvRecordingBadge.visibility = View.VISIBLE
            val km = distMeters / 1000f
            val m = durationSec / 60
            val s = durationSec % 60
            val lang = AppPrefs.getAppLang(this)
            val title = if (lang == "uk") "ЗАПИС ТРЕКУ" else "TRACK RECORDING"
            val unit = if (lang == "uk") "км" else "km"
            tvRecordingBadge.text = String.format(Locale.US, "⏺️ %s: %.2f %s (%02d:%02d)", title, km, unit, m, s)
        } else {
            tvRecordingBadge.visibility = View.GONE
        }
    }

    private fun updateLiveStats() {
        val s = MushroomTrackingService.instance
        val isRec = s?.isRecording == true
        if (::btnRecordTrack.isInitialized) {
            btnRecordTrack.setRecording(isRec)
        }
        if (isRec) {
            val dist = currentMetrics?.recordedDistanceMeters
                ?: MushroomTrackingService.lastMetrics?.recordedDistanceMeters
                ?: s?.currentActiveTrack?.distanceMeters ?: 0f
            val dur = currentMetrics?.recordedDurationSec
                ?: MushroomTrackingService.lastMetrics?.recordedDurationSec
                ?: s?.currentActiveTrack?.durationSec ?: 0L
            updateRecordingUi(true, dist, dur)
        } else {
            updateRecordingUi(false, 0f, 0L)
        }
    }

    private fun updateUiLanguage() {
        val lang = AppPrefs.getAppLang(this)
        if (::btnMenu.isInitialized) {
            btnMenu.contentDescription = if (lang == "uk") "Меню" else "Menu"
        }
        if (::btnAddMarker.isInitialized) {
            btnAddMarker.contentDescription = if (lang == "uk") "Створити маркер" else "Create Marker"
        }
        if (::btnRecordTrack.isInitialized) {
            btnRecordTrack.contentDescription = if (lang == "uk") "Запис треку" else "Record Track"
        }
        if (::btnCenter.isInitialized) {
            btnCenter.contentDescription = if (lang == "uk") "Центрувати на мені" else "Center on Current Location"
        }
        if (::compassButton.isInitialized) {
            compassButton.contentDescription = if (lang == "uk") "Компас" else "Compass"
        }
        updateLiveStats()
    }

    private fun showMainMenuDialog() {
        val dialog = Dialog(this)
        val container = UiUtils.createDarkDialogContainer(this)
        val itemParams = UiUtils.createStandardItemParams()
        val currentLang = AppPrefs.getAppLang(this)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleTv = TextView(this).apply {
            text = if (currentLang == "uk") "🌲 Меню грибника" else "🌲 Mushroom Menu"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleTv)

        val btnLangToggle = Button(this).apply {
            text = if (currentLang == "uk") "🇺🇦 UK" else "🇬🇧 EN"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (4 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val newLang = if (currentLang == "uk") "en" else "uk"
                AppPrefs.setAppLang(this@MushroomMapActivity, newLang)
                dialog.dismiss()
                updateUiLanguage()
                showMainMenuDialog()
            }
        }
        headerRow.addView(btnLangToggle)
        container.addView(headerRow)

        // 1. Markers
        val density = resources.displayMetrics.density
        val markerIcon = MarkerIconDrawable(density, Color.parseColor("#F44336"), 18)
        val btnMarkers = UiUtils.createStyledButton(
            this,
            if (currentLang == "uk") "Маркери" else "Markers",
            itemParams,
            icon = markerIcon
        ) {
            dialog.dismiss()
            val loc = mapView.currentLocation ?: currentMetrics?.location
            MarkersListDialog.show(
                this@MushroomMapActivity,
                dbHelper,
                loc?.latitude,
                loc?.longitude,
                onSelectMarker = { marker ->
                    isFollowLocation = false
                    mapView.setCenter(marker.lat, marker.lon)
                },
                onVisibilityChanged = {
                    mapView.reloadMarkers()
                }
            )
        }
        container.addView(btnMarkers)

        // 2. Tracks
        val isRec = MushroomTrackingService.instance?.isRecording == true
        val trackText = if (currentLang == "uk") {
            if (isRec) "Треки (запис...)" else "Треки"
        } else {
            if (isRec) "Tracks (recording...)" else "Tracks"
        }
        val trackIcon = TrackIconDrawable(
            density,
            if (isRec) Color.parseColor("#FF5252") else Color.parseColor("#2196F3"),
            18,
            isRecording = isRec
        )
        val btnTracks = UiUtils.createStyledButton(this, trackText, itemParams, icon = trackIcon) {
            dialog.dismiss()
            TracksListDialog.show(
                this@MushroomMapActivity,
                dbHelper,
                onSelectTrack = { track ->
                    isFollowLocation = false
                    mapView.fitTrackBounds(track)
                },
                onVisibilityChanged = {
                    mapView.reloadTracks()
                    updateLiveStats()
                }
            )
        }
        container.addView(btnTracks)

        // 3. Download offline maps
        val btnDownloadMaps = UiUtils.createStyledButton(this, if (currentLang == "uk") "🗺️ Карти" else "🗺️ Maps", itemParams) {
            dialog.dismiss()
            RegionDownloadDialog.show(this@MushroomMapActivity) {
                mapView.invalidate()
            }
        }
        container.addView(btnDownloadMaps)

        // 4. Background work without battery optimization
        if (!ServiceUtils.isIgnoringBatteryOptimizations(this)) {
            val btnBattery = UiUtils.createStyledButton(this, if (currentLang == "uk") "🔋 Робота у фоні (без обмежень)" else "🔋 Background Run (Unrestricted)", itemParams) {
                dialog.dismiss()
                ServiceUtils.requestIgnoreBatteryOptimizations(this@MushroomMapActivity)
            }
            container.addView(btnBattery)
        }

        // 5. Mushrooms
        val btnMushrooms = UiUtils.createStyledButton(this, if (currentLang == "uk") "🍄 Гриби" else "🍄 Mushrooms", itemParams) {
            dialog.dismiss()
            startActivity(Intent(this@MushroomMapActivity, com.olegskal.mushroom.mushrooms.MushroomActivity::class.java))
        }
        container.addView(btnMushrooms)

        // Divider before Help
        container.addView(UiUtils.createDialogDivider(this))

        // Help
        val btnHelp = UiUtils.createStyledButton(this, if (currentLang == "uk") "ℹ️ Довідка" else "ℹ️ Help", itemParams) {
            dialog.dismiss()
            showHelpDialog()
        }
        container.addView(btnHelp)

        // Divider before Exit
        container.addView(UiUtils.createDialogDivider(this))

        // 6. Exit
        val btnQuit = UiUtils.createStyledButton(this, if (currentLang == "uk") "🚪 Вихід" else "🚪 Exit", itemParams) {
            dialog.dismiss()
            val s = MushroomTrackingService.instance
            if (s?.isRecording == true) {
                s.stopTrackRecording()
            }
            val stopIntent = Intent(this@MushroomMapActivity, MushroomTrackingService::class.java).apply {
                action = MushroomTrackingService.ACTION_STOP_SERVICE
            }
            startService(stopIntent)
            com.olegskal.mushroom.mushrooms.MushroomClassifierTab.isWarningDismissed = false
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }
        container.addView(btnQuit)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showHelpDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val currentLang = AppPrefs.getAppLang(this)
        val isUk = currentLang == "uk"
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16221C"))
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
        }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "v1.0"
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = if (isUk) "ℹ️ Довідка користувача" else "ℹ️ User Guide & Help"
            setTextColor(Color.WHITE)
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
        }
        val tvVersion = TextView(this).apply {
            text = "Mushroom $versionName"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        titleBox.addView(tvTitle)
        titleBox.addView(tvVersion)
        header.addView(titleBox)

        val btnClose = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(btnClose)
        root.addView(header)

        // Scrollable content
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
        }

        fun addSection(icon: String, title: String, desc: String, iconDrawable: Drawable? = null) {
            val secTitle = TextView(this).apply {
                if (iconDrawable != null) {
                    val sz = (16 * density).toInt()
                    iconDrawable.setBounds(0, 0, sz, sz)
                    val ssb = android.text.SpannableStringBuilder("  ").append(title)
                    ssb.setSpan(CenteredImageSpan(iconDrawable), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = ssb
                } else {
                    text = "$icon $title"
                }
                setTextColor(Color.parseColor("#10B981"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (10 * density).toInt(), 0, (3 * density).toInt())
            }
            val secDesc = TextView(this).apply {
                text = desc
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 12.5f
                setLineSpacing(3f, 1.15f)
            }
            content.addView(secTitle)
            content.addView(secDesc)
        }

        if (isUk) {
            addSection(
                "🗺️", "Офлайн-карта та навігація",
                "• Повний автономний перегляд векторних карт OpenStreetMap без підключення до інтернету.\n" +
                "• Масштабування двома пальцями (pinch-to-zoom), обертання карти жестом або за компасом.\n" +
                "• Кнопка «Центрувати на мені» миттєво центрує екран на поточному GPS-місцезнаходженні.\n" +
                "• Кнопка компаса вказує напрямок на північ; натискання на неї вирівнює карту на північ."
            )
            addSection(
                "", "Грибні точки та маркери",
                "• Додавання нової точки на карті: тривале натискання (довгий тап) або швидкий подвійний тап на потрібному місці.\n" +
                "• Також точку можна створити за поточними GPS-координатами через круглу плаваючу кнопку з прапорцем.\n" +
                "• Редагування назви, висоти, нотаток та вибір типу гриба (білий, лисичка, опеньок, маслюк тощо).\n" +
                "• Список усіх маркерів у меню: фільтрація за назвою, сортування за відстанню, приховування/показ та експорт у GPX.",
                MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )
            addSection(
                "", "Запис та аналіз GPS-треків",
                "• Надійний фоновий запис маршруту навіть при заблокованому екрані смартфона у кишені.\n" +
                "• Інформаційний бейдж у верхній частині показує подолану відстань і точний час походу.\n" +
                "• Автоматичне збереження у вбудовану базу даних та експорт треків у стандартний формат GPX.\n" +
                "• Керування треками: перегляд маршрутів на карті, центрування на межах треку та статистика.",
                TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )
            addSection(
                "📥", "Завантаження офлайн-карт",
                "• Попереднє завантаження карт областей України або інших країн світу перед виходом у ліс.\n" +
                "• 100% захист від втрати орієнтації за повної відсутності стільникового зв'язку."
            )
            addSection(
                "🔬", "AI Визначник грибів (Нейромережа)",
                "• Розпізнавання грибів за фотографіями автономною нейромережею MobileNet ONNX.\n" +
                "• Підтримка до 3-х різних фото (капелюшок, ніжка, зріз) для комбінованого точного аналізу.\n" +
                "• Визначення Top-5 найімовірніших видів із зазначенням відсотка впевненості та статусу їстівності.\n" +
                "• Швидкий перехід з картки результату до пошуку в енциклопедії."
            )
            addSection(
                "📖", "Енциклопедія та безпека",
                "• Детальні картки видів з фотографіями та морфологічними характеристиками.\n" +
                "• Єдині статуси їстівності: 🟢 Їстівний, 🟡 Умовно-їстівний, 🟠 Отруйний, 🔴 Смертельно отруйний.\n" +
                "• Попередження про небезпечні та смертельні двійники (бліда поганка, галерина тощо).\n" +
                "• Зручні фільтри за їстівністю та типом гіменофора (трубчасті / пластинчасті)."
            )
            addSection(
                "🔋", "Оптимізація батареї",
                "• Для безперервного фонового запису треків рекомендується вимкнути оптимізацію батареї у меню."
            )
        } else {
            addSection(
                "🗺️", "Offline Map & Navigation",
                "• Offline OpenStreetMap rendering with zero cellular connection needed.\n" +
                "• Smooth pinch-to-zoom, manual or compass map rotation, follow-user positioning.\n" +
                "• Center button instantly snaps the camera to your real-time GPS coordinates.\n" +
                "• Compass button points north; tapping aligns the map view to north."
            )
            addSection(
                "", "Mushroom Markers & Waypoints",
                "• Add new marker: long press or quick double tap anywhere on the map.\n" +
                "• Or tap the floating flag button to pin your current GPS coordinates.\n" +
                "• Edit marker name, notes, elevation, and assign mushroom species icons.\n" +
                "• Markers manager: search filter, proximity sorting, visibility toggle, GPX export.",
                MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )
            addSection(
                "", "Track Recording & GPS Logging",
                "• Reliable background track recording even with screen turned off.\n" +
                "• Live status badge displays elapsed distance and duration.\n" +
                "• Automatic local database storage and full GPX export support.\n" +
                "• Inspect routes, auto-fit track boundaries, and track management.",
                TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )
            addSection(
                "📥", "Offline Map Downloads",
                "• Download regional map packages over Wi-Fi before heading into deep woods.\n" +
                "• Guaranteed orientation with zero cellular reception."
            )
            addSection(
                "🔬", "AI Mushroom Classifier",
                "• Identify mushrooms offline using an embedded MobileNet ONNX model.\n" +
                "• Combine up to 3 photos (cap, stem, slice) for ensemble confidence analysis.\n" +
                "• View Top-5 species candidates with confidence scores and edibility badges.\n" +
                "• Tap any result card to jump directly into the encyclopedia."
            )
            addSection(
                "📖", "Encyclopedia & Safety Guide",
                "• Comprehensive species cards with high-res photos and morphology.\n" +
                "• Standardized edibility badges: 🟢 Edible, 🟡 Cond. Edible, 🟠 Toxic, 🔴 Deadly Toxic.\n" +
                "• Lookalike safety alerts for fatal lookalikes (Death Cap, Funeral Bell, etc.).\n" +
                "• Filter by edibility level and hymenophore structure (tubes / gills)."
            )
            addSection(
                "🔋", "Battery Optimization",
                "• Disable battery optimization via the menu for uninterrupted background recording."
            )
        }

        // Repository Link section at the bottom
        val repoSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Color.parseColor("#1F3327"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, (14 * density).toInt(), 0, (6 * density).toInt())
            }
        }

        val tvRepoTitle = TextView(this).apply {
            text = if (isUk) "🌐 Вихідний код проєкту (GitHub):" else "🌐 Open Source Repository (GitHub):"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
        }
        val tvRepoLink = TextView(this).apply {
            text = "https://github.com/OlegSkalGit/mushroom"
            setTextColor(Color.parseColor("#60A5FA"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (4 * density).toInt(), 0, 0)
            isClickable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/OlegSkalGit/mushroom")))
                } catch (e: Exception) {
                    Toast.makeText(this@MushroomMapActivity, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
        }
        repoSection.addView(tvRepoTitle)
        repoSection.addView(tvRepoLink)
        content.addView(repoSection)

        scroll.addView(content)
        root.addView(scroll)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateUiLanguage()
        mapView.reloadMarkers()
        mapView.reloadTracks()
        updateLiveStats()

        MushroomTrackingService.serviceStateListener = { isRunning ->
            if (!isRunning) runOnUiThread { finish() }
            else runOnUiThread { updateLiveStats(); mapView.reloadTracks() }
        }

        if (::compassButton.isInitialized) {
            compassButton.setBearing(-mapView.mapBearing)
        }

        // Register hardware orientation sensors for instant, real-time compass
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        MushroomTrackingService.metricsListener = { metrics ->
            runOnUiThread {
                currentMetrics = metrics
                mapView.updateLocationMetrics(metrics)
                updateLiveStats()
            }
        }

        uiHandler.post(periodicRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager.unregisterListener(this)
        uiHandler.removeCallbacks(periodicRefreshRunnable)
        MushroomTrackingService.metricsListener = null
        MushroomTrackingService.serviceStateListener = null
        if (::mapView.isInitialized) {
            mapView.saveMapState()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) {
            mapView.saveMapState()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        com.olegskal.mushroom.mushrooms.MushroomClassifierTab.isWarningDismissed = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        var newAzimuth: Float? = null

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientAngles)
            var az = Math.toDegrees(orientAngles[0].toDouble()).toFloat()
            if (az < 0f) az += 360f
            newAzimuth = az
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravityVals, 0, 3)
            hasGravity = true
            if (hasMag && rotationVectorSensor == null) {
                if (SensorManager.getRotationMatrix(rotMatrix, null, gravityVals, magVals)) {
                    SensorManager.getOrientation(rotMatrix, orientAngles)
                    var az = Math.toDegrees(orientAngles[0].toDouble()).toFloat()
                    if (az < 0f) az += 360f
                    newAzimuth = az
                }
            }
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magVals, 0, 3)
            hasMag = true
            if (hasGravity && rotationVectorSensor == null) {
                if (SensorManager.getRotationMatrix(rotMatrix, null, gravityVals, magVals)) {
                    SensorManager.getOrientation(rotMatrix, orientAngles)
                    var az = Math.toDegrees(orientAngles[0].toDouble()).toFloat()
                    if (az < 0f) az += 360f
                    newAzimuth = az
                }
            }
        }

        if (newAzimuth != null) {
            onCompassAzimuthChanged(newAzimuth)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onCompassAzimuthChanged(targetAzimuth: Float) {
        val now = SystemClock.uptimeMillis()
        var diff = targetAzimuth - currentFilteredAzimuth
        while (diff < -180f) diff += 360f
        while (diff > 180f) diff -= 360f

        currentFilteredAzimuth = (currentFilteredAzimuth + diff * 0.35f + 360f) % 360f

        if (now - lastCompassUiTime >= 33L) {
            lastCompassUiTime = now
            mapView.setCompassHeading(currentFilteredAzimuth)
        }
    }

    // --- INNER MAP CANVAS VIEW ---
    @SuppressLint("ClickableViewAccessibility")
    inner class MushroomMapView(context: Context) : View(context) {

        var mapCenterLat: Double = 50.4501
        var mapCenterLon: Double = 30.5234
        var zoomLevel: Float = 12.0f

        var currentLocation: Location? = null
        private var compassHeading: Float? = null
        private var trajectoryBearing: Float = 0f

        private var markersList: List<MushroomMarker> = emptyList()
        private var tracksList: List<MushroomTrack> = emptyList()

        // Drawing paints
        private val bgPaint = Paint().apply { color = Color.parseColor("#1b221b") }
        private val userCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }
        private val userRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2200E5FF")
            style = Paint.Style.FILL
        }
        private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6600E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val headingArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            style = Paint.Style.FILL
        }
        private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.WHITE)
        }
        private val markerPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val liveTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9800")
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val tileGridPaint = Paint().apply {
            color = Color.parseColor("#253025")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private val tileBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }
        private val tileDstRect = RectF()
        private val tileSrcRect = Rect()
        private val quadDstRect = RectF()
        private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8000E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val anchorCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B300E5FF")
            style = Paint.Style.FILL
        }
        private val anchorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6600E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        var mapBearing: Float = 0f

        // Gesture handling
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isDragging = false
        private var isMultiTouch = false

        private var prevDist = 0f
        private var prevAngle = 0f
        private var prevFocusX = 0f
        private var prevFocusY = 0f

        // Double-tap & drag gesture (one-finger pan, zoom & rotate with fixed anchor)
        private var lastTapUpTime = 0L
        private var lastTapUpX = 0f
        private var lastTapUpY = 0f
        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var isDoubleTapDrag = false
        private var isDoubleTapCandidate = false
        private var hasDoubleTapMoved = false
        private var anchorX = 0f
        private var anchorY = 0f

        private var isLongPressTriggered = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val longPressSlop = maxOf(touchSlop * 2.2f, 24f * resources.displayMetrics.density)
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        private fun handlePointActionAt(screenX: Float, screenY: Float, isFromDoubleTap: Boolean) {
            val hitMarker = findMarkerAt(screenX, screenY, 32f * resources.displayMetrics.density)
            if (hitMarker != null) {
                if (isFromDoubleTap) {
                    ItemEditDialog.showEditMarker(this@MushroomMapActivity, dbHelper, hitMarker) {
                        reloadMarkers()
                    }
                } else {
                    val lang = AppPrefs.getAppLang(this@MushroomMapActivity)
                    val title = if (lang == "uk") "Видалити маркер?" else "Delete marker?"
                    val msg = if (lang == "uk") "Видалити \"${hitMarker.name}\"?" else "Delete \"${hitMarker.name}\"?"
                    val delBtn = if (lang == "uk") "Видалити" else "Delete"
                    val cancelBtn = if (lang == "uk") "Скасувати" else "Cancel"
                    AlertDialog.Builder(this@MushroomMapActivity)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton(delBtn) { _, _ ->
                            dbHelper.deleteMarker(hitMarker.id)
                            reloadMarkers()
                            val tMsg = if (lang == "uk") "Маркер \"${hitMarker.name}\" видалено" else "Marker \"${hitMarker.name}\" deleted"
                            Toast.makeText(this@MushroomMapActivity, tMsg, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(cancelBtn, null)
                        .show()
                }
            } else {
                val coords = screenToLatLon(screenX, screenY)
                ItemEditDialog.showAddMarker(
                    this@MushroomMapActivity,
                    dbHelper,
                    coords.first,
                    coords.second,
                    altitude = 0.0
                ) {
                    reloadMarkers()
                }
            }
        }

        private val longPressRunnable = Runnable {
            if (!isDragging && !isMultiTouch && !isDoubleTapDrag) {
                isLongPressTriggered = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                handlePointActionAt(downX, downY, isFromDoubleTap = false)
            }
        }

        init {
            isHapticFeedbackEnabled = true
            if (AppPrefs.hasSavedMapLocation(context)) {
                mapCenterLat = AppPrefs.getMapLat(context)
                mapCenterLon = AppPrefs.getMapLon(context)
            }
            val savedZoom = AppPrefs.getMapZoom(context)
            zoomLevel = savedZoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
            val savedBearing = AppPrefs.getMapBearing(context)
            mapBearing = (savedBearing % 360f + 360f) % 360f
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(longPressRunnable)
        }

        fun saveMapState() {
            AppPrefs.setMapState(context, mapCenterLat, mapCenterLon, zoomLevel, mapBearing)
        }

        fun screenToLatLon(touchX: Float, touchY: Float): Pair<Double, Double> {
            val cx = width / 2f
            val cy = height / 2f
            val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
            val scale = 2.0f.pow(zoomLevel - baseZoom)
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)

            val rad = Math.toRadians(mapBearing.toDouble())
            val cosR = cos(rad)
            val sinR = sin(rad)

            val screenDx = (touchX - cx).toDouble()
            val screenDy = (touchY - cy).toDouble()

            val rotDx = screenDx * cosR - screenDy * sinR
            val rotDy = screenDx * sinR + screenDy * cosR

            val targetWorldX = centerWorld.first + rotDx / scale
            val targetWorldY = centerWorld.second + rotDy / scale
            return OsmTileEngine.worldPixelToLatLon(targetWorldX, targetWorldY, baseZoom)
        }

        fun latLonToScreen(lat: Double, lon: Double): Pair<Float, Float> {
            val cx = width / 2f
            val cy = height / 2f
            val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
            val scale = 2.0f.pow(zoomLevel - baseZoom)
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val targetWorld = OsmTileEngine.latLonToWorldPixel(lat, lon, baseZoom)

            val scaledDx = (targetWorld.first - centerWorld.first) * scale
            val scaledDy = (targetWorld.second - centerWorld.second) * scale

            val rad = Math.toRadians(mapBearing.toDouble())
            val cosR = cos(rad)
            val sinR = sin(rad)

            val screenDx = scaledDx * cosR + scaledDy * sinR
            val screenDy = -scaledDx * sinR + scaledDy * cosR

            return Pair((cx + screenDx).toFloat(), (cy + screenDy).toFloat())
        }

        fun findMarkerAt(touchX: Float, touchY: Float, maxDistPx: Float): MushroomMarker? {
            var closest: MushroomMarker? = null
            var minDist = maxDistPx

            for (marker in markersList) {
                if (!marker.isVisible) continue
                val (mx, my) = latLonToScreen(marker.lat, marker.lon)
                val d = hypot((touchX - mx).toDouble(), (touchY - my).toDouble()).toFloat()
                val dHead = hypot((touchX - mx).toDouble(), (touchY - (my - 18f)).toDouble()).toFloat()
                val dist = minOf(d, dHead)
                if (dist < minDist) {
                    minDist = dist
                    closest = marker
                }
            }
            return closest
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val count = event.pointerCount
            val density = resources.displayMetrics.density

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.uptimeMillis()
                    val tapDeltaTime = now - lastTapUpTime
                    val tapDeltaDist = hypot((event.x - lastTapUpX).toDouble(), (event.y - lastTapUpY).toDouble()).toFloat()

                    isLongPressTriggered = false
                    removeCallbacks(longPressRunnable)

                    // Double-tap criteria: time delta in [40ms, 380ms] and distance delta < 45dp
                    if (lastTapUpTime > 0L && tapDeltaTime in 40L..380L && tapDeltaDist < 45f * density) {
                        isDoubleTapDrag = true
                        isDoubleTapCandidate = true
                        hasDoubleTapMoved = false
                        isDragging = false
                        isMultiTouch = false
                        anchorX = lastTapUpX
                        anchorY = lastTapUpY
                        lastTouchX = event.x
                        lastTouchY = event.y
                        prevDist = hypot((event.x - anchorX).toDouble(), (event.y - anchorY).toDouble()).toFloat()
                        prevAngle = Math.toDegrees(atan2((event.y - anchorY).toDouble(), (event.x - anchorX).toDouble())).toFloat()
                        lastTapUpTime = 0L
                    } else {
                        isDoubleTapDrag = false
                        isDoubleTapCandidate = false
                        hasDoubleTapMoved = false
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isDragging = false
                        isMultiTouch = false
                        postDelayed(longPressRunnable, longPressTimeout)
                    }
                    downTime = now
                    downX = event.x
                    downY = event.y
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    removeCallbacks(longPressRunnable)
                    if (isDoubleTapDrag) {
                        isDoubleTapDrag = false
                        isDoubleTapCandidate = false
                    }
                    if (count >= 2) {
                        isMultiTouch = true
                        isDragging = false
                        val x0 = event.getX(0)
                        val y0 = event.getY(0)
                        val x1 = event.getX(1)
                        val y1 = event.getY(1)
                        prevFocusX = (x0 + x1) / 2f
                        prevFocusY = (y0 + y1) / 2f
                        prevDist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat().coerceAtLeast(20f)
                        prevAngle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val moveDist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()

                    // Only cancel long-press if movement exceeds generous longPressSlop (tolerates micro-shifts)
                    if (moveDist > longPressSlop) {
                        removeCallbacks(longPressRunnable)
                    }
                    if (isLongPressTriggered) {
                        return true
                    }

                    if (isDoubleTapDrag) {
                        if (!hasDoubleTapMoved && moveDist > touchSlop) {
                            hasDoubleTapMoved = true
                        }
                        if (hasDoubleTapMoved) {
                            isFollowLocation = false
                            AppPrefs.setFollowUser(context, false)
                            val curX = event.x
                            val curY = event.y
                            val dx = curX - lastTouchX
                            val dy = curY - lastTouchY

                            // 1. Pan with moving touch
                            if (abs(dx) > 1f || abs(dy) > 1f) {
                                hasDoubleTapMoved = true
                                panMap(dx, dy)
                                lastTouchX = curX
                                lastTouchY = curY
                            }

                            // 2. Zoom and Rotate relative to anchor
                            val curDist = hypot((curX - anchorX).toDouble(), (curY - anchorY).toDouble()).toFloat()
                            val minGestureDist = 20f * density

                            if (curDist >= minGestureDist) {
                                hasDoubleTapMoved = true
                                if (prevDist >= minGestureDist) {
                                    // Smooth zoom
                                    val factor = curDist / prevDist
                                    val zoomDelta = (ln(factor.toDouble()) / ln(2.0)).toFloat()
                                    zoomLevel = (zoomLevel + zoomDelta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)

                                    // Rotation
                                    val curAngle = Math.toDegrees(atan2((curY - anchorY).toDouble(), (curX - anchorX).toDouble())).toFloat()
                                    var deltaAngle = curAngle - prevAngle
                                    while (deltaAngle < -180f) deltaAngle += 360f
                                    while (deltaAngle > 180f) deltaAngle -= 360f

                                    if (abs(deltaAngle) > 0.3f) {
                                        mapBearing = (mapBearing - deltaAngle) % 360f
                                        if (mapBearing < 0f) mapBearing += 360f
                                        prevAngle = curAngle
                                        compassButton.setBearing(-mapBearing)
                                    }
                                } else {
                                    prevAngle = Math.toDegrees(atan2((curY - anchorY).toDouble(), (curX - anchorX).toDouble())).toFloat()
                                }
                                prevDist = curDist
                            }
                            invalidate()
                        }
                    } else if (count >= 2 && isMultiTouch) {
                        val x0 = event.getX(0)
                        val y0 = event.getY(0)
                        val x1 = event.getX(1)
                        val y1 = event.getY(1)
                        val focusX = (x0 + x1) / 2f
                        val focusY = (y0 + y1) / 2f
                        val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat().coerceAtLeast(20f)
                        val angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

                        isFollowLocation = false
                        AppPrefs.setFollowUser(context, false)

                        // 1. Two-finger Pan
                        val dFocusX = focusX - prevFocusX
                        val dFocusY = focusY - prevFocusY
                        if (abs(dFocusX) > 1f || abs(dFocusY) > 1f) {
                            panMap(dFocusX, dFocusY)
                            prevFocusX = focusX
                            prevFocusY = focusY
                        }

                        // 2. Rotation
                        var deltaAngle = angle - prevAngle
                        while (deltaAngle < -180f) deltaAngle += 360f
                        while (deltaAngle > 180f) deltaAngle -= 360f

                        if (abs(deltaAngle) > 0.3f) {
                            mapBearing = (mapBearing - deltaAngle) % 360f
                            if (mapBearing < 0f) mapBearing += 360f
                            prevAngle = angle
                            compassButton.setBearing(-mapBearing)
                        }

                        // 3. Smooth Continuous Pinch Zoom
                        if (prevDist > 20f && dist > 20f) {
                            val factor = dist / prevDist
                            val zoomDelta = (ln(factor.toDouble()) / ln(2.0)).toFloat()
                            zoomLevel = (zoomLevel + zoomDelta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                            prevDist = dist
                        }

                        invalidate()
                    } else if (count == 1 && !isMultiTouch && !isDoubleTapDrag) {
                        // Start dragging only once movement exceeds longPressSlop to protect long-press from micro-shifts
                        if (!isDragging && moveDist > longPressSlop) {
                            isDragging = true
                            removeCallbacks(longPressRunnable)
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        if (isDragging) {
                            val dx = event.x - lastTouchX
                            val dy = event.y - lastTouchY
                            if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                                isFollowLocation = false
                                AppPrefs.setFollowUser(context, false)
                                panMap(dx, dy)
                                lastTouchX = event.x
                                lastTouchY = event.y
                            }
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (count <= 2) {
                        isMultiTouch = false
                        val remIdx = if (event.actionIndex == 0) 1 else 0
                        if (remIdx < count) {
                            lastTouchX = event.getX(remIdx)
                            lastTouchY = event.getY(remIdx)
                        }
                        saveMapState()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (isLongPressTriggered) {
                        isLongPressTriggered = false
                        return true
                    }
                    if (isDoubleTapDrag) {
                        isDoubleTapDrag = false
                        val upDist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                        if (isDoubleTapCandidate && !hasDoubleTapMoved && upDist <= touchSlop * 1.5f) {
                            // Confirmed double-tap without drag -> add or edit marker!
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            handlePointActionAt(event.x, event.y, isFromDoubleTap = true)
                        }
                        isDoubleTapCandidate = false
                        hasDoubleTapMoved = false
                        lastTapUpTime = 0L
                        saveMapState()
                        invalidate()
                    } else {
                        isDragging = false
                        isMultiTouch = false
                        val now = SystemClock.uptimeMillis()
                        val tapDist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                        if (now - downTime < 320L && tapDist <= longPressSlop) {
                            lastTapUpTime = now
                            lastTapUpX = event.x
                            lastTapUpY = event.y
                        } else {
                            lastTapUpTime = 0L
                        }
                        saveMapState()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    isLongPressTriggered = false
                    isDragging = false
                    isMultiTouch = false
                    isDoubleTapDrag = false
                    isDoubleTapCandidate = false
                    hasDoubleTapMoved = false
                    lastTapUpTime = 0L
                    saveMapState()
                }
            }
            return true
        }

        private var alignAnimator: ValueAnimator? = null

        fun alignToNorth(): Boolean {
            val normBearing = (mapBearing % 360f + 360f) % 360f
            if (normBearing < 0.1f || normBearing > 359.9f) {
                mapBearing = 0f
                compassButton.setBearing(0f)
                saveMapState()
                invalidate()
                return false
            }
            var diff = 0f - normBearing
            while (diff < -180f) diff += 360f
            while (diff > 180f) diff -= 360f
            val start = normBearing
            val end = start + diff

            alignAnimator?.cancel()
            alignAnimator = ValueAnimator.ofFloat(start, end).apply {
                duration = 260L
                addUpdateListener { va ->
                    val v = va.animatedValue as Float
                    mapBearing = (v % 360f + 360f) % 360f
                    compassButton.setBearing(-mapBearing)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mapBearing = 0f
                        compassButton.setBearing(0f)
                        invalidate()
                        saveMapState()
                    }
                })
                start()
            }
            return true
        }

        fun reloadMarkers() {
            markersList = dbHelper.getAllMarkers()
            invalidate()
        }

        fun reloadTracks() {
            tracksList = dbHelper.getAllTracks()
            invalidate()
        }

        fun setCenter(lat: Double, lon: Double) {
            mapCenterLat = lat
            mapCenterLon = lon
            invalidate()
            saveMapState()
        }

        fun centerOnCurrentLocation() {
            currentLocation?.let {
                mapCenterLat = it.latitude
                mapCenterLon = it.longitude
                invalidate()
                saveMapState()
            }
        }

        fun zoomIn() {
            if (zoomLevel < MAX_MAP_ZOOM) {
                zoomLevel = (zoomLevel + 1.0f).coerceAtMost(MAX_MAP_ZOOM)
                saveMapState()
                invalidate()
            }
        }

        fun zoomOut() {
            if (zoomLevel > MIN_MAP_ZOOM) {
                zoomLevel = (zoomLevel - 1.0f).coerceAtLeast(MIN_MAP_ZOOM)
                saveMapState()
                invalidate()
            }
        }

        fun fitTrackBounds(track: MushroomTrack) {
            if (track.points.isEmpty()) return
            var minLat = 90.0
            var maxLat = -90.0
            var minLon = 180.0
            var maxLon = -180.0
            for (p in track.points) {
                minLat = minOf(minLat, p.lat)
                maxLat = maxOf(maxLat, p.lat)
                minLon = minOf(minLon, p.lon)
                maxLon = maxOf(maxLon, p.lon)
            }
            mapCenterLat = (minLat + maxLat) / 2.0
            mapCenterLon = (minLon + maxLon) / 2.0
            zoomLevel = 13.0f
            saveMapState()
            invalidate()
        }

        private fun panMap(dxPx: Float, dyPx: Float) {
            val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
            val scale = 2.0f.pow(zoomLevel - baseZoom)
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val rad = Math.toRadians(mapBearing.toDouble())
            val cosR = cos(rad)
            val sinR = sin(rad)
            val rotatedDx = (dxPx * cosR - dyPx * sinR) / scale
            val rotatedDy = (dxPx * sinR + dyPx * cosR) / scale

            val newWorldX = centerWorld.first - rotatedDx
            val newWorldY = centerWorld.second - rotatedDy
            val newCoords = OsmTileEngine.worldPixelToLatLon(newWorldX, newWorldY, baseZoom)
            mapCenterLat = newCoords.first
            mapCenterLon = newCoords.second
            invalidate()
        }

        fun setCompassHeading(az: Float) {
            val prev = compassHeading ?: -999f
            var diff = kotlin.math.abs(az - prev)
            if (diff > 180f) diff = 360f - diff
            compassHeading = az
            if (diff >= 0.8f) {
                invalidate()
            }
        }

        fun updateLocationMetrics(metrics: ProcessedLocationMetrics) {
            val locChanged = currentLocation?.latitude != metrics.location.latitude ||
                    currentLocation?.longitude != metrics.location.longitude
            val headingDiff = kotlin.math.abs((compassHeading ?: -999f) - metrics.compassHeading)
            val trajectoryDiff = kotlin.math.abs(trajectoryBearing - metrics.trajectoryBearing)

            currentLocation = metrics.location
            compassHeading = metrics.compassHeading
            trajectoryBearing = metrics.trajectoryBearing
            if (isFollowLocation) {
                mapCenterLat = metrics.location.latitude
                mapCenterLon = metrics.location.longitude
            }
            if (locChanged || headingDiff >= 1.5f || trajectoryDiff >= 1.5f) {
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(bgPaint.color)

            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
            val scale = 2.0f.pow(zoomLevel - baseZoom)

            canvas.save()
            if (mapBearing != 0f) {
                canvas.rotate(-mapBearing, cx, cy)
            }
            if (scale != 1.0f) {
                canvas.scale(scale, scale, cx, cy)
            }

            // 1. Draw OSM Map Tiles
            drawOsmTiles(canvas, cx, cy, baseZoom, scale)

            // 2. Draw Recorded Tracks
            drawTracks(canvas, cx, cy, baseZoom, scale)

            // 3. Draw Markers
            drawMarkers(canvas, cx, cy, baseZoom, scale)

            // 4. Draw Current Position
            drawUserLocation(canvas, cx, cy, baseZoom, scale)

            canvas.restore()

            // 5. Draw Double-tap & Drag Visual Pivot Lever
            if (isDoubleTapDrag) {
                canvas.drawLine(anchorX, anchorY, lastTouchX, lastTouchY, anchorLinePaint)
                canvas.drawCircle(anchorX, anchorY, 18f, anchorPaint)
                canvas.drawCircle(anchorX, anchorY, 5f, anchorCenterPaint)
            }
        }

        private fun drawOsmTiles(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val tileSize = OsmTileEngine.TILE_SIZE

            val maxRadius = (hypot(cx.toDouble(), cy.toDouble()) / scale).toFloat() + tileSize
            val startPx = centerWorld.first - maxRadius
            val startPy = centerWorld.second - maxRadius
            val endPx = centerWorld.first + maxRadius
            val endPy = centerWorld.second + maxRadius

            val minTileX = floor(startPx / tileSize).toInt()
            val maxTileX = ceil(endPx / tileSize).toInt()
            val minTileY = floor(startPy / tileSize).toInt()
            val maxTileY = ceil(endPy / tileSize).toInt()

            val maxCoord = 1 shl baseZoom

            for (tx in minTileX..maxTileX) {
                val clampedTx = (tx % maxCoord + maxCoord) % maxCoord
                for (ty in minTileY..maxTileY) {
                    if (ty < 0 || ty >= maxCoord) continue

                    val screenLeft = (tx * tileSize - centerWorld.first + cx).toFloat()
                    val screenTop = (ty * tileSize - centerWorld.second + cy).toFloat()
                    tileDstRect.set(screenLeft, screenTop, screenLeft + tileSize + 0.6f, screenTop + tileSize + 0.6f)

                    val bmp = OsmTileEngine.getTile(baseZoom, clampedTx, ty)
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, null, tileDstRect, tileBitmapPaint)
                    } else {
                        var drawnFallback = false

                        // 1. Overscaling fallback: parent tile from lower zoom levels (scaled up)
                        val parent = OsmTileEngine.findParentTile(baseZoom, clampedTx, ty, MIN_BASE_ZOOM)
                        if (parent != null) {
                            val subSize = parent.bitmap.width.toFloat() / (1 shl parent.zoomDiff)
                            val sLeft = (parent.subX * subSize).toInt()
                            val sTop = (parent.subY * subSize).toInt()
                            val sRight = ((parent.subX + 1) * subSize).toInt().coerceAtMost(parent.bitmap.width)
                            val sBottom = ((parent.subY + 1) * subSize).toInt().coerceAtMost(parent.bitmap.height)
                            tileSrcRect.set(sLeft, sTop, sRight, sBottom)
                            canvas.drawBitmap(parent.bitmap, tileSrcRect, tileDstRect, tileBitmapPaint)
                            drawnFallback = true
                        }

                        // 2. Underscaling fallback: child tiles from higher zoom levels (quadrants)
                        if (baseZoom < MAX_BASE_ZOOM) {
                            val childZoom = baseZoom + 1
                            val halfW = tileDstRect.width() / 2f
                            val halfH = tileDstRect.height() / 2f
                            for (cxIdx in 0..1) {
                                for (cyIdx in 0..1) {
                                    val childBmp = OsmTileEngine.getChildTile(
                                        childZoom,
                                        (clampedTx shl 1) + cxIdx,
                                        (ty shl 1) + cyIdx
                                    )
                                    if (childBmp != null) {
                                        val qLeft = tileDstRect.left + cxIdx * halfW
                                        val qTop = tileDstRect.top + cyIdx * halfH
                                        quadDstRect.set(qLeft, qTop, qLeft + halfW, qTop + halfH)
                                        canvas.drawBitmap(childBmp, null, quadDstRect, tileBitmapPaint)
                                        drawnFallback = true
                                    }
                                }
                            }
                        }

                        if (!drawnFallback) {
                            canvas.drawRect(screenLeft, screenTop, screenLeft + tileSize, screenTop + tileSize, tileGridPaint)
                        }
                    }
                }
            }
        }

        private fun drawTracks(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            trackPaint.strokeWidth = 8f / scale
            liveTrackPaint.strokeWidth = 9f / scale

            val s = MushroomTrackingService.instance
            val activeTrackId = if (s?.isRecording == true) s.currentActiveTrack?.id else null

            for (track in tracksList) {
                if (track.id == activeTrackId) continue
                if (!track.isVisible || track.points.size < 2) continue
                trackPaint.color = track.color

                var prevX = -1f
                var prevY = -1f
                for (p in track.points) {
                    val wp = OsmTileEngine.latLonToWorldPixel(p.lat, p.lon, baseZoom)
                    val sx = (wp.first - centerWorld.first + cx).toFloat()
                    val sy = (wp.second - centerWorld.second + cy).toFloat()

                    if (prevX >= 0f && prevY >= 0f) {
                        canvas.drawLine(prevX, prevY, sx, sy, trackPaint)
                    }
                    prevX = sx
                    prevY = sy
                }
            }

            // Draw Live Active Track
            if (s?.isRecording == true) {
                val active = s.currentActiveTrack
                if (active != null && active.points.size >= 2) {
                    liveTrackPaint.color = active.color
                    var prevX = -1f
                    var prevY = -1f
                    for (p in active.points) {
                        val wp = OsmTileEngine.latLonToWorldPixel(p.lat, p.lon, baseZoom)
                        val sx = (wp.first - centerWorld.first + cx).toFloat()
                        val sy = (wp.second - centerWorld.second + cy).toFloat()

                        if (prevX >= 0f && prevY >= 0f) {
                            canvas.drawLine(prevX, prevY, sx, sy, liveTrackPaint)
                        }
                        prevX = sx
                        prevY = sy
                    }
                }
            }
        }

        private fun drawMarkers(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val invScale = 1f / scale

            for (m in markersList) {
                if (!m.isVisible) continue
                val wp = OsmTileEngine.latLonToWorldPixel(m.lat, m.lon, baseZoom)
                val sx = (wp.first - centerWorld.first + cx).toFloat()
                val sy = (wp.second - centerWorld.second + cy).toFloat()

                canvas.save()
                canvas.translate(sx, sy)
                if (invScale != 1f) {
                    canvas.scale(invScale, invScale)
                }
                if (mapBearing != 0f) {
                    canvas.rotate(mapBearing)
                }

                markerPinPaint.color = m.color
                // Pin head circle
                canvas.drawCircle(0f, -18f, 14f, markerPinPaint)
                canvas.drawCircle(0f, -18f, 14f, userRingPaint)
                // Pin stem
                canvas.drawLine(0f, -4f, 0f, 0f, userRingPaint)

                // Label
                canvas.drawText(m.name, 20f, -8f, markerTextPaint)

                canvas.restore()
            }
        }

        private fun drawUserLocation(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            val loc = currentLocation
            val targetLat = loc?.latitude ?: mapCenterLat
            val targetLon = loc?.longitude ?: mapCenterLon

            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val wp = OsmTileEngine.latLonToWorldPixel(targetLat, targetLon, baseZoom)
            val sx = (wp.first - centerWorld.first + cx).toFloat()
            val sy = (wp.second - centerWorld.second + cy).toFloat()

            // Accuracy circle (scales with geographic terrain)
            if (loc != null && loc.hasAccuracy()) {
                val metersPerPx = (156543.03392 * cos(Math.toRadians(loc.latitude))) / (1 shl baseZoom)
                val accPx = (loc.accuracy / metersPerPx).toFloat()
                canvas.drawCircle(sx, sy, accPx, accuracyPaint)
                canvas.drawCircle(sx, sy, accPx, accuracyStrokePaint)
            }

            canvas.save()
            canvas.translate(sx, sy)
            val invScale = 1f / scale
            if (invScale != 1f) {
                canvas.scale(invScale, invScale)
            }

            val bearingToDraw: Float? = compassHeading
                ?: if (loc?.hasBearing() == true && loc.speed > 0.5f) loc.bearing
                else if (trajectoryBearing != 0f) trajectoryBearing
                else null

            if (bearingToDraw != null) {
                canvas.save()
                canvas.rotate(bearingToDraw, 0f, 0f)
                val arrowPath = Path().apply {
                    moveTo(0f, -34f)
                    lineTo(14f, 6f)
                    lineTo(0f, -2f)
                    lineTo(-14f, 6f)
                    close()
                }
                canvas.drawPath(arrowPath, headingArrowPaint)
                canvas.restore()
            }

            // Center blue user dot
            canvas.drawCircle(0f, 0f, 14f, userCenterPaint)
            canvas.drawCircle(0f, 0f, 14f, userRingPaint)

            canvas.restore()
        }
    }
}
