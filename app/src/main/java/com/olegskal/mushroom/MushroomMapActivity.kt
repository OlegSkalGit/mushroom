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
import android.content.res.ColorStateList
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.map.*
import com.olegskal.mushroom.math.ProcessedLocationMetrics
import com.olegskal.mushroom.math.GeoMath
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
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
        private val poiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "PoiLoader").apply { isDaemon = true }
        }
    }

    private lateinit var mapView: MushroomMapView
    private lateinit var dbHelper: DatabaseHelper

    val mapCenterLat: Double
        get() = if (::mapView.isInitialized) mapView.mapCenterLat else 50.4501

    val mapCenterLon: Double
        get() = if (::mapView.isInitialized) mapView.mapCenterLon else 30.5234

    // UI overlays
    private lateinit var tvRecordingBadge: TextView
    private lateinit var compassButton: CompassButton
    private lateinit var btnSearch: SearchAddressButton
    private lateinit var btnRuler: RulerButton
    private lateinit var btnAddMarker: FlagMarkerButton
    private lateinit var btnRecordTrack: TrackRecordButton
    private lateinit var btnMenu: Button
    private lateinit var btnRouteToggle: Button
    private lateinit var progressBarRoute: ProgressBar
    private lateinit var btnDownloadMapBanner: Button
    lateinit var bottomDownloadProgressBar: ProgressBar
    lateinit var bottomDownloadProgressText: TextView

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
    var isFollowLocation: Boolean = true
        private set
    var isFollowHeading: Boolean = false
        private set

    fun setFollowLocation(follow: Boolean) {
        val changed = (isFollowLocation != follow)
        isFollowLocation = follow
        if (changed) {
            AppPrefs.setFollowUser(this, follow)
        }
        if (!follow) {
            if (::mapView.isInitialized) {
                mapView.cancelCenterAnimation()
            }
            if (isFollowHeading) {
                setFollowHeading(false)
            }
        }
        updateCompassButtonState()
    }

    fun setFollowHeading(follow: Boolean) {
        isFollowHeading = follow
        if (!follow && ::mapView.isInitialized) {
            mapView.cancelBearingAnimation()
            mapView.resetHeadingTracking()
        }
        updateCompassButtonState()
    }

    fun updateCompassButtonState(angle: Float = if (::mapView.isInitialized) -mapView.mapBearing else 0f) {
        if (!::compassButton.isInitialized || !::mapView.isInitialized) return
        val isNotNorth = abs(mapView.mapBearing % 360f) > 0.5f
        val isShifted = !isFollowLocation
        val isDisplaced = isShifted || (isNotNorth && !isFollowHeading)

        val mode = when {
            isDisplaced -> CompassButton.Mode.DISPLACED
            isFollowHeading -> CompassButton.Mode.FOLLOW_HEADING
            else -> CompassButton.Mode.FOLLOW_NORTH_UP
        }
        compassButton.setMode(mode, angle)
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var isTileRedrawPending = false
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
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
        PoiManager.init(this)
        BRouterEngine.ensureProfilesExtracted(this)
        dbHelper = DatabaseHelper(this)
        dbHelper.restoreDataFromExternalStorageIfDbEmpty()

        val hasSavedLoc = AppPrefs.hasSavedMapLocation(this)
        setFollowLocation(!hasSavedLoc && AppPrefs.getFollowUser(this))

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

        btnSearch = SearchAddressButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Search Address"
            setOnClickListener {
                AddAddressDialog.show(this@MushroomMapActivity, dbHelper) {
                    mapView.reloadMarkers()
                }
            }
        }

        btnRuler = RulerButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Ruler"
            setOnClickListener {
                toggleRulerMode()
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
                val nearestPoi = PoiManager.findNearestPoi(lat, lon, 50.0)
                ItemEditDialog.showAddMarker(
                    this@MushroomMapActivity,
                    dbHelper,
                    lat,
                    lon,
                    altitude = alt,
                    initialName = nearestPoi?.name
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

        compassButton = CompassButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Compass"
            setOnClickListener {
                handleCompassClick()
            }
        }
        updateCompassButtonState()

        topHeaderRow.addView(btnMenu)
        topHeaderRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        topHeaderRow.addView(btnSearch)
        topHeaderRow.addView(btnRuler)
        topHeaderRow.addView(btnAddMarker)
        topHeaderRow.addView(btnRecordTrack)
        topHeaderRow.addView(compassButton)
        topInfoPanel.addView(topHeaderRow)

        val actionButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
            }
        }

        btnRouteToggle = UiUtils.createStyledButton(this, "Маршрут") {
            handleRouteToggleClick()
        }.apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (4 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        actionButtonsRow.addView(btnRouteToggle)

        progressBarRoute = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#555555"))
            visibility = View.GONE
            val h = (8 * resources.displayMetrics.density).toInt()
            val w = (100 * resources.displayMetrics.density).toInt()
            val marginStart = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                setMargins(marginStart, 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        actionButtonsRow.addView(progressBarRoute)

        val actionSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
        }
        actionButtonsRow.addView(actionSpacer)

        btnDownloadMapBanner = UiUtils.createStyledButton(this, "⬇️ Завантажити карту") {
            val country = MapDownloadManager.findCountryForLocation(mapView.mapCenterLat, mapView.mapCenterLon)
            if (country != null) {
                RegionDownloadDialog.confirmAndDownloadCountry(this@MushroomMapActivity, country) {
                    mapView.invalidate()
                    updateMapDownloadBanner()
                }
            } else {
                RegionDownloadDialog.show(this@MushroomMapActivity) {
                    mapView.invalidate()
                    updateMapDownloadBanner()
                }
            }
        }.apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            val padH = (10 * resources.displayMetrics.density).toInt()
            val padV = (4 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((6 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }
        }
        actionButtonsRow.addView(btnDownloadMapBanner)
        topInfoPanel.addView(actionButtonsRow)

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

        bottomDownloadProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            visibility = View.GONE
            val h = (4 * resources.displayMetrics.density).toInt().coerceAtLeast(6)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                h,
                Gravity.BOTTOM
            )
        }
        rootLayout.addView(bottomDownloadProgressBar)

        bottomDownloadProgressText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            val padH = (10 * resources.displayMetrics.density).toInt()
            val padV = (3 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D9131C16"))
                cornerRadius = 6 * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#263D30"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            }
        }
        rootLayout.addView(bottomDownloadProgressText)

        setContentView(rootLayout)

        OsmTileEngine.onTileReadyListener = {
            if (!isTileRedrawPending) {
                isTileRedrawPending = true
                uiHandler.post(tileRedrawRunnable)
            }
        }

        handleIncomingIntent(intent)
        updateUiLanguage()

        uiHandler.postDelayed({
            if (!isFinishing) {
                com.olegskal.mushroom.ui.InitialSetupDialog.showIfNeeded(this) {
                    com.olegskal.mushroom.map.OsmTileEngine.reloadMaps()
                    com.olegskal.mushroom.map.PoiManager.refreshPoiFiles()
                    mapView.reloadPois()
                    updateMapDownloadBanner()
                    mapView.invalidate()
                }
            }
        }, 600L)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        MushroomTrackingService.isAppInForeground = true
        MushroomTrackingService.ensureServiceAndNotification(this)
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
        setFollowLocation(false)
        setFollowHeading(false)
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
                setFollowLocation(false)
                setFollowHeading(false)
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
        val isUk = AppPrefs.isUk(this)
        val titleText = if (isUk) "⚠️ Увага!" else "⚠️ Attention!"
        val messageText = if (isUk) {
            "Запис треку автоматично не зупиняється!\nНе забудьте зупинити запис треку, для збереження заряду акумулятора!"
        } else {
            "Track recording does not stop automatically!\nDo not forget to stop track recording to preserve battery power!"
        }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMessage(messageText)
            .setPositiveButton(if (isUk) "Почати запис" else "Start recording") { _, _ ->
                performStartTrackRecording(title, color)
            }
            .setNegativeButton(if (isUk) "Скасувати" else "Cancel", null)
            .show()
    }

    private fun performStartTrackRecording(title: String, color: Int) {
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

    private fun handleCompassClick() {
        val isUk = AppPrefs.isUk(this)
        val isNotNorth = abs(mapView.mapBearing % 360f) > 0.5f
        val isShifted = !isFollowLocation
        val isDisplacedOrRotated = isShifted || isNotNorth || isFollowHeading

        if (isDisplacedOrRotated) {
            setFollowHeading(false)
            mapView.animateBearingTo(0f)
            setFollowLocation(true)
            mapView.centerOnCurrentLocation()
            updateCompassButtonState(0f)
            val msg = if (isUk) "Карту вирівняно на північ та відцентровано" else "Map aligned to North & centered"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            setFollowHeading(true)
            setFollowLocation(true)
            mapView.centerOnCurrentLocation()
            val targetHeading = mapView.getUserHeading() ?: 0f
            mapView.animateBearingTo(targetHeading)
            updateCompassButtonState(-targetHeading)
            val msg = if (isUk) "Вирівнювання за стрілкою індикатора" else "Tracking heading orientation"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleRulerMode() {
        if (!mapView.isRulerMode) {
            mapView.isRulerMode = true
            btnRuler.setRulerActive(true)
            updateRouteButtonVisibility()
            val msg = if (AppPrefs.isUk(this)) "Режим лінійки увімкнено" else "Ruler mode enabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            if (mapView.rulerPoints.size <= 1) {
                mapView.rulerPoints.clear()
                mapView.routeSegments.clear()
                mapView.isRouteMode = false
                mapView.isRulerMode = false
                btnRuler.setRulerActive(false)
                updateRouteButtonVisibility()
                mapView.invalidate()
            } else {
                showRulerActionDialog()
            }
        }
    }

    private fun showRulerActionDialog() {
        val lang = AppPrefs.getAppLang(this)
        val title = if (lang == "uk") "Лінійка вимірювань" else "Measurement Ruler"
        val totalKm = if (mapView.isRouteMode && mapView.routeSegments.isNotEmpty()) {
            mapView.calculateRouteTotalDistanceMeters() / 1000f
        } else {
            mapView.calculateRulerTotalDistanceMeters() / 1000f
        }
        val msg = if (lang == "uk") {
            String.format(Locale.US, "Точок: %d | Дистанція: %.2f км\nОберіть дію:", mapView.rulerPoints.size, totalKm)
        } else {
            String.format(Locale.US, "Points: %d | Distance: %.2f km\nChoose action:", mapView.rulerPoints.size, totalKm)
        }
        val btnSave = if (lang == "uk") "Зберегти трек" else "Save Track"
        val btnDelete = if (lang == "uk") "Видалити" else "Delete"
        val btnCancel = if (lang == "uk") "Скасувати" else "Cancel"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(btnSave) { _, _ ->
                val trackPts = mutableListOf<com.olegskal.mushroom.model.TrackPoint>()
                var totalDistMeters = 0f

                if (mapView.isRouteMode && mapView.routeSegments.isNotEmpty()) {
                    val selectedSegs = mapView.routeSegments
                        .filter { it.isSelected }
                        .sortedWith(compareBy<RouteSegment> { it.legIndex }.thenBy { it.spanIndex })
                    for (selSeg in selectedSegs) {
                        if (selSeg.points.size >= 2) {
                            for (p in selSeg.points) {
                                if (trackPts.isEmpty() || trackPts.last().lat != p.first || trackPts.last().lon != p.second) {
                                    trackPts.add(com.olegskal.mushroom.model.TrackPoint(p.first, p.second))
                                }
                            }
                            totalDistMeters += selSeg.distanceMeters
                        }
                    }
                } else {
                    for (pt in mapView.rulerPoints) {
                        trackPts.add(com.olegskal.mushroom.model.TrackPoint(pt.lat, pt.lon))
                    }
                    totalDistMeters = mapView.calculateRulerTotalDistanceMeters()
                }

                ItemEditDialog.showSaveTrackFromRuler(
                    activity = this,
                    dbHelper = dbHelper,
                    points = trackPts,
                    distanceMeters = totalDistMeters
                ) {
                    mapView.rulerPoints.clear()
                    mapView.routeSegments.clear()
                    mapView.isRouteMode = false
                    mapView.isRulerMode = false
                    btnRuler.setRulerActive(false)
                    updateRouteButtonVisibility()
                    mapView.reloadTracks()
                    mapView.invalidate()
                }
            }
            .setNeutralButton(btnDelete) { _, _ ->
                mapView.rulerPoints.clear()
                mapView.routeSegments.clear()
                mapView.isRouteMode = false
                mapView.isRulerMode = false
                btnRuler.setRulerActive(false)
                updateRouteButtonVisibility()
                mapView.invalidate()
                val tMsg = if (lang == "uk") "Лінійку очищено" else "Ruler cleared"
                Toast.makeText(this, tMsg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(btnCancel, null)
            .show()
    }

    private fun handleRouteToggleClick() {
        if (mapView.isRouteMode) {
            mapView.isRouteMode = false
            mapView.routeSegments.clear()
            updateRouteButtonUi()
            mapView.invalidate()
            val msg = if (AppPrefs.isUk(this)) "Режим маршруту вимкнено" else "Route mode disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            if (mapView.rulerPoints.isEmpty()) return
            if (mapView.rulerPoints.size == 1) {
                val loc = mapView.currentLocation ?: currentMetrics?.location
                if (loc == null) {
                    val msg = if (AppPrefs.isUk(this))
                        "Немає сигналу GPS для визначення поточного місцеположення"
                    else
                        "No GPS signal to determine current location"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    return
                }
                mapView.rulerPoints.add(mapView.RulerPoint(loc.latitude, loc.longitude))
                mapView.invalidate()
            }
            showTransportSelectionDialog()
        }
    }

    private fun showTransportSelectionDialog() {
        val isUk = AppPrefs.isUk(this)
        val title = if (isUk) "Оберіть тип транспорту:" else "Select transport type:"
        val options = if (isUk) {
            arrayOf("🚗 Авто", "🚶 Пішки", "🚴 Велосипед")
        } else {
            arrayOf("🚗 Car", "🚶 Foot", "🚴 Bicycle")
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                val transport = when (which) {
                    0 -> TransportType.CAR
                    1 -> TransportType.FOOT
                    else -> TransportType.BICYCLE
                }
                calculateAndDisplayRoute(transport)
            }
            .setNegativeButton(if (isUk) "Відхилити" else "Cancel", null)
            .show()
    }

    private fun calculateAndDisplayRoute(transport: TransportType) {
        val isUk = AppPrefs.isUk(this)
        val waypoints = mapView.rulerPoints.map { Pair(it.lat, it.lon) }

        if (!BRouterEngine.hasNavigationDataForWaypoints(waypoints)) {
            val noDataMsg = if (isUk) {
                "Файли навігації (.rd5) для цього регіону ще не завантажені. Завантажте карту країни для розрахунку маршруту дорогами."
            } else {
                "Navigation files (.rd5) for this region are not downloaded. Download the country map to calculate road routes."
            }
            Toast.makeText(this, noDataMsg, Toast.LENGTH_LONG).show()
        }

        if (::btnRouteToggle.isInitialized) {
            btnRouteToggle.isEnabled = false
        }
        if (::progressBarRoute.isInitialized) {
            progressBarRoute.progress = 0
            progressBarRoute.visibility = View.VISIBLE
        }

        Thread {
            try {
                val result = BRouterEngine.buildRouteSegmentsBetweenWaypoints(
                    waypoints = waypoints,
                    transport = transport,
                    isUk = isUk,
                    onInitialRouteReady = { initialResult ->
                        runOnUiThread {
                            if (!mapView.isRulerMode || mapView.rulerPoints.size <= 1) return@runOnUiThread
                            mapView.routeSegments.clear()
                            mapView.routeSegments.addAll(initialResult.segments)
                            mapView.isRouteMode = true
                            updateRouteButtonUi()
                            mapView.invalidate()
                        }
                    },
                    onProgress = { pct ->
                        runOnUiThread {
                            if (::progressBarRoute.isInitialized) {
                                progressBarRoute.progress = pct
                            }
                        }
                    }
                )
                runOnUiThread {
                    if (::progressBarRoute.isInitialized) {
                        progressBarRoute.visibility = View.GONE
                    }
                    if (::btnRouteToggle.isInitialized) {
                        btnRouteToggle.isEnabled = true
                    }
                    if (!mapView.isRulerMode || mapView.rulerPoints.size <= 1) return@runOnUiThread
                    mapView.routeSegments.clear()
                    mapView.routeSegments.addAll(result.segments)
                    mapView.isRouteMode = true
                    updateRouteButtonUi()
                    mapView.invalidate()
                    val count = result.segments.size
                    val msg = if (isUk) "Побудовано $count варіантів відрізків. Натисніть лінію для вибору"
                              else "Built $count segment variants. Tap a line to select"
                    Toast.makeText(this@MushroomMapActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                AppLogger.log("MushroomMapActivity", "calculateAndDisplayRoute", false, "Routing calculation error: ${e.message}")
                runOnUiThread {
                    if (::progressBarRoute.isInitialized) {
                        progressBarRoute.visibility = View.GONE
                    }
                    if (::btnRouteToggle.isInitialized) {
                        btnRouteToggle.isEnabled = true
                    }
                    val err = if (isUk) "Помилка розрахунку маршруту" else "Route calculation error"
                    Toast.makeText(this@MushroomMapActivity, err, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    fun updateRouteButtonVisibility() {
        if (!::btnRouteToggle.isInitialized) return
        val show = mapView.isRulerMode && mapView.rulerPoints.isNotEmpty()
        btnRouteToggle.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && ::progressBarRoute.isInitialized) {
            progressBarRoute.visibility = View.GONE
        }
        if (show) {
            updateRouteButtonUi()
        }
    }

    private fun updateRouteButtonUi() {
        if (!::btnRouteToggle.isInitialized) return
        val isUk = AppPrefs.isUk(this)
        if (mapView.isRouteMode) {
            btnRouteToggle.text = if (isUk) "✓ Маршрут" else "✓ Route"
            btnRouteToggle.setBackgroundColor(Color.parseColor("#00E5FF"))
            btnRouteToggle.setTextColor(Color.BLACK)
        } else {
            btnRouteToggle.text = if (isUk) "Маршрут" else "Route"
            btnRouteToggle.setBackgroundColor(Color.parseColor("#333333"))
            btnRouteToggle.setTextColor(Color.WHITE)
        }
    }

    fun updateBottomDownloadProgress(visible: Boolean, progress: Int = 0, max: Int = 1000, statusText: String? = null) {
        runOnUiThread {
            if (!::bottomDownloadProgressBar.isInitialized) return@runOnUiThread
            bottomDownloadProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
            if (max > 0) bottomDownloadProgressBar.max = max
            if (progress >= 0) bottomDownloadProgressBar.progress = progress

            if (::bottomDownloadProgressText.isInitialized) {
                if (!visible) {
                    bottomDownloadProgressText.visibility = View.GONE
                } else if (statusText != null) {
                    if (statusText.isNotBlank()) {
                        bottomDownloadProgressText.text = statusText
                        bottomDownloadProgressText.visibility = View.VISIBLE
                    } else {
                        bottomDownloadProgressText.visibility = View.GONE
                    }
                }
            }
        }
    }

    fun centerOnLocation(lat: Double, lon: Double) {
        runOnUiThread {
            if (::mapView.isInitialized) {
                mapView.animateCenterTo(lat, lon, durationMs = 320L, saveOnEnd = true, useDecelerate = true)
            }
        }
    }

    fun reloadMapsAndPois() {
        runOnUiThread {
            com.olegskal.mushroom.map.OsmTileEngine.reloadMaps()
            com.olegskal.mushroom.map.PoiManager.refreshPoiFiles()
            if (::mapView.isInitialized) {
                mapView.reloadPois()
                mapView.postInvalidate()
            }
            updateMapDownloadBanner()
        }
    }

    fun updateMapDownloadBanner() {
        runOnUiThread {
            if (!::btnDownloadMapBanner.isInitialized) return@runOnUiThread
            if (InitialSetupDialog.isDownloading() || MapDownloadManager.isCurrentlyDownloading()) {
                btnDownloadMapBanner.visibility = View.GONE
                return@runOnUiThread
            }
            val country = MapDownloadManager.findCountryForLocation(mapView.mapCenterLat, mapView.mapCenterLon)
            if (country == null) {
                btnDownloadMapBanner.visibility = View.GONE
                return@runOnUiThread
            }
            val status = MapDownloadManager.getCountryStatus(country)
            val isUk = AppPrefs.isUk(this)
            val countryName = if (isUk) country.nameUk else country.name
            when (status) {
                CountryStatus.READY -> {
                    btnDownloadMapBanner.visibility = View.GONE
                    MapDownloadManager.checkCountryUpdatesAsync(country) { hasUpdate ->
                        if (hasUpdate) {
                            runOnUiThread {
                                if (::btnDownloadMapBanner.isInitialized) {
                                    btnDownloadMapBanner.visibility = View.VISIBLE
                                    btnDownloadMapBanner.text = if (isUk) "🔄 Оновити карту ($countryName)" else "🔄 Update map ($countryName)"
                                    btnDownloadMapBanner.setBackgroundColor(Color.parseColor("#D97706"))
                                }
                            }
                        }
                    }
                }
                CountryStatus.NEEDS_UPDATE -> {
                    btnDownloadMapBanner.visibility = View.VISIBLE
                    btnDownloadMapBanner.text = if (isUk) "🔄 Оновити карту ($countryName)" else "🔄 Update map ($countryName)"
                    btnDownloadMapBanner.setBackgroundColor(Color.parseColor("#D97706"))
                }
                CountryStatus.NOT_DOWNLOADED, CountryStatus.INCOMPLETE -> {
                    btnDownloadMapBanner.visibility = View.VISIBLE
                    btnDownloadMapBanner.text = if (isUk) "⬇️ Завантажити карту ($countryName)" else "⬇️ Download map ($countryName)"
                    btnDownloadMapBanner.setBackgroundColor(Color.parseColor("#059669"))
                }
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
        if (::compassButton.isInitialized) {
            compassButton.contentDescription = if (lang == "uk") "Компас та центрування" else "Compass & Center"
        }
        updateMapDownloadBanner()
        updateRouteButtonVisibility()
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
                    setFollowLocation(false)
                    setFollowHeading(false)
                    mapView.animateCenterTo(marker.lat, marker.lon, durationMs = 350L, saveOnEnd = true, useDecelerate = true)
                },
                onVisibilityChanged = {
                    mapView.reloadMarkers()
                }
            )
        }
        container.addView(btnMarkers)

        // Locations (POIs)
        val locationIcon = LocationIconDrawable(density, Color.parseColor("#10B981"), 18)
        val btnLocationsMenu = UiUtils.createStyledButton(
            this,
            if (currentLang == "uk") "Локації" else "Locations",
            itemParams,
            icon = locationIcon
        ) {
            dialog.dismiss()
            LocationsCategoriesDialog.show(this@MushroomMapActivity) {
                mapView.reloadPois()
                mapView.invalidate()
            }
        }
        container.addView(btnLocationsMenu)

        // Divider after Locations
        container.addView(UiUtils.createDialogDivider(this))

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
                    setFollowLocation(false)
                    setFollowHeading(false)
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

        // Divider after Maps
        container.addView(UiUtils.createDialogDivider(this))

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
            s?.stopSelfAndCleanup()
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

        class HelpChapter(val title: String, val content: String)

        fun addSpoilerSection(
            icon: String,
            title: String,
            chapters: List<HelpChapter>,
            defaultExpanded: Boolean = false,
            iconDrawable: Drawable? = null
        ) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, (4 * density).toInt(), 0, (6 * density).toInt())
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1B2921"))
                    cornerRadius = 8 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#263D30"))
                }
            }

            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (12 * density).toInt()
                val padV = (10 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#22372B"))
                    cornerRadius = 8 * density
                }
                isClickable = true
                isFocusable = true
            }

            val titleTv = TextView(this).apply {
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
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val arrowTv = TextView(this).apply {
                text = if (defaultExpanded) "▲" else "▼"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding((6 * density).toInt(), 0, (4 * density).toInt(), 0)
            }

            headerLayout.addView(titleTv)
            headerLayout.addView(arrowTv)
            card.addView(headerLayout)

            val bodyLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, (8 * density).toInt(), p, (12 * density).toInt())
                visibility = if (defaultExpanded) View.VISIBLE else View.GONE
            }

            for ((index, chapter) in chapters.withIndex()) {
                if (index > 0) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                            setMargins(0, (8 * density).toInt(), 0, (8 * density).toInt())
                        }
                        setBackgroundColor(Color.parseColor("#263D30"))
                    }
                    bodyLayout.addView(divider)
                }

                if (chapter.title.isNotBlank()) {
                    val chTitle = TextView(this).apply {
                        text = chapter.title
                        setTextColor(Color.parseColor("#34D399"))
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, (2 * density).toInt(), 0, (3 * density).toInt())
                    }
                    bodyLayout.addView(chTitle)
                }

                val chContent = TextView(this).apply {
                    text = chapter.content
                    setTextColor(Color.parseColor("#D1D5DB"))
                    textSize = 12.5f
                    setLineSpacing(3f, 1.15f)
                }
                bodyLayout.addView(chContent)
            }

            card.addView(bodyLayout)

            headerLayout.setOnClickListener {
                val isVisible = bodyLayout.visibility == View.VISIBLE
                bodyLayout.visibility = if (isVisible) View.GONE else View.VISIBLE
                arrowTv.text = if (isVisible) "▼" else "▲"
            }

            content.addView(card)
        }

        if (isUk) {
            addSpoilerSection(
                "🗺️", "Векторні карти Mapsforge, навігація та жести",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Керування картою та жести",
                        "• Переміщення: проведіть одним пальцем по екрану для вільного огляду карти.\n" +
                        "• Масштабування: зводьте або розводьте два пальці для плавного наближення чи віддалення.\n" +
                        "• Швидкий зум однією рукою: двічі торкніться екрана і, не відпускаючи палець, проведіть угору або вниз.\n" +
                        "• Обертання карти: повертайте двома пальцями, щоб орієнтувати карту за напрямком вашого руху.\n" +
                        "• Створення мітки: швидкий подвійний тап або довге натискання на будь-якій ділянці карти відкриває збереження нової точки."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Панель керування на екрані карти",
                        "• ☰ Меню (вгорі ліворуч): головне меню програми (Маркери, Локації/POI, Треки, Карти, Гриби, Довідка, Вихід).\n" +
                        "• 📏 Лінійка: вимірювання дистанцій та побудова пішохідних, велосипедних або автомобільних маршрутів через BRouter.\n" +
                        "• 🚩 Маркер (прапорець): миттєве збереження точки за вашим GPS-положенням (або центром карти, якщо GPS вимкнено).\n" +
                        "• ⏺️ Запис треку: старт фонового запису маршруту (синій значок) та завершення (червоний STOP).\n" +
                        "• 🧭 Компас: орієнтує карту на північ та центрує на положенні; повторне натискання вмикає автоорієнтацію за напрямком. Режими: червоний контур — зсув (вільний огляд), нейтральний — слідкування на північ, зелений — слідкування з поворотом за рухом.\n" +
                        "• Кнопка «Маршрут»: з'являється у режимі лінійки для розрахунку шляху між точками.\n" +
                        "• Банер «⬇️ Завантажити карту»: з'являється вгорі, якщо карта поточної області ще не завантажена."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Покрокова інструкція: Як зорієнтуватися на місцевості",
                        "• Крок 1: Увімкніть геолокацію (GPS) на телефоні та відкрийте додаток Mushroom.\n" +
                        "• Крок 2: Натисніть кнопку компаса (🧭) у правому верхньому кутку — карта миттєво відцентрується на вашому поточному положенні (синя крапка з індикатором огляду) та зорієнтується на північ.\n" +
                        "• Крок 3: Почніть рух — індикатор напрямку плавно вказуватиме курс завдяки вбудованому магнітному компасу. Повторне натискання на компас увімкне автоповорот карти за ходом вашого руху.\n" +
                        "• Крок 4: Якщо вгорі з'явилася кнопка «⬇️ Завантажити карту» — натисніть її, щоб безкоштовно зберегти офлайн-карту поточної території."
                    )
                ),
                defaultExpanded = true
            )

            addSpoilerSection(
                "", "Точки інтересу («Локації» / POI)",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Категорії та пошук потрібних місць",
                        "• Доступно 22 основні категорії: Джерела та питна вода, Лікарні та аптеки, АЗС та зарядні станції, Магазини та продукти, Кафе та ресторани, Туризм і пам'ятки, Транспорт, Готелі тощо.\n" +
                        "• Усі об'єкти працюють 100% автономно без інтернету.\n" +
                        "• При віддаленні карти сусідні об'єкти зручно групуються у круглі значки з лічильником, а при наближенні показуються окремими іконками з назвами."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Покрокова інструкція: Як увімкнути показ потрібних об'єктів",
                        "• Крок 1: Відкрийте головне меню ☰ (вгорі ліворуч) та виберіть пункт «Локації» (зелена позначка).\n" +
                        "• Крок 2: Скористайтеся верхнім рядком пошуку категорій або виберіть потрібні пункти у повному списку (наприклад: «Джерела води», «Аптеки»).\n" +
                        "• Крок 3: Позначте прапорцями об'єкти, які ви хочете бачити на карті, або зніміть зайві.\n" +
                        "• Крок 4: Поверніться на карту — обрані точки з'являться на місцевості автоматично."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Розумна автоназва та інформація про маркер",
                        "• Якщо ви ставите маркер неподалік від будь-якого відомого об'єкта (до 50 м), програма автоматично підставить його назву в поле назви мітки.\n" +
                        "• У вікні редагування маркера торкніться кнопки ℹ️ (Інфо) поруч із назвою, щоб переглянути точні координати з копіюванням у буфер, адміністративну прив'язку, адресу будинку (<30м) та списки 5 найближчих адрес і 10 найближчих POI. Клік по будь-якому пункту списку створює новий маркер!"
                    )
                ),
                defaultExpanded = false,
                iconDrawable = LocationIconDrawable(density, Color.parseColor("#10B981"), 16)
            )

            addSpoilerSection(
                "📏", "Інтерактивна лінійка та BRouter маршрути",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Вимірювання відстаней на карті",
                        "• Увімкнення: натисніть кнопку з лінійкою 📏 на екрані карти (вона підсвітиться бірюзовим кольором).\n" +
                        "• Вимірювання: ставте точки по черзі тапом по екрану. Над кожною точкою з'являється підказка: довжина поточного відрізка та загальна дистанція.\n" +
                        "• Зручне редагування:\n" +
                        "  - Додавання проміжної точки: просто торкніться лінії між двома точками, щоб вставити нову точку на цьому відрізку.\n" +
                        "  - Переміщення точки: затисніть точку пальцем і перетягніть її в потрібне місце.\n" +
                        "  - Видалення точки: торкніться самої круглої точки для її швидкого видалення."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Покрокова інструкція: Побудова маршрутів (з проміжними точками!)",
                        "• Спосіб 1: Швидкий маршрут від вас до цілі (в 1 клік!):\n" +
                        "  1. Увімкніть режим лінійки 📏 та торкніться карти лише в одному місці — кінцевій точці призначення.\n" +
                        "  2. Натисніть кнопку «Маршрут» у верхній панелі. Програма автоматично візьме ваше поточне GPS-положення за початкову точку!\n" +
                        "  3. Оберіть спосіб пересування: «🚶 Пішки» (лісові та пішохідні стежки), «🚴 Велосипед» або «🚗 Авто».\n" +
                        "  4. Додаток без інтернету розрахує до 3 найкращих варіантів шляху.\n\n" +
                        "• Спосіб 2: Маршрут із довільним стартом, фінішем та проміжними точками (via points):\n" +
                        "  1. Увімкніть режим лінійки 📏 та поставте точки послідовно: перша точка — старт маршруту, остання — фініш, а всі точки між ними — обов'язкові проміжні точки (via points).\n" +
                        "  2. Щоб змінити шлях в обхід перешкоди, торкніться лінії між точками та перетягніть нову точку через потрібну галявину чи місток.\n" +
                        "  3. Натисніть кнопку «Маршрут» та оберіть тип транспорту («Пішки», «Велосипед» чи «Авто»).\n" +
                        "  4. BRouter прокладе наскрізний маршрут послідовно через усі зазначені проміжні точки!"
                    ),
                    HelpChapter(
                        "📌 Глава 3: Вибір альтернатив та збереження в трек",
                        "• Активний обраний маршрут підсвічується яскраво-блакитним кольором, а альтернативні — сірими лініями.\n" +
                        "• Торкніться будь-якої сірої лінії, щоб перемкнутися на неї — додаток покаже її довжину та деталі.\n" +
                        "• Щоб зберегти розрахований маршрут, повторно натисніть кнопку лінійки 📏, оберіть «Зберегти трек», вкажіть назву та колір — маршрут з'явиться у вашому списку треків із зазначенням кілометражу."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🏠", "База адрес, точок інтересу (POI) та офлайн-пошук",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Можливості розумного пошуку",
                        "• Працює повністю офлайн: миттєво знаходить адреси з номерами будинків, вулиці, населені пункти (міста, села, селища), регіони та корисні об'єкти (джерела, аптеки, АЗС, лікарні, магазини, ліси тощо).\n" +
                        "• Структурована видача результатів (квота 30 пунктів): 15 адрес, 10 точок POI та 5 міст/сіл із динамічним каскадним заповненням та сортуванням за відстанню від вас (або центру карти).\n" +
                        "• Дистанція у дужках: перед назвою кожного знайденого результату чітко показується відстань `(X м)` або `(X.X км)`.\n" +
                        "• Розуміє скорочення та розмовні запити: наприклад, «чер маз заправка», «черн зарядна», «аптека», «ліс», «вода».\n" +
                        "• Розпізнає складні номери будинків: шукає як разом, так і через дефіс (68-Б, 68б, 68 б).\n" +
                        "• Наочні значки: кожен тип об'єкта підсвічується зрозумілою іконкою (💊, ⛽, ⚡, 🏥, 🌲, 💧, 🌳, 🚻 тощо)."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Покрокова інструкція: Як знайти адресу, POI чи місто/село",
                        "• Крок 1: Відкрийте головне меню ☰ -> «Маркери» та натисніть кнопку «🔍 Додати адресу».\n" +
                        "• Крок 2: Почніть вводити назву міста/села, вулиці з номером будинку або шуканий об'єкт (наприклад: «Чернігів Мазепи 68-Б», «аптека», «джерело», «лісництво»).\n" +
                        "• Крок 3: У списку результатів торкніться потрібного рядка.\n" +
                        "• Крок 4: Автоматично відкриється вікно нового маркера з точними координатами, готовою назвою та адресою.\n" +
                        "• Крок 5: Натисніть «Зберегти маркер» — мітка одразу з'явиться на карті."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Швидкий маршрут до знайденого місця",
                        "• Після збереження мітки торкніться її на карті, увімкніть лінійку 📏 та натисніть «Маршрут» — ви отримаєте готовий пішохідний або автомобільний маршрут до цієї точки."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "", "Грибні точки та маркери",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Покрокова інструкція: Як зберегти грибне місце",
                        "• Спосіб 1 (Швидкий на місці): торкніться кнопки з червоним прапорцем 🚩 на верхній панелі карти — маркер миттєво збережеться за вашими GPS-координатами (або центром карти).\n" +
                        "• Спосіб 2 (На карті): зробіть подвійний тап або затисніть пальцем будь-яку ділянку на карті.\n" +
                        "• Спосіб 3 (З буфера): скопіюйте координати з месенджера чи іншої карти, відкрийте меню ☰ -> «Маркери» та натисніть «📋 Вставити координати з буфера».\n" +
                        "• Спосіб 4 (З пошуку): відкрийте меню ☰ -> «Маркери» -> «🔍 Додати адресу» та оберіть потрібний знайдений об'єкт.\n" +
                        "• Налаштування мітки: вкажіть назву, виберіть вид гриба або тип точки, встановіть колір значка та додайте нотатки."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Кнопка «Інфо» (ℹ️) у вікні маркера",
                        "• У вікні додавання або редагування маркера праворуч від заголовка розташована кнопка ℹ️ (Інфо).\n" +
                        "• Вона показує: назву мітки, точні координати (клік копіює їх у буфер з підказкою 📋), область, район, громаду, найближчий населений пункт та адресу будинку (якщо відстань менше 30 м).\n" +
                        "• Нижче відображаються списки: 5 найближчих адрес та 10 найближчих точок POI з відстанями у дужках.\n" +
                        "• Пункти списків клікабельні — клік підставляє параметри у форму та змінює режим на збереження нового маркера!"
                    ),
                    HelpChapter(
                        "📌 Глава 3: Список маркерів та експорт GPX",
                        "• Відкрийте меню ☰ -> «Маркери» — перед кожною назвою у дужках зазначено відстань `(X м)` або `(X.X км)` від вас (або від центру карти, якщо геолокація вимкнена).\n" +
                        "• Натисніть на будь-яку точку у списку — карта миттєво відцентрується на ній.\n" +
                        "• Використовуйте галочки для показу або приховування окремих маркерів на карті.\n" +
                        "• Кнопка «Експорт у GPX» зберігає ваші точки у стандартний файл для резервної копії або передачі в інший навігатор."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )

            addSpoilerSection(
                "", "Запис та аналіз GPS-треків",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Покрокова інструкція: Як записати маршрут походу",
                        "• Крок 1: Перед тим як зайти в ліс, натисніть круглу кнопку запису треку ⏺️ на карті (синій значок). Вона зміниться на червоний індикатор STOP, а вгорі з'явиться червоний рядок статусу.\n" +
                        "• Крок 2: Спокійно рухайтеся лісом — додаток записує кожен ваш крок у фоновому режимі, навіть коли екран вимкнено і телефон лежить у кишені.\n" +
                        "• Крок 3: Розумний фільтр виключає тремтіння координат та зайві скупчення точок під час зупинок на одному місці.\n" +
                        "• Крок 4: На екрані постійно відображається жива статистика: пройдений кілометраж та час запису.\n" +
                        "• Крок 5: Після завершення прогулянки знову натисніть кнопку STOP на карті, введіть назву, виберіть колір лінії та збережіть трек."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Перегляд, імпорт та експорт треків",
                        "• Відкрийте меню ☰ -> «Треки» — біля кожного треку в дужках зазначено його точну довжину (наприклад: «Маршрут до болота (4.15 км)»).\n" +
                        "• Вмикайте чекбокси, щоб відображати один або кілька попередніх треків різними кольорами — це допоможе не блукати тими самими місцями.\n" +
                        "• Кнопки «Імпорт» та «Експорт» дозволяють відкривати маршрути друзів або зберігати власні у форматі GPX."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )

            addSpoilerSection(
                "📥", "Завантаження карт, оновлень та ресурсів",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Покрокова інструкція: Налаштування перед виїздом у ліс",
                        "• Крок 1: Підключіть телефон до Wi-Fi вдома перед поїздкою.\n" +
                        "• Крок 2: Відкрийте меню ☰ -> «🗺️ Карти» та оберіть вашу країну (наприклад, Україна).\n" +
                        "• Крок 3: Програма автоматично завантажить усе необхідне: карту місцевості, базу адрес, точки інтересу (POI), файли пішохідних маршрутів, енциклопедію та модель визначника.\n" +
                        "• Крок 4: Слідкуйте за тонкою смужкою знизу екрана — вона показує прогрес і не заважає переглядати карту.\n" +
                        "• Крок 5: Після завершення завантаження застосунок на 100% готовий до повної автономної роботи без інтернету."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Оновлення карт та додатка",
                        "• Додаток періодично перевіряє наявність свіжих карт: бейдж [Оновити] з'явиться поруч із країною, коли на сервері з'являться нові дані.\n" +
                        "• При виході нової версії програми вам буде запропоновано оновитися в один клік зі збереженням усіх ваших збережених точок і треків."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🍄", "AI Визначник грибів та Енциклопедія",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Як сфотографувати та розпізнати гриб",
                        "• Крок 1: Відкрийте меню ☰ -> «🍄 Гриби» -> вкладка «🔬 Визначник».\n" +
                        "• Крок 2: Натисніть «📷 Камера» (або «🖼️ Галерея») і зробіть від 1 до 5 фотографій однієї знахідки.\n" +
                        "• Крок 3: Для максимальної точності сфотографуйте: 1) капелюшок зверху, 2) низ капелюшка (губку або пластинки), 3) ніжку цілком, 4) поздовжній розріз м'якоті, 5) гриб у природному середовищі.\n" +
                        "• Крок 4: За потреби скористайтеся інструментом кадрування ✂ для точного виділення гриба в кадрі.\n" +
                        "• Крок 5: Натисніть кнопку «🔍 Визначити гриб» — нейромережа офлайн визначить найбільш схожі види.\n" +
                        "• Крок 6: При перегляді фото на повний екран гортайте фото свайпами вліво/вправо, а свайпом угору закривайте перегляд."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Офлайн Енциклопедія грибів",
                        "• Перейдіть на вкладку «📖 Енциклопедія» у розділі «🍄 Гриби».\n" +
                        "• Шукайте гриби за назвою (українською, народною або латинською мовою), фільтруйте за їстівністю (🟢 Їстівні, 🔴 Неїстівні, ☠️ Смертельно отруйні) або типом гіменофора (🧽 Губчасті, 🍂 Пластинчасті).\n" +
                        "• Відкривайте детальні картки видів з фотографіями, детальним описом капелюшка, ніжки, м'якоті, сезону збору та небезпечних двійників.\n" +
                        "• ⚠️ Головне правило безпеки: якщо маєте хоч найменший сумнів — ніколи не вживайте незнайомий гриб у їжу!"
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🔋", "Робота у фоні та енергозбереження",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Покрокова інструкція: Налаштування фонової роботи без збоїв",
                        "• Крок 1: Відкрийте меню ☰ -> «🔋 Робота у фоні (без обмежень)».\n" +
                        "• Крок 2: У системному вікні Android виберіть «Без обмежень» (або вимкніть «Оптимізацію заряду» для Mushroom).\n" +
                        "• Це необхідно для того, щоб операційна система не вимикала GPS-модуль, коли екран телефону вимкнено під час запису треку."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Робота компаса та правильне калібрування",
                        "• Індикатор користувача плавно вказує напрямок вашого погляду. Якщо він обертається неточно, покрутіть телефон у повітрі «вісімкою» для калібрування датчиків.\n" +
                        "• Натискання на кнопку компаса на екрані карти миттєво орієнтує карту верхівкою на північ та центрує на вашій позиції."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🌐", "Зведення: Що працює Офлайн, а що Онлайн",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Що працює на 100% ОФЛАЙН (у лісі без жодного зв'язку)",
                        "✓ Перегляд карти місцевості, плавний зум, поворот та орієнтування за компасом.\n" +
                        "✓ Відображення точок інтересу (POI): джерела води, АЗС, аптеки, магазини, пам'ятки.\n" +
                        "✓ Офлайн-пошук адрес, будинків, населених пунктів та об'єктів з дистанціями.\n" +
                        "✓ Вимірювання відстаней лінійкою та побудова маршрутів через проміжні точки.\n" +
                        "✓ Збереження грибних міток, інформаційна картка ℹ️, автопідстановка назв, експорт GPX.\n" +
                        "✓ Фоновий запис треків з точним підрахунком кілометражу.\n" +
                        "✓ Розпізнавання грибів за фото за допомогою вбудованої нейромережі.\n" +
                        "✓ Повна енциклопедія грибів з фотографіями, фільтрами та описами двійників."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Що потребує інтернету ОНЛАЙН (зробіть удома перед виїздом)",
                        "✓ Початкове завантаження карт вибраної країни, бази адрес, POI та маршрутизації.\n" +
                        "✓ Завантаження бази енциклопедії грибів та моделі нейромережі.\n" +
                        "✓ Перевірка та оновлення додатка до найновішої версії."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "💾", "Збереження даних та резервні копії",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Надійність збереження ваших даних",
                        "• Усі ваші збережені грибні галявини, маркери та записані треки зберігаються у внутрішній пам'яті телефону в папці mushroom.\n" +
                        "• Ваші дані ніколи не зникають і не перезаписуються під час оновлення чи перевстановлення додатка."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Покрокова інструкція: Як створити резервну копію (бекап)",
                        "• Крок 1: Підключіть телефон до комп'ютера або відкрийте будь-який файловий менеджер на телефоні.\n" +
                        "• Крок 2: Відкрийте папку mushroom у спільній пам'яті телефону.\n" +
                        "• Крок 3: Скопіюйте папки markers (ваші точки) та tracks (ваші маршрути) на комп'ютер або хмарний диск.\n" +
                        "• Крок 4: Тепер ваші цінні грибні координати під надійним захистом!"
                    )
                ),
                defaultExpanded = false
            )
        } else {
            addSpoilerSection(
                "🗺️", "Mapsforge Vector Maps, Navigation & Gestures",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Map Controls & Gestures",
                        "• Pan: slide a single finger across the screen to freely explore the map.\n" +
                        "• Zoom: pinch or spread two fingers for smooth zoom (from world overview down to forest trails).\n" +
                        "• One-handed zoom: double-tap and hold, then drag up or down to zoom rapidly with one hand.\n" +
                        "• Map rotation: rotate with two fingers to turn the map along your heading.\n" +
                        "• Create waypoint: double-tap or long-press on any clearing to create a custom marker."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: On-Screen Map Controls",
                        "• ☰ Menu (top left): access Markers, Locations/POI, Tracks, Maps, Mushrooms, Help, Exit.\n" +
                        "• 📏 Ruler: measure distances and calculate Foot, Bicycle, or Car routes via BRouter.\n" +
                        "• 🚩 Marker (flag): instantly saves a waypoint at your current GPS location (or map center).\n" +
                        "• ⏺️ Track Recording: start background logging (blue circle) and finish (red STOP square).\n" +
                        "• 🧭 Compass: aligns map to North and centers on your position; tap again for heading follow mode. Modes: red rim — displaced map, white rim — North-up tracking, green rim — heading follow tracking.\n" +
                        "• 'Route' button: appears in ruler mode to calculate paths between waypoints.\n" +
                        "• '⬇️ Download Map' banner: appears at the top if the current region's map is not installed."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Step-by-Step Guide: How to Orient Outdoors",
                        "• Step 1: Enable GPS on your phone and open the Mushroom app.\n" +
                        "• Step 2: Tap the compass button (🧭) in the top-right corner — the map instantly centers on your current location (blue dot with direction beam) and aligns to North.\n" +
                        "• Step 3: Start walking — the arrow rotates dynamically with your movement. Tap compass again to lock map orientation to your heading.\n" +
                        "• Step 4: If an amber/green banner appears at the top, tap it to download vector maps for the current region."
                    )
                ),
                defaultExpanded = true
            )

            addSpoilerSection(
                "", "Points of Interest (Locations / POI)",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Categories & Finding Nearby Spots",
                        "• 22 major categories: Water springs, Hospitals & pharmacies, Fuel & EV chargers, Groceries & shops, Food & cafes, Tourism, Transit, Hotels, etc.\n" +
                        "• Operates 100% offline without cellular coverage or internet.\n" +
                        "• When zoomed out, nearby points cluster into neat circular badges with counters; zooming in reveals individual icons and names."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Step-by-Step Guide: How to Enable Desired POIs",
                        "• Step 1: Open menu ☰ (top left) and choose 'Locations' (green pin).\n" +
                        "• Step 2: Use the top search bar or browse the list to locate desired categories (e.g. 'Drinking Water', 'Pharmacies').\n" +
                        "• Step 3: Check boxes for categories you want displayed on the map, or uncheck unneeded ones.\n" +
                        "• Step 4: Return to the map — selected objects will appear on the terrain automatically."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Smart Marker Auto-Naming & Info Card",
                        "• When placing a marker within 50 meters of any known landmark, the app automatically suggests that landmark's name.\n" +
                        "• In the marker dialog, tap the ℹ️ (Info) button next to the title to see copyable coordinates, admin hierarchy, street address (<30m), and lists of 5 nearest addresses and 10 nearest POIs. Tapping any item creates a new marker!"
                    )
                ),
                defaultExpanded = false,
                iconDrawable = LocationIconDrawable(density, Color.parseColor("#10B981"), 16)
            )

            addSpoilerSection(
                "📏", "Interactive Ruler & BRouter Route Planning",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Measuring Distances on the Map",
                        "• Activate: tap the ruler button 📏 on the map (highlights in vibrant color).\n" +
                        "• Measure: tap sequential points on the map. A floating badge above each segment shows distance and cumulative trip length.\n" +
                        "• Easy editing:\n" +
                        "  - Insert intermediate point: tap any line between two points to insert a new via-point.\n" +
                        "  - Reposition: press and drag any point.\n" +
                        "  - Delete: tap a circular point to remove it."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Step-by-Step Guide: Route Planning (with Intermediate Points!)",
                        "• Method 1: Instant Route from Current Position (1-Click!):\n" +
                        "  1. Activate ruler mode 📏 and tap once on the map at your destination.\n" +
                        "  2. Tap the 'Route' button in the top bar. The app automatically uses your GPS position as start!\n" +
                        "  3. Choose travel profile: '🚶 Foot' (forest paths and footpaths), '🚴 Bicycle', or '🚗 Car'.\n" +
                        "  4. BRouter calculates up to 3 optimal routes immediately offline.\n\n" +
                        "• Method 2: Route with Arbitrary Start, Finish & Via-Points:\n" +
                        "  1. Activate ruler mode 📏 and tap points in order: first point is start, last point is destination, and all points in between are intermediate via-points.\n" +
                        "  2. To steer the route around obstacles, tap on the segment line to insert new via-points and drag them where needed.\n" +
                        "  3. Tap 'Route' and pick transport mode ('Foot', 'Bicycle', 'Car').\n" +
                        "  4. BRouter routes seamlessly through all intermediate via-points in sequence!"
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Alternative Routes & Saving Tracks",
                        "• Active route is highlighted in cyan-blue, while alternatives are shown as gray lines.\n" +
                        "• Tap any gray line to switch to it and view distance details.\n" +
                        "• To save the calculated route, tap the ruler button 📏 again, choose 'Save Track', set name and color — it will appear in your saved tracks list with exact distance."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🏠", "Address Database, Points of Interest (POI) & Search",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Smart Offline Search Capabilities",
                        "• 100% Offline: instantly resolves street addresses with house numbers, settlements (cities, towns, villages), regions, and landmarks (springs, pharmacies, gas stations, EV chargers, stores, forests).\n" +
                        "• Structured Quota (30 items): 15 addresses, 10 POIs, and 5 settlements with dynamic redistribution, sorted by distance from your GPS position (or map center).\n" +
                        "• Distance in Parentheses: every result displays exact distance `(X м)` or `(X.X км)` before its title.\n" +
                        "• Recognizes colloquial queries and complex house numbers (68-B, 68b, 68 b).\n" +
                        "• Clean visual category icons for instant recognition."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Step-by-Step Guide: How to Find an Address or Landmark",
                        "• Step 1: Open menu ☰ -> 'Markers' and tap '🔍 Add Address'.\n" +
                        "• Step 2: Start typing a settlement, street with house number, or facility name (e.g., `Mazepy 68-B`, `gas station`, `pharmacy`, `spring`).\n" +
                        "• Step 3: In the suggestion list, tap your desired item.\n" +
                        "• Step 4: The new marker dialog opens with pre-filled coordinates, title, and address.\n" +
                        "• Step 5: Tap 'Save' — the marker appears on the map."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Instant Navigation to Found Locations",
                        "• After saving the waypoint, tap it on the map, open ruler 📏, and click 'Route' — you get instant turn-by-turn routing right to that spot."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "", "Mushroom Spots & Markers",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Step-by-Step Guide: How to Save a Mushroom Spot",
                        "• Method 1 (Quick at current spot): tap the red flag button 🚩 on the map — your point is instantly saved at GPS coordinates (or map center).\n" +
                        "• Method 2 (On the map): double-tap or long-press on any clearing on the map.\n" +
                        "• Method 3 (From clipboard): copy coordinates, open menu ☰ -> 'Markers' -> '📋 Paste Coordinates from Clipboard'.\n" +
                        "• Method 4 (From search): open menu ☰ -> 'Markers' -> '🔍 Add Address' and select a result.\n" +
                        "• Customize your point: title, mushroom species, icon color, and notes."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Marker Info Button (ℹ️)",
                        "• In the marker dialog, tap ℹ️ (Info) next to the title to see: marker name, copyable coordinates (📋), administrative region, house address (<30m), and lists of 5 nearest addresses and 10 nearest POIs with distances in parentheses.\n" +
                        "• Items are clickable — tapping any item loads its coordinates into the dialog as a new marker!"
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Markers List & GPX Export",
                        "• Open menu ☰ -> 'Markers' — each marker displays distance in parentheses `(X м)` or `(X.X км)` from your position (or map center).\n" +
                        "• Tap any waypoint to center the map on it.\n" +
                        "• Check/uncheck boxes to toggle marker visibility on the map.\n" +
                        "• Tap 'Export to GPX' to save points into a standard file for backup or sharing."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )

            addSpoilerSection(
                "", "Track Recording & GPS Logging",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Step-by-Step Guide: How to Record Your Foraging Trail",
                        "• Step 1: Before entering the woods, tap the circular track button ⏺️ on the map (blue icon). It turns into a red STOP square with a live status bar at the top.\n" +
                        "• Step 2: Forage freely — the app logs your route in the background even with the screen off in your pocket.\n" +
                        "• Step 3: Smart filtering eliminates GPS coordinate jitter and unnecessary clusters during pauses.\n" +
                        "• Step 4: Elapsed duration and total distance are displayed on screen in real time.\n" +
                        "• Step 5: When finished, tap STOP on the map, enter a track name, choose color, and save."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Reviewing, Importing & Exporting Tracks",
                        "• Open menu ☰ -> 'Tracks' — each track displays its exact distance in km.\n" +
                        "• Check boxes to overlay past trails in different colors so you can see where you already searched.\n" +
                        "• Use 'Import' and 'Export' buttons to load tracks from friends or back up your trails in standard GPX format."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )

            addSpoilerSection(
                "📥", "Data Downloads & App Updates",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Step-by-Step Guide: Initial Setup Before Going Outdoors",
                        "• Step 1: Connect your phone to Wi-Fi at home prior to your trip.\n" +
                        "• Step 2: Open menu ☰ -> '🗺️ Maps' and select your country (e.g. Ukraine).\n" +
                        "• Step 3: The app automatically downloads vector map, address database, POIs, routing profiles, encyclopedia, and AI model.\n" +
                        "• Step 4: Monitor the slim progress bar at the bottom of the screen.\n" +
                        "• Step 5: Once downloads finish, the application is 100% ready for complete offline operation in the woods."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Updating Maps & the App",
                        "• The app automatically checks for updated maps: an [Update] badge appears next to the country when new data is available.\n" +
                        "• When a new app version is released, you can update in one click while keeping all your saved spots and tracks intact."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🍄", "AI Mushroom Classifier & Encyclopedia",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Step-by-Step Guide: How to Photograph & Identify a Mushroom",
                        "• Step 1: Open menu ☰ -> '🍄 Mushrooms' -> '🔬 Classifier' tab.\n" +
                        "• Step 2: Tap '📷 Camera' (or '🖼️ Gallery') and take 1 to 5 clear photos of the specimen.\n" +
                        "• Step 3: For highest accuracy, capture: 1) top cap, 2) underside (pores/gills), 3) entire stem, 4) longitudinal flesh cut, 5) natural habitat.\n" +
                        "• Step 4: Use crop tool ✂ if needed to frame the mushroom.\n" +
                        "• Step 5: Tap '🔍 Identify Mushroom' — on-device AI returns matching species within seconds.\n" +
                        "• Step 6: When viewing enlarged photos, swipe left/right to browse images and swipe up to close viewer."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Offline Mushroom Encyclopedia",
                        "• Switch to '📖 Encyclopedia' tab in '🍄 Mushrooms'.\n" +
                        "• Search by vernacular or Latin scientific name, filter by edibility (🟢 Edible, 🔴 Inedible, ☠️ Poisonous) or hymenophore (🧽 Pores, 🍂 Gills).\n" +
                        "• Inspect detailed species profiles with photos, morphological guides, and lookalike warnings.\n" +
                        "• ⚠️ Golden Rule: If you are in any doubt whatsoever, NEVER consume a wild mushroom!"
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🔋", "Background Operation & Battery Optimization",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Step-by-Step Guide: Setting Up Unrestricted Background GPS",
                        "• Step 1: Open menu ☰ -> '🔋 Background Service (Unrestricted)'.\n" +
                        "• Step 2: In Android system settings, choose 'Unrestricted' (or disable 'Battery Optimization' for Mushroom).\n" +
                        "• This prevents the operating system from putting GPS to sleep when your phone is locked inside your pocket during track recording."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Compass & Sensor Calibration",
                        "• The user heading arrow indicates your orientation. If misaligned, wave your phone in a figure-8 motion in the air to calibrate sensor.\n" +
                        "• Tapping the compass button on the map aligns the map to geographic North and centers on your position."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🌐", "Summary: Offline vs Online Operations",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: 100% OFFLINE Operations (deep in the forest without signal)",
                        "✓ Browsing maps, smooth zoom, rotating, compass orientation.\n" +
                        "✓ Viewing Points of Interest (POI): water springs, fuel, pharmacies, stores, sights.\n" +
                        "✓ Offline search for addresses, house numbers, settlements, and POIs with distance indicators.\n" +
                        "✓ Distance measurement with ruler and routing with intermediate via-points.\n" +
                        "✓ Creating mushroom waypoints, ℹ️ info panel, auto-naming near landmarks, GPX export.\n" +
                        "✓ Background GPS track logging with real-time distance counters.\n" +
                        "✓ Photo identification with the on-device AI neural network & photo zoom gestures.\n" +
                        "✓ Full mushroom encyclopedia with photos, morphological guides, and lookalike warnings."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: ONLINE Operations Required (perform at home before leaving)",
                        "✓ Initial download of country vector maps, address database, POIs, and routing files.\n" +
                        "✓ Download of the mushroom encyclopedia database and AI model archive.\n" +
                        "✓ Checking and updating the app to the latest version."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "💾", "Data Storage & Backup",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Reliability of Your Saved Data",
                        "• All your marked mushroom spots, waypoints, and recorded trails are safely stored in your phone's internal storage inside the mushroom folder.\n" +
                        "• Your data is never deleted or overwritten during app updates or reinstalls."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Step-by-Step Guide: How to Create a Backup",
                        "• Step 1: Connect your phone to a computer with a USB cable, or open any file manager on your phone.\n" +
                        "• Step 2: Open the mushroom folder in your phone's main storage.\n" +
                        "• Step 3: Copy the markers (your spots) and tracks (your recorded trails) folders to your PC or cloud drive.\n" +
                        "• Step 4: Your secret foraging spots are now securely backed up!"
                    )
                ),
                defaultExpanded = false
            )
        }

        // Repository Link section at the bottom
        val repoSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1B2921"))
                cornerRadius = 8 * density
                setStroke((1 * density).toInt(), Color.parseColor("#263D30"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, (10 * density).toInt(), 0, (6 * density).toInt())
            }
        }

        val tvRepoTitle = TextView(this).apply {
            text = if (isUk) "🌐 Вихідний код проєкту (GitHub):" else "🌐 Open Source Repository (GitHub):"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
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
        MushroomTrackingService.isAppInForeground = true
        MushroomTrackingService.ensureServiceAndNotification(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateUiLanguage()
        mapView.reloadMarkers()
        mapView.reloadTracks()
        mapView.reloadPois()
        updateMapDownloadBanner()
        updateRouteButtonVisibility()
        updateLiveStats()

        MushroomTrackingService.serviceStateListener = { isRunning ->
            if (!isRunning) runOnUiThread { finish() }
            else runOnUiThread { updateLiveStats(); mapView.reloadTracks() }
        }

        updateCompassButtonState()

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

        val cachedMetrics = MushroomTrackingService.lastMetrics
        if (cachedMetrics != null) {
            currentMetrics = cachedMetrics
            mapView.updateLocationMetrics(cachedMetrics)
        } else {
            MushroomTrackingService.instance?.requestSingleNetworkLocation()
        }

        uiHandler.post(periodicRefreshRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            MushroomTrackingService.isAppInForeground = true
            MushroomTrackingService.ensureServiceAndNotification(this)
        }
    }

    override fun onPause() {
        super.onPause()
        MushroomTrackingService.isAppInForeground = false
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
        sensorManager.unregisterListener(this)
        if (::mapView.isInitialized) {
            mapView.saveMapState()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::mapView.isInitialized && mapView.isRulerMode) {
            toggleRulerMode()
            return
        }
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        com.olegskal.mushroom.mushrooms.MushroomClassifierTab.isWarningDismissed = false
        try { com.olegskal.mushroom.address.AddressDb.closeDatabase() } catch (_: Throwable) {}
        try { com.olegskal.mushroom.mushrooms.MushroomDatabaseManager.closeDatabase() } catch (_: Throwable) {}
        try { com.olegskal.mushroom.map.PoiManager.close() } catch (_: Throwable) {}
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

        currentFilteredAzimuth = (currentFilteredAzimuth + diff * 0.20f + 360f) % 360f

        if (isFollowHeading) {
            // Auto-rotation active: update at ~30-60 FPS without 500ms dead pauses
            if (now - lastCompassUiTime >= 33L) {
                lastCompassUiTime = now
                mapView.setCompassHeading(currentFilteredAzimuth)
            }
        } else {
            // Map static: throttle compass arrow to 500ms and 5° deadband to eliminate stationary micro-drift
            var bDiff = kotlin.math.abs(currentFilteredAzimuth - (mapView.compassHeading ?: -999f))
            if (bDiff > 180f) bDiff = 360f - bDiff
            if (bDiff >= 5.0f && now - lastCompassUiTime >= 500L) {
                lastCompassUiTime = now
                mapView.setCompassHeading(currentFilteredAzimuth)
            }
        }
    }

    // --- INNER MAP CANVAS VIEW ---
    @SuppressLint("ClickableViewAccessibility")
    inner class MushroomMapView(context: Context) : View(context) {

        var mapCenterLat: Double = 50.4501
        var mapCenterLon: Double = 30.5234
        var zoomLevel: Float = 12.0f

        var currentLocation: Location? = null
        var compassHeading: Float? = null
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

        // Ruler mode
        inner class RulerPoint(var lat: Double, var lon: Double)
        val rulerPoints = mutableListOf<RulerPoint>()
        var isRulerMode: Boolean = false
            set(value) {
                field = value
                if (!value) {
                    draggedRulerPointIndex = -1
                    isRulerPointDragging = false
                    isRouteMode = false
                    routeSegments.clear()
                }
                invalidate()
            }
        private var draggedRulerPointIndex = -1
        private var isRulerPointDragging = false
        private var rulerDownX = 0f
        private var rulerDownY = 0f

        var isRouteMode: Boolean = false
            set(value) {
                field = value
                if (!value) {
                    routeSegments.clear()
                }
                invalidate()
            }
        val routeSegments = mutableListOf<RouteSegment>()

        // POI caching & display
        private var cachedPois: List<PoiItem> = emptyList()

        private val rulerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99000000")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rulerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rulerNodeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }
        private val rulerNodeCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val rulerBadgeTextPaint = Paint(markerTextPaint).apply {
            textAlign = Paint.Align.CENTER
        }

        private val routeRedDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        private val routeRedDotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }

        private val routeGrayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9E9E9E")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val routeGrayShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66000000")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val routeBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val routeBlueShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99000000")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val routePolylinePath = Path()

        private val poiEmojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 17f * resources.displayMetrics.density
            setShadowLayer(2f * resources.displayMetrics.density, 0f, 1f * resources.displayMetrics.density, Color.parseColor("#99000000"))
        }
        private val poiLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#CC18181B")
        }
        private val poiLabelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#44FFFFFF")
            strokeWidth = 1f * resources.displayMetrics.density
        }
        private val poiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val poiClusterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#10B981")
        }
        private val poiClusterRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
        private val poiClusterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f * resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val poiShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#55000000")
        }
        private val poiLabelRect = RectF()

        fun calculateRulerTotalDistanceMeters(): Float {
            var total = 0f
            for (i in 1 until rulerPoints.size) {
                total += GeoMath.calculateDistance(
                    rulerPoints[i - 1].lat, rulerPoints[i - 1].lon,
                    rulerPoints[i].lat, rulerPoints[i].lon
                )
            }
            return total
        }

        fun calculateRouteTotalDistanceMeters(): Float {
            if (!isRouteMode || routeSegments.isEmpty()) return calculateRulerTotalDistanceMeters()
            val selectedSum = routeSegments.filter { it.isSelected }.sumOf { it.distanceMeters.toDouble() }.toFloat()
            if (selectedSum > 0f) return selectedSum
            val totalSpans = (routeSegments.maxOfOrNull { it.spanIndex } ?: -1) + 1
            var total = 0f
            for (s in 0 until totalSpans) {
                val sel = routeSegments.firstOrNull { it.spanIndex == s }
                total += sel?.distanceMeters ?: 0f
            }
            return total
        }

        fun findRouteSegmentAt(screenX: Float, screenY: Float, maxDistPx: Float): RouteSegment? {
            if (!isRouteMode || routeSegments.isEmpty()) return null
            var bestUnselected: RouteSegment? = null
            var minUnselectedDist = maxDistPx
            var bestSelected: RouteSegment? = null
            var minSelectedDist = maxDistPx

            for (seg in routeSegments) {
                if (seg.points.size < 2) continue
                var segMinD = Float.MAX_VALUE
                for (j in 0 until seg.points.size - 1) {
                    val (x1, y1) = latLonToScreen(seg.points[j].first, seg.points[j].second)
                    val (x2, y2) = latLonToScreen(seg.points[j + 1].first, seg.points[j + 1].second)
                    val l2 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
                    val d = if (l2 == 0f) {
                        hypot((screenX - x1).toDouble(), (screenY - y1).toDouble()).toFloat()
                    } else {
                        val t = (((screenX - x1) * (x2 - x1) + (screenY - y1) * (y2 - y1)) / l2).coerceIn(0f, 1f)
                        val px = x1 + t * (x2 - x1)
                        val py = y1 + t * (y2 - y1)
                        hypot((screenX - px).toDouble(), (screenY - py).toDouble()).toFloat()
                    }
                    if (d < segMinD) segMinD = d
                }
                if (!seg.isSelected) {
                    if (segMinD < minUnselectedDist) {
                        minUnselectedDist = segMinD
                        bestUnselected = seg
                    }
                } else {
                    if (segMinD < minSelectedDist) {
                        minSelectedDist = segMinD
                        bestSelected = seg
                    }
                }
            }
            return bestUnselected ?: bestSelected
        }

        fun selectRouteSegment(hitSeg: RouteSegment) {
            val legSegs = routeSegments.filter { it.legIndex == hitSeg.legIndex && it.points.size >= 2 }
            if (legSegs.isEmpty()) return

            // Stitch a connected path from legStart -> hitSeg.first -> hitSeg.last -> legEnd
            // preserving currently selected segments wherever possible (cost 0 for isSelected, 10 + variantIndex otherwise)
            val nodeMatchMeters = 8f
            val nodeCoords = ArrayList<Pair<Double, Double>>()
            fun nodeIdOf(pt: Pair<Double, Double>): Int {
                for (i in nodeCoords.indices) {
                    if (GeoMath.calculateDistance(pt.first, pt.second, nodeCoords[i].first, nodeCoords[i].second) <= nodeMatchMeters) {
                        return i
                    }
                }
                nodeCoords.add(pt)
                return nodeCoords.size - 1
            }

            val segStartNode = HashMap<String, Int>(legSegs.size)
            val segEndNode = HashMap<String, Int>(legSegs.size)
            for (s in legSegs) {
                segStartNode[s.id] = nodeIdOf(s.points.first())
                segEndNode[s.id] = nodeIdOf(s.points.last())
            }

            val selectedInLeg = legSegs.filter { it.isSelected }.sortedBy { it.spanIndex }
            val legStartNode = segStartNode[selectedInLeg.firstOrNull()?.id ?: legSegs.first().id] ?: 0
            val legEndNode = segEndNode[selectedInLeg.lastOrNull()?.id ?: legSegs.last().id] ?: 0
            val hitStartNode = segStartNode[hitSeg.id] ?: return
            val hitEndNode = segEndNode[hitSeg.id] ?: return

            fun findConnectedChain(fromNode: Int, toNode: Int): List<RouteSegment>? {
                if (fromNode == toNode) return emptyList()
                val nNodes = nodeCoords.size
                val bestCost = IntArray(nNodes) { Int.MAX_VALUE }
                val prevNode = IntArray(nNodes) { -1 }
                val prevSeg = arrayOfNulls<RouteSegment>(nNodes)
                val visited = BooleanArray(nNodes)

                bestCost[fromNode] = 0
                for (step in 0 until nNodes) {
                    var u = -1
                    var minC = Int.MAX_VALUE
                    for (v in 0 until nNodes) {
                        if (!visited[v] && bestCost[v] < minC) {
                            minC = bestCost[v]
                            u = v
                        }
                    }
                    if (u == -1 || u == toNode) break
                    visited[u] = true

                    for (s in legSegs) {
                        if (segStartNode[s.id] == u) {
                            val v = segEndNode[s.id] ?: continue
                            val edgeCost = if (s.isSelected) 0 else (10 + s.variantIndex)
                            if (bestCost[u] + edgeCost < bestCost[v]) {
                                bestCost[v] = bestCost[u] + edgeCost
                                prevNode[v] = u
                                prevSeg[v] = s
                            }
                        }
                    }
                }

                if (bestCost[toNode] == Int.MAX_VALUE) return null
                val chain = ArrayList<RouteSegment>()
                var cur = toNode
                while (cur != fromNode) {
                    val seg = prevSeg[cur] ?: return null
                    chain.add(seg)
                    cur = prevNode[cur]
                }
                chain.reverse()
                return chain
            }

            val prefix = findConnectedChain(legStartNode, hitStartNode)
            val suffix = findConnectedChain(hitEndNode, legEndNode)

            if (prefix != null && suffix != null) {
                val fullChain = ArrayList<RouteSegment>(prefix.size + 1 + suffix.size)
                fullChain.addAll(prefix)
                fullChain.add(hitSeg)
                fullChain.addAll(suffix)

                val chosenIds = HashSet<String>(fullChain.size)
                val baseSpan = hitSeg.legIndex * 1000
                for ((idx, s) in fullChain.withIndex()) {
                    chosenIds.add(s.id)
                    s.spanIndex = baseSpan + idx
                }
                for (s in legSegs) {
                    s.isSelected = chosenIds.contains(s.id)
                }
            } else {
                val sameSpanSegs = legSegs.filter { it.spanIndex == hitSeg.spanIndex }
                for (s in sameSpanSegs) {
                    s.isSelected = (s.id == hitSeg.id)
                }
            }
        }

        private var cachedPoiMinLat = 0.0
        private var cachedPoiMaxLat = 0.0
        private var cachedPoiMinLon = 0.0
        private var cachedPoiMaxLon = 0.0
        private var cachedPoiZoom = -1f
        private var isPoiLoading = false

        private val poiHandler = Handler(Looper.getMainLooper())
        private val poiUpdateRunnable = Runnable {
            updatePoisForViewportAsync()
        }

        fun shouldRefreshPois(): Boolean {
            if (!PoiManager.isPoiEnabled || width == 0 || height == 0) return false
            if (cachedPois.isEmpty() || cachedPoiZoom < 0f) return true
            if (abs(zoomLevel - cachedPoiZoom) >= 0.75f) return true

            // Перевіряємо, чи поточний видимий екран ще надійно знаходиться всередині буфера
            val curC1 = screenToLatLon(0f, 0f)
            val curC2 = screenToLatLon(width.toFloat(), height.toFloat())
            val curMinLat = minOf(curC1.first, curC2.first)
            val curMaxLat = maxOf(curC1.first, curC2.first)
            val curMinLon = minOf(curC1.second, curC2.second)
            val curMaxLon = maxOf(curC1.second, curC2.second)

            return curMinLat < cachedPoiMinLat || curMaxLat > cachedPoiMaxLat ||
                   curMinLon < cachedPoiMinLon || curMaxLon > cachedPoiMaxLon
        }

        fun schedulePoiUpdate(immediate: Boolean = false) {
            if (!PoiManager.isPoiEnabled || width == 0 || height == 0) return
            poiHandler.removeCallbacks(poiUpdateRunnable)
            if (immediate) {
                poiHandler.post(poiUpdateRunnable)
            } else {
                poiHandler.postDelayed(poiUpdateRunnable, 120L)
            }
        }

        fun updatePoisForViewportAsync() {
            if (!PoiManager.isPoiEnabled || width == 0 || height == 0) {
                cachedPois = emptyList()
                return
            }
            if (isPoiLoading) return
            isPoiLoading = true

            // Буфер у 1.0x ширини і висоти екрана в усіх 4 напрямках (разом 3.0x на 3.0x екрана)
            val padW = width.toFloat() * 1.0f
            val padH = height.toFloat() * 1.0f
            val c1 = screenToLatLon(-padW, -padH)
            val c2 = screenToLatLon(width + padW, -padH)
            val c3 = screenToLatLon(-padW, height + padH)
            val c4 = screenToLatLon(width + padW, height + padH)

            val minLat = minOf(c1.first, c2.first, c3.first, c4.first)
            val maxLat = maxOf(c1.first, c2.first, c3.first, c4.first)
            val minLon = minOf(c1.second, c2.second, c3.second, c4.second)
            val maxLon = maxOf(c1.second, c2.second, c3.second, c4.second)
            val curZoom = zoomLevel

            poiExecutor.execute {
                try {
                    val newPois = PoiManager.findPoisInBbox(minLat, maxLat, minLon, maxLon, maxResults = 50000)
                    post {
                        cachedPois = newPois
                        cachedPoiMinLat = minLat
                        cachedPoiMaxLat = maxLat
                        cachedPoiMinLon = minLon
                        cachedPoiMaxLon = maxLon
                        cachedPoiZoom = curZoom
                        isPoiLoading = false
                        invalidate()
                    }
                } catch (e: Exception) {
                    post { isPoiLoading = false }
                }
            }
        }

        fun updatePoisForViewport() {
            schedulePoiUpdate(immediate = true)
        }

        fun reloadPois() {
            cachedPoiMinLat = 0.0
            cachedPoiMaxLat = 0.0
            cachedPoiMinLon = 0.0
            cachedPoiMaxLon = 0.0
            cachedPoiZoom = -1f
            schedulePoiUpdate(immediate = true)
        }

        fun findRulerPointAt(screenX: Float, screenY: Float, maxDistPx: Float): Int {
            var closest = -1
            var minDist = maxDistPx
            for (i in rulerPoints.indices) {
                val (sx, sy) = latLonToScreen(rulerPoints[i].lat, rulerPoints[i].lon)
                val d = hypot((screenX - sx).toDouble(), (screenY - sy).toDouble()).toFloat()
                if (d < minDist) {
                    minDist = d
                    closest = i
                }
            }
            return closest
        }

        fun findRulerSegmentAt(screenX: Float, screenY: Float, maxDistPx: Float): Int {
            if (rulerPoints.size < 2) return -1
            var closestSeg = -1
            var minDist = maxDistPx

            for (i in 0 until rulerPoints.size - 1) {
                val (x1, y1) = latLonToScreen(rulerPoints[i].lat, rulerPoints[i].lon)
                val (x2, y2) = latLonToScreen(rulerPoints[i + 1].lat, rulerPoints[i + 1].lon)

                val l2 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
                val d = if (l2 == 0f) {
                    hypot((screenX - x1).toDouble(), (screenY - y1).toDouble()).toFloat()
                } else {
                    val t = (((screenX - x1) * (x2 - x1) + (screenY - y1) * (y2 - y1)) / l2).coerceIn(0f, 1f)
                    val projX = x1 + t * (x2 - x1)
                    val projY = y1 + t * (y2 - y1)
                    hypot((screenX - projX).toDouble(), (screenY - projY).toDouble()).toFloat()
                }

                if (d < minDist) {
                    minDist = d
                    closestSeg = i
                }
            }
            return closestSeg
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
        private var anchorLat = 0.0
        private var anchorLon = 0.0

        private var isLongPressTriggered = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val longPressSlop = maxOf(touchSlop * 2.2f, 24f * resources.displayMetrics.density)
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        private fun handlePointActionAt(screenX: Float, screenY: Float) {
            val hitMarker = findMarkerAt(screenX, screenY, 32f * resources.displayMetrics.density)
            if (hitMarker != null) {
                val lang = AppPrefs.getAppLang(this@MushroomMapActivity)
                val title = if (lang == "uk") "Видалити маркер?" else "Delete marker?"
                val msg = if (lang == "uk") "Видалити \"${hitMarker.name}\"?" else "Delete \"${hitMarker.name}\"?"
                val delBtn = if (lang == "uk") "Видалити" else "Delete"
                val editBtn = if (lang == "uk") "Редагувати" else "Edit"
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
                    .setNeutralButton(editBtn) { _, _ ->
                        ItemEditDialog.showEditMarker(this@MushroomMapActivity, dbHelper, hitMarker) {
                            reloadMarkers()
                        }
                    }
                    .setNegativeButton(cancelBtn, null)
                    .show()
            } else {
                val coords = screenToLatLon(screenX, screenY)
                val nearestPoi = PoiManager.findNearestPoi(coords.first, coords.second, 50.0)
                ItemEditDialog.showAddMarker(
                    this@MushroomMapActivity,
                    dbHelper,
                    coords.first,
                    coords.second,
                    altitude = 0.0,
                    initialName = nearestPoi?.name
                ) {
                    reloadMarkers()
                }
            }
        }

        private val longPressRunnable = Runnable {
            if (!isDragging && !isMultiTouch && !isDoubleTapDrag) {
                isLongPressTriggered = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                handlePointActionAt(downX, downY)
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
            OsmTileEngine.onViewportChanged(zoomLevel.toInt(), mapCenterLat, mapCenterLon)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            OsmTileEngine.onViewportChanged(zoomLevel.toInt(), mapCenterLat, mapCenterLon)
            updatePoisForViewport()
            updateMapDownloadBanner()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(longPressRunnable)
            poiHandler.removeCallbacks(poiUpdateRunnable)
            cancelCenterAnimation()
            cancelBearingAnimation()
        }

        fun saveMapState() {
            AppPrefs.setMapState(context, mapCenterLat, mapCenterLon, zoomLevel, mapBearing)
            OsmTileEngine.onViewportChanged(zoomLevel.toInt(), mapCenterLat, mapCenterLon)
            updatePoisForViewport()
            updateMapDownloadBanner()
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

            if (isRulerMode) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelCenterAnimation()
                        cancelBearingAnimation()
                        removeCallbacks(longPressRunnable)
                        isLongPressTriggered = false
                        isDoubleTapDrag = false
                        isDoubleTapCandidate = false
                        hasDoubleTapMoved = false
                        rulerDownX = event.x
                        rulerDownY = event.y
                        draggedRulerPointIndex = if (isRouteMode) -1 else findRulerPointAt(event.x, event.y, 28f * density)
                        isRulerPointDragging = false
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isDragging = false
                        isMultiTouch = false
                        downTime = SystemClock.uptimeMillis()
                        downX = event.x
                        downY = event.y
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (count >= 2) {
                            isMultiTouch = true
                            isDragging = false
                            draggedRulerPointIndex = -1
                            isRulerPointDragging = false
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
                        val moveDist = hypot((event.x - rulerDownX).toDouble(), (event.y - rulerDownY).toDouble()).toFloat()
                        if (draggedRulerPointIndex != -1) {
                            if (!isRulerPointDragging && moveDist > touchSlop) {
                                isRulerPointDragging = true
                            }
                            if (isRulerPointDragging) {
                                val coords = screenToLatLon(event.x, event.y)
                                rulerPoints[draggedRulerPointIndex].lat = coords.first
                                rulerPoints[draggedRulerPointIndex].lon = coords.second
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

                            setFollowLocation(false)
                            setFollowHeading(false)

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
                                updateCompassButtonState(-mapBearing)
                            }

                            // 3. Smooth Continuous Pinch Zoom
                            if (prevDist > 20f && dist > 20f) {
                                val factor = dist / prevDist
                                val zoomDelta = (ln(factor.toDouble()) / ln(2.0)).toFloat()
                                zoomLevel = (zoomLevel + zoomDelta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                                prevDist = dist
                                OsmTileEngine.onViewportChanged(zoomLevel.toInt(), mapCenterLat, mapCenterLon)
                            }
                            invalidate()
                        } else if (count == 1 && !isMultiTouch) {
                            if (!isDragging && moveDist > touchSlop * 1.2f) {
                                isDragging = true
                                lastTouchX = event.x
                                lastTouchY = event.y
                            }
                            if (isDragging) {
                                val dx = event.x - lastTouchX
                                val dy = event.y - lastTouchY
                                if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                                    setFollowLocation(false)
                                    setFollowHeading(false)
                                    panMap(dx, dy)
                                    lastTouchX = event.x
                                    lastTouchY = event.y
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
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
                        val upDist = hypot((event.x - rulerDownX).toDouble(), (event.y - rulerDownY).toDouble()).toFloat()
                        if (isRouteMode) {
                            if (!isDragging && upDist <= touchSlop * 1.5f) {
                                val hitSeg = findRouteSegmentAt(event.x, event.y, 28f * density)
                                if (hitSeg != null) {
                                    selectRouteSegment(hitSeg)
                                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    Toast.makeText(context, hitSeg.advantage, Toast.LENGTH_SHORT).show()
                                    invalidate()
                                }
                            }
                            isDragging = false
                        } else if (draggedRulerPointIndex != -1) {
                            if (isRulerPointDragging) {
                                val coords = screenToLatLon(event.x, event.y)
                                rulerPoints[draggedRulerPointIndex].lat = coords.first
                                rulerPoints[draggedRulerPointIndex].lon = coords.second
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            } else {
                                rulerPoints.removeAt(draggedRulerPointIndex)
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                updateRouteButtonVisibility()
                            }
                            draggedRulerPointIndex = -1
                            isRulerPointDragging = false
                            invalidate()
                        } else {
                            if (!isDragging && upDist <= touchSlop * 1.5f) {
                                val segIdx = findRulerSegmentAt(event.x, event.y, 22f * density)
                                val coords = screenToLatLon(event.x, event.y)
                                if (segIdx != -1) {
                                    rulerPoints.add(segIdx + 1, RulerPoint(coords.first, coords.second))
                                } else {
                                    rulerPoints.add(RulerPoint(coords.first, coords.second))
                                }
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                updateRouteButtonVisibility()
                                invalidate()
                            }
                            isDragging = false
                        }
                        saveMapState()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        draggedRulerPointIndex = -1
                        isRulerPointDragging = false
                        isDragging = false
                        isMultiTouch = false
                        saveMapState()
                    }
                }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelCenterAnimation()
                    cancelBearingAnimation()
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
                        val anchorCoords = screenToLatLon(anchorX, anchorY)
                        anchorLat = anchorCoords.first
                        anchorLon = anchorCoords.second
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
                        hasDoubleTapMoved = false
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
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        if (hasDoubleTapMoved) {
                            setFollowLocation(false)
                            setFollowHeading(false)
                            val curX = event.x
                            val curY = event.y

                            val dy = curY - lastTouchY
                            val dyDp = dy / density
                            val zoomDelta = dyDp * 0.012f
                            zoomLevel = (zoomLevel + zoomDelta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)

                            lastTouchX = curX
                            lastTouchY = curY

                            val curDist = hypot((curX - anchorX).toDouble(), (curY - anchorY).toDouble()).toFloat()
                            if (curDist >= 20f * density) {
                                val curAngle = Math.toDegrees(atan2((curY - anchorY).toDouble(), (curX - anchorX).toDouble())).toFloat()
                                var deltaAngle = curAngle - prevAngle
                                while (deltaAngle < -180f) deltaAngle += 360f
                                while (deltaAngle > 180f) deltaAngle -= 360f

                                if (abs(deltaAngle) in 0.3f..30f) {
                                    mapBearing = (mapBearing - deltaAngle) % 360f
                                    if (mapBearing < 0f) mapBearing += 360f
                                    prevAngle = curAngle
                                    updateCompassButtonState(-mapBearing)
                                }
                            } else {
                                prevAngle = Math.toDegrees(atan2((curY - anchorY).toDouble(), (curX - anchorX).toDouble())).toFloat()
                            }

                            pinAnchorToScreen(anchorLat, anchorLon, anchorX, anchorY)

                            if (shouldRefreshPois()) {
                                schedulePoiUpdate(immediate = false)
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

                        setFollowLocation(false)
                        setFollowHeading(false)

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
                            updateCompassButtonState(-mapBearing)
                        }

                        // 3. Smooth Continuous Pinch Zoom
                        if (prevDist > 20f && dist > 20f) {
                            val factor = dist / prevDist
                            val zoomDelta = (ln(factor.toDouble()) / ln(2.0)).toFloat()
                            zoomLevel = (zoomLevel + zoomDelta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                            prevDist = dist
                            OsmTileEngine.onViewportChanged(zoomLevel.toInt(), mapCenterLat, mapCenterLon)
                            if (shouldRefreshPois()) {
                                schedulePoiUpdate(immediate = false)
                            }
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
                                setFollowLocation(false)
                                setFollowHeading(false)
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
                            handlePointActionAt(event.x, event.y)
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
        private var centerAnimator: ValueAnimator? = null

        fun cancelCenterAnimation() {
            centerAnimator?.cancel()
            centerAnimator = null
        }

        fun cancelBearingAnimation() {
            alignAnimator?.cancel()
            alignAnimator = null
        }

        fun animateBearingTo(
            targetBearing: Float,
            durationMs: Long = 260L,
            saveOnEnd: Boolean = true,
            useDecelerate: Boolean = true
        ) {
            val normTarget = (targetBearing % 360f + 360f) % 360f
            val normBearing = (mapBearing % 360f + 360f) % 360f
            var diff = normTarget - normBearing
            while (diff < -180f) diff += 360f
            while (diff > 180f) diff -= 360f

            cancelBearingAnimation()
            if (abs(diff) < 0.5f) {
                mapBearing = normTarget
                updateCompassButtonState(-mapBearing)
                invalidate()
                if (saveOnEnd) saveMapState()
                return
            }
            val start = normBearing
            val end = start + diff

            alignAnimator = ValueAnimator.ofFloat(start, end).apply {
                duration = durationMs
                interpolator = if (useDecelerate) DecelerateInterpolator(1.2f) else LinearInterpolator()
                addUpdateListener { va ->
                    val v = va.animatedValue as Float
                    mapBearing = (v % 360f + 360f) % 360f
                    updateCompassButtonState(-mapBearing)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mapBearing = normTarget
                        updateCompassButtonState(-mapBearing)
                        alignAnimator = null
                        invalidate()
                        if (saveOnEnd) {
                            saveMapState()
                        }
                    }
                })
                start()
            }
        }

        fun animateCenterTo(
            targetLat: Double,
            targetLon: Double,
            durationMs: Long = 320L,
            saveOnEnd: Boolean = false,
            useDecelerate: Boolean = false
        ) {
            cancelCenterAnimation()
            val startLat = mapCenterLat
            val startLon = mapCenterLon
            var dLon = targetLon - startLon
            while (dLon < -180.0) dLon += 360.0
            while (dLon > 180.0) dLon -= 360.0
            val dLat = targetLat - startLat

            if (hypot(dLat, dLon) < 1e-7) {
                mapCenterLat = targetLat
                mapCenterLon = targetLon
                val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
                OsmTileEngine.onViewportChanged(baseZoom, mapCenterLat, mapCenterLon)
                invalidate()
                if (saveOnEnd) saveMapState()
                return
            }

            var lastViewportTileX = -1
            var lastViewportTileY = -1

            centerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = if (useDecelerate) DecelerateInterpolator(1.2f) else LinearInterpolator()
                addUpdateListener { va ->
                    val frac = va.animatedFraction.toDouble()
                    mapCenterLat = startLat + dLat * frac
                    mapCenterLon = (startLon + dLon * frac + 540.0) % 360.0 - 180.0
                    val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
                    val tile = OsmTileEngine.latLonToTile(mapCenterLat, mapCenterLon, baseZoom)
                    if (tile.first != lastViewportTileX || tile.second != lastViewportTileY) {
                        lastViewportTileX = tile.first
                        lastViewportTileY = tile.second
                        OsmTileEngine.onViewportChanged(baseZoom, mapCenterLat, mapCenterLon)
                    }
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mapCenterLat = targetLat
                        mapCenterLon = targetLon
                        val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
                        OsmTileEngine.onViewportChanged(baseZoom, mapCenterLat, mapCenterLon)
                        if (shouldRefreshPois()) {
                            schedulePoiUpdate(immediate = false)
                        }
                        centerAnimator = null
                        invalidate()
                        if (saveOnEnd) {
                            saveMapState()
                        }
                    }
                })
                start()
            }
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
            cancelCenterAnimation()
            mapCenterLat = lat
            mapCenterLon = lon
            invalidate()
            saveMapState()
        }

        fun centerOnCurrentLocation() {
            val loc = currentLocation ?: LocationUtils.getLastKnownLocationCascade(context)
            if (loc != null) {
                currentLocation = loc
                animateCenterTo(loc.latitude, loc.longitude, durationMs = 320L, saveOnEnd = true, useDecelerate = true)
            }
        }

        fun fitTrackBounds(track: MushroomTrack) {
            if (track.points.isEmpty()) return
            setFollowLocation(false)
            setFollowHeading(false)
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
            val targetLat = (minLat + maxLat) / 2.0
            val targetLon = (minLon + maxLon) / 2.0
            animateCenterTo(targetLat, targetLon, durationMs = 350L, saveOnEnd = true, useDecelerate = true)
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
            OsmTileEngine.onViewportChanged(baseZoom, mapCenterLat, mapCenterLon)
            if (shouldRefreshPois()) {
                schedulePoiUpdate(immediate = false)
            }
            invalidate()
        }

        private fun pinAnchorToScreen(aLat: Double, aLon: Double, scrX: Float, scrY: Float) {
            val cx = width / 2f
            val cy = height / 2f
            val baseZoom = zoomLevel.toInt().coerceIn(MIN_BASE_ZOOM, MAX_BASE_ZOOM)
            val scale = 2.0f.pow(zoomLevel - baseZoom)
            val anchorWorld = OsmTileEngine.latLonToWorldPixel(aLat, aLon, baseZoom)

            val rad = Math.toRadians(mapBearing.toDouble())
            val cosR = cos(rad)
            val sinR = sin(rad)

            val screenDx = (scrX - cx).toDouble()
            val screenDy = (scrY - cy).toDouble()

            val rotDx = screenDx * cosR - screenDy * sinR
            val rotDy = screenDx * sinR + screenDy * cosR

            val newCenterWorldX = anchorWorld.first - rotDx / scale
            val newCenterWorldY = anchorWorld.second - rotDy / scale

            val newCoords = OsmTileEngine.worldPixelToLatLon(newCenterWorldX, newCenterWorldY, baseZoom)
            mapCenterLat = newCoords.first
            mapCenterLon = newCoords.second
            OsmTileEngine.onViewportChanged(baseZoom, mapCenterLat, mapCenterLon)
        }

        fun getUserHeading(): Float? {
            val loc = currentLocation
            return compassHeading
                ?: if (loc?.hasBearing() == true && loc.speed > 0.5f) loc.bearing
                else if (trajectoryBearing != 0f) trajectoryBearing
                else null
        }

        private var isHeadingTrackingActive = false

        fun resetHeadingTracking() {
            isHeadingTrackingActive = false
        }

        fun setCompassHeading(az: Float) {
            val prev = compassHeading ?: -999f
            var diff = kotlin.math.abs(az - prev)
            if (diff > 180f) diff = 360f - diff
            compassHeading = az
            if (isFollowHeading) {
                if (alignAnimator?.isRunning != true) {
                    val userH = getUserHeading() ?: az
                    var bDiff = userH - mapBearing
                    while (bDiff < -180f) bDiff += 360f
                    while (bDiff > 180f) bDiff -= 360f

                    val absDiff = kotlin.math.abs(bDiff)
                    if (!isHeadingTrackingActive && absDiff >= 5.0f) {
                        isHeadingTrackingActive = true
                    }

                    if (isHeadingTrackingActive) {
                        if (absDiff < 0.3f) {
                            mapBearing = userH
                            isHeadingTrackingActive = false
                            updateCompassButtonState(-mapBearing)
                            invalidate()
                        } else {
                            mapBearing = (mapBearing + bDiff * 0.35f + 360f) % 360f
                            updateCompassButtonState(-mapBearing)
                            invalidate()
                        }
                    }
                }
            } else if (diff >= 5.0f) {
                invalidate()
            }
        }

        private var lastGpsLocationTime = 0L

        fun updateLocationMetrics(metrics: ProcessedLocationMetrics) {
            val now = SystemClock.uptimeMillis()
            val elapsed = if (lastGpsLocationTime > 0L) now - lastGpsLocationTime else 1000L
            lastGpsLocationTime = now
            val panDuration = elapsed.coerceIn(850L, 1300L)

            val locChanged = currentLocation?.latitude != metrics.location.latitude ||
                    currentLocation?.longitude != metrics.location.longitude
            val headingDiff = kotlin.math.abs((compassHeading ?: -999f) - metrics.compassHeading)
            val trajectoryDiff = kotlin.math.abs(trajectoryBearing - metrics.trajectoryBearing)

            currentLocation = metrics.location
            if (compassHeading == null && metrics.compassHeading != 0f) {
                compassHeading = metrics.compassHeading
            }
            trajectoryBearing = metrics.trajectoryBearing

            if (isFollowLocation && locChanged) {
                animateCenterTo(
                    metrics.location.latitude,
                    metrics.location.longitude,
                    durationMs = panDuration,
                    saveOnEnd = false,
                    useDecelerate = false
                )
            }
            if (isFollowHeading && alignAnimator?.isRunning != true && compassHeading == null) {
                val userH = getUserHeading() ?: metrics.compassHeading
                var bDiff = userH - mapBearing
                while (bDiff < -180f) bDiff += 360f
                while (bDiff > 180f) bDiff -= 360f
                if (kotlin.math.abs(bDiff) >= 3.0f) {
                    animateBearingTo(
                        userH,
                        durationMs = panDuration,
                        saveOnEnd = false,
                        useDecelerate = false
                    )
                }
            }
            if (locChanged || headingDiff >= 5.0f || trajectoryDiff >= 5.0f) {
                if (centerAnimator?.isRunning != true && alignAnimator?.isRunning != true) {
                    invalidate()
                }
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
            val tilesDrawn = drawOsmTiles(canvas, cx, cy, baseZoom, scale)

            // 1b. Draw OSM Map Labels (overlay pass across full screen Canvas)
            drawOsmLabels(canvas, cx, cy, baseZoom, tilesDrawn)

            // 2. Draw Recorded Tracks
            drawTracks(canvas, cx, cy, baseZoom, scale)

            // 3. Draw POIs
            drawPois(canvas, cx, cy, baseZoom, scale)

            // 4. Draw Markers
            drawMarkers(canvas, cx, cy, baseZoom, scale)

            // 5. Draw Current Position
            drawUserLocation(canvas, cx, cy, baseZoom, scale)

            canvas.restore()

            // 6. Draw Double-tap & Drag Visual Pivot Lever
            if (isDoubleTapDrag && hasDoubleTapMoved) {
                canvas.drawLine(anchorX, anchorY, lastTouchX, lastTouchY, anchorLinePaint)
                canvas.drawCircle(anchorX, anchorY, 18f, anchorPaint)
                canvas.drawCircle(anchorX, anchorY, 5f, anchorCenterPaint)
            }

            // 7. Draw Ruler
            if (isRulerMode || rulerPoints.isNotEmpty()) {
                drawRuler(canvas)
            }
        }

        private fun drawPois(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            if (!PoiManager.isPoiEnabled) return
            if (cachedPois.isEmpty()) {
                if (shouldRefreshPois()) {
                    schedulePoiUpdate(immediate = true)
                }
                return
            }
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val invScale = 1f / scale
            val density = resources.displayMetrics.density
            val showNames = zoomLevel >= 15.0f

            // Агресивне динамічне групування для збереження чистоти карти
            val clusterRadiusScreen = when {
                zoomLevel < 11.0f -> 48f * density
                zoomLevel < 13.5f -> 38f * density
                zoomLevel < 15.5f -> 28f * density
                else -> 18f * density
            }
            val clusterRadiusCanvas = clusterRadiusScreen / scale
            val r2 = clusterRadiusCanvas * clusterRadiusCanvas

            // Безпечна зона видимості з запасом під поворот карти
            val margin = (maxOf(width, height) * 0.6f) / scale
            val minX = cx - margin
            val maxX = cx + margin
            val minY = cy - margin
            val maxY = cy + margin

            class PoiCluster(
                val anchorX: Float,
                val anchorY: Float,
                val items: MutableList<PoiItem>
            )

            val clusters = mutableListOf<PoiCluster>()
            for (poi in cachedPois) {
                val wp = OsmTileEngine.latLonToWorldPixel(poi.lat, poi.lon, baseZoom)
                val sx = (wp.first - centerWorld.first + cx).toFloat()
                val sy = (wp.second - centerWorld.second + cy).toFloat()

                // Відсікаємо точки далеко за межами екрана — гарантія 60 fps
                if (sx < minX || sx > maxX || sy < minY || sy > maxY) continue

                var bestCluster: PoiCluster? = null
                var minD2 = r2
                if (r2 > 0f) {
                    for (c in clusters) {
                        val dx = sx - c.anchorX
                        val dy = sy - c.anchorY
                        val d2 = dx * dx + dy * dy
                        if (d2 <= minD2) {
                            minD2 = d2
                            bestCluster = c
                        }
                    }
                }

                if (bestCluster != null) {
                    bestCluster.items.add(poi)
                } else {
                    clusters.add(PoiCluster(sx, sy, mutableListOf(poi)))
                }
            }

            val emojiFm = poiEmojiPaint.fontMetrics
            val emojiOffsetY = -(emojiFm.ascent + emojiFm.descent) / 2f
            val clusterFm = poiClusterTextPaint.fontMetrics
            val clusterOffsetY = -(clusterFm.ascent + clusterFm.descent) / 2f

            for (cluster in clusters) {
                canvas.save()
                canvas.translate(cluster.anchorX, cluster.anchorY)
                if (invScale != 1f) {
                    canvas.scale(invScale, invScale)
                }
                if (mapBearing != 0f) {
                    canvas.rotate(mapBearing)
                }

                if (cluster.items.size == 1) {
                    val poi = cluster.items[0]

                    // Поодинока точка: ТІЛЬКИ чиста іконка без кружечка
                    canvas.drawText(poi.icon, 0f, emojiOffsetY, poiEmojiPaint)

                    // Плашка назви точки (починаючи з зуму 15)
                    if (showNames && poi.name.isNotEmpty()) {
                        val textWidth = poiTextPaint.measureText(poi.name)
                        val padH = 5f * density
                        val labelHeight = 15f * density
                        val top = -11f * density - labelHeight
                        val bottom = top + labelHeight
                        val left = -textWidth / 2f - padH
                        val right = textWidth / 2f + padH
                        poiLabelRect.set(left, top, right, bottom)

                        canvas.drawRoundRect(poiLabelRect, 4f * density, 4f * density, poiLabelBgPaint)
                        canvas.drawRoundRect(poiLabelRect, 4f * density, 4f * density, poiLabelStrokePaint)

                        val textY = top + labelHeight / 2f - (poiTextPaint.ascent() + poiTextPaint.descent()) / 2f
                        canvas.drawText(poi.name, 0f, textY, poiTextPaint)
                    }
                } else {
                    val count = cluster.items.size
                    val firstCat = cluster.items[0].categoryId
                    val isUniform = cluster.items.all { it.categoryId == firstCat }
                    val clusterIcon = if (isUniform) {
                        cluster.items[0].icon
                    } else {
                        cluster.items.groupBy { it.icon }.maxByOrNull { it.value.size }?.key ?: "📍"
                    }
                    val firstColor = cluster.items[0].color
                    val clusterColor = if (isUniform) firstColor else Color.parseColor("#10B981")

                    // 1. Іконка категорії для всієї групи (завжди видима)
                    canvas.drawText(clusterIcon, 0f, emojiOffsetY, poiEmojiPaint)

                    // 2. Кружечок-бейджик з кількістю у верхньому правому кутку
                    val countText = if (count > 999) "999+" else count.toString()
                    val badgeR = when {
                        count >= 100 -> 10.5f * density
                        count >= 10 -> 9f * density
                        else -> 7.5f * density
                    }
                    val badgeX = 8.5f * density
                    val badgeY = -8.5f * density

                    poiClusterPaint.color = clusterColor
                    canvas.drawCircle(badgeX, badgeY, badgeR + 1.2f * density, poiShadowPaint)
                    canvas.drawCircle(badgeX, badgeY, badgeR, poiClusterPaint)
                    canvas.drawCircle(badgeX, badgeY, badgeR, poiClusterRingPaint)

                    canvas.drawText(countText, badgeX, badgeY + clusterOffsetY, poiClusterTextPaint)
                }

                canvas.restore()
            }
        }

        private fun drawRuler(canvas: Canvas) {
            if (rulerPoints.isEmpty()) return
            val density = resources.displayMetrics.density

            // 1. Draw connecting lines between consecutive points or route segments
            if (isRouteMode && routeSegments.isNotEmpty()) {
                routeGrayShadowPaint.strokeWidth = 6f * density
                routeGrayPaint.strokeWidth = 3f * density
                routeBlueShadowPaint.strokeWidth = 7f * density
                routeBluePaint.strokeWidth = 3.5f * density

                // Unselected gray segments
                for (seg in routeSegments) {
                    if (seg.isSelected || seg.points.size < 2) continue
                    routePolylinePath.reset()
                    val (startX, startY) = latLonToScreen(seg.points[0].first, seg.points[0].second)
                    routePolylinePath.moveTo(startX, startY)
                    for (pIdx in 1 until seg.points.size) {
                        val (px, py) = latLonToScreen(seg.points[pIdx].first, seg.points[pIdx].second)
                        routePolylinePath.lineTo(px, py)
                    }
                    canvas.drawPath(routePolylinePath, routeGrayShadowPaint)
                    canvas.drawPath(routePolylinePath, routeGrayPaint)
                }

                // Selected blue segments
                for (seg in routeSegments) {
                    if (!seg.isSelected || seg.points.size < 2) continue
                    routePolylinePath.reset()
                    val (startX, startY) = latLonToScreen(seg.points[0].first, seg.points[0].second)
                    routePolylinePath.moveTo(startX, startY)
                    for (pIdx in 1 until seg.points.size) {
                        val (px, py) = latLonToScreen(seg.points[pIdx].first, seg.points[pIdx].second)
                        routePolylinePath.lineTo(px, py)
                    }
                    canvas.drawPath(routePolylinePath, routeBlueShadowPaint)
                    canvas.drawPath(routePolylinePath, routeBluePaint)
                }
            } else if (!isRouteMode && rulerPoints.size >= 2) {
                for (i in 0 until rulerPoints.size - 1) {
                    val (x1, y1) = latLonToScreen(rulerPoints[i].lat, rulerPoints[i].lon)
                    val (x2, y2) = latLonToScreen(rulerPoints[i + 1].lat, rulerPoints[i + 1].lon)

                    rulerShadowPaint.strokeWidth = 6f * density
                    canvas.drawLine(x1, y1, x2, y2, rulerShadowPaint)

                    rulerLinePaint.strokeWidth = 3f * density
                    canvas.drawLine(x1, y1, x2, y2, rulerLinePaint)
                }
            }

            // 2. Draw nodes and distance badges
            val isUk = AppPrefs.isUk(context)
            val unit = if (isUk) "км" else "km"
            var cumulativeDistMeters = 0f

            val pointsToDraw = rulerPoints.map { Pair(it.lat, it.lon) }

            for (i in pointsToDraw.indices) {
                val pt = pointsToDraw[i]
                val (x, y) = latLonToScreen(pt.first, pt.second)

                val segDistMeters = if (i > 0) {
                    if (isRouteMode && routeSegments.isNotEmpty()) {
                        val legSegs = routeSegments.filter { it.legIndex == i - 1 && it.isSelected }
                        if (legSegs.isNotEmpty()) {
                            legSegs.sumOf { it.distanceMeters.toDouble() }.toFloat()
                        } else {
                            routeSegments.filter { it.legIndex == i - 1 }.sumOf { it.distanceMeters.toDouble() }.toFloat()
                        }
                    } else {
                        GeoMath.calculateDistance(
                            pointsToDraw[i - 1].first, pointsToDraw[i - 1].second,
                            pt.first, pt.second
                        )
                    }
                } else 0f
                cumulativeDistMeters += segDistMeters

                val isDragged = (!isRouteMode && i == draggedRulerPointIndex && isRulerPointDragging)

                if (isRouteMode) {
                    // Route mode: Solid red dot
                    val outerR = if (isDragged) 8.5f * density else 6f * density
                    val innerR = if (isDragged) 6f * density else 4.5f * density

                    // Shadow
                    canvas.drawCircle(x, y, outerR + 1.5f * density, rulerShadowPaint)
                    // White border
                    routeRedDotBorderPaint.strokeWidth = 1.5f * density
                    canvas.drawCircle(x, y, outerR, routeRedDotBorderPaint)
                    // Red dot center
                    canvas.drawCircle(x, y, innerR, routeRedDotPaint)
                } else {
                    // Ruler mode: Cyan ring with white center
                    val outerR = if (isDragged) 10f * density else 7f * density
                    val innerR = if (isDragged) 5f * density else 3.5f * density

                    // Node shadow
                    canvas.drawCircle(x, y, outerR + 1.5f * density, rulerShadowPaint)
                    // Node outer ring
                    canvas.drawCircle(x, y, outerR, rulerNodeRingPaint)
                    // Node center
                    canvas.drawCircle(x, y, innerR, rulerNodeCenterPaint)
                }

                // Distance text label anchored directly to point (no background, no edge clamping)
                val badgeText = if (i == 0) {
                    "0 $unit"
                } else {
                    val segKm = segDistMeters / 1000f
                    val totalKm = cumulativeDistMeters / 1000f
                    String.format(Locale.US, "%.2f / %.2f %s", segKm, totalKm, unit)
                }

                val nodeR = if (isRouteMode) 6f * density else 7f * density
                val fontMetrics = rulerBadgeTextPaint.fontMetrics
                val textY = y - nodeR - 4f * density - fontMetrics.descent
                canvas.drawText(badgeText, x, textY, rulerBadgeTextPaint)
            }
        }

        private fun drawOsmTiles(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float): List<Pair<Int, Int>> {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val tileSize = OsmTileEngine.TILE_SIZE

            val rad = Math.toRadians(abs(mapBearing.toDouble()))
            val cosR = abs(cos(rad))
            val sinR = abs(sin(rad))
            val halfW = ((cx * cosR + cy * sinR) / scale).toFloat() + 0.25f * tileSize
            val halfH = ((cx * sinR + cy * cosR) / scale).toFloat() + 0.25f * tileSize

            val startPx = centerWorld.first - halfW
            val startPy = centerWorld.second - halfH
            val endPx = centerWorld.first + halfW
            val endPy = centerWorld.second + halfH

            val minTileX = floor(startPx / tileSize).toInt()
            val maxTileX = ceil(endPx / tileSize).toInt()
            val minTileY = floor(startPy / tileSize).toInt()
            val maxTileY = ceil(endPy / tileSize).toInt()

            val maxCoord = 1 shl baseZoom

            val centerTx = floor(centerWorld.first / tileSize).toInt()
            val centerTy = floor(centerWorld.second / tileSize).toInt()

            val tilesToDraw = mutableListOf<Pair<Int, Int>>()
            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    if (ty in 0 until maxCoord) {
                        tilesToDraw.add(Pair(tx, ty))
                    }
                }
            }
            tilesToDraw.sortBy { (tx, ty) ->
                val dx = tx - centerTx
                val dy = ty - centerTy
                dx * dx + dy * dy
            }

            for ((tx, ty) in tilesToDraw) {
                val clampedTx = (tx % maxCoord + maxCoord) % maxCoord
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
                    } else if (baseZoom > 6) {
                        val parentZoom = minOf(5, baseZoom - 1)
                        val diff = baseZoom - parentZoom
                        OsmTileEngine.getTile(parentZoom, clampedTx shr diff, ty shr diff)
                    }

                    // 2. Underscaling fallback: child tiles from higher zoom levels (quadrants)
                    if (!drawnFallback && baseZoom < MAX_BASE_ZOOM) {
                        val childZoom = baseZoom + 1
                        val qHalfW = tileDstRect.width() / 2f
                        val qHalfH = tileDstRect.height() / 2f
                        for (cxIdx in 0..1) {
                            for (cyIdx in 0..1) {
                                val childBmp = OsmTileEngine.getChildTile(
                                    childZoom,
                                    (clampedTx shl 1) + cxIdx,
                                    (ty shl 1) + cyIdx
                                )
                                if (childBmp != null) {
                                    val qLeft = tileDstRect.left + cxIdx * qHalfW
                                    val qTop = tileDstRect.top + cyIdx * qHalfH
                                    quadDstRect.set(qLeft, qTop, qLeft + qHalfW, qTop + qHalfH)
                                    canvas.drawBitmap(childBmp, null, quadDstRect, tileBitmapPaint)
                                }
                            }
                        }
                    }
                }
            }
            return tilesToDraw
        }

        private var lastLabelZoom: Int = -1
        private var lastLabelTiles: List<Pair<Int, Int>> = emptyList()
        private var lastLabelStoreVersion: Int = -1
        private var cachedLabelsToDraw: List<org.mapsforge.core.mapelements.MapElementContainer> = emptyList()
        private val labelMatrix = org.mapsforge.map.android.graphics.AndroidGraphicFactory.INSTANCE.createMatrix()

        private fun drawOsmLabels(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, tilesToDraw: List<Pair<Int, Int>>) {
            val store = OsmTileEngine.labelStore ?: return
            val currentVersion = store.version

            if (baseZoom != lastLabelZoom || tilesToDraw != lastLabelTiles || currentVersion != lastLabelStoreVersion) {
                val rawItems = OsmTileEngine.getLabelsForTiles(tilesToDraw, baseZoom)
                cachedLabelsToDraw = if (rawItems.isNotEmpty()) {
                    org.mapsforge.map.util.LayerUtil.collisionFreeOrdered(rawItems, org.mapsforge.core.model.Rotation.NULL_ROTATION)
                } else {
                    emptyList()
                }
                lastLabelZoom = baseZoom
                lastLabelTiles = tilesToDraw
                lastLabelStoreVersion = currentVersion
            }

            if (cachedLabelsToDraw.isEmpty()) return

            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val origin = org.mapsforge.core.model.Point(
                (centerWorld.first - cx).toDouble(),
                (centerWorld.second - cy).toDouble()
            )

            val gCanvas = org.mapsforge.map.android.graphics.AndroidGraphicFactory.createGraphicContext(canvas)
            for (item in cachedLabelsToDraw) {
                try {
                    item.draw(gCanvas, origin, labelMatrix, org.mapsforge.core.model.Rotation.NULL_ROTATION)
                } catch (_: Throwable) {}
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

        private val userArrowPath = Path().apply {
            moveTo(0f, -34f)
            lineTo(14f, 6f)
            lineTo(0f, -2f)
            lineTo(-14f, 6f)
            close()
        }

        private fun drawUserLocation(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            val loc = currentLocation ?: return
            val targetLat = loc.latitude
            val targetLon = loc.longitude

            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            val wp = OsmTileEngine.latLonToWorldPixel(targetLat, targetLon, baseZoom)
            val sx = (wp.first - centerWorld.first + cx).toFloat()
            val sy = (wp.second - centerWorld.second + cy).toFloat()

            // Accuracy circle (scales with geographic terrain)
            if (loc.hasAccuracy()) {
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

            val bearingToDraw: Float? = getUserHeading()

            if (bearingToDraw != null) {
                canvas.save()
                canvas.rotate(bearingToDraw, 0f, 0f)
                canvas.drawPath(userArrowPath, headingArrowPaint)
                canvas.restore()
            }

            // Center blue user dot
            canvas.drawCircle(0f, 0f, 14f, userCenterPaint)
            canvas.drawCircle(0f, 0f, 14f, userRingPaint)

            canvas.restore()
        }
    }
}
