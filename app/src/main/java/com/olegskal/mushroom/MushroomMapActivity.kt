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
    private lateinit var compassButton: CompassButton
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
        val isActive = isShifted || isNotNorth || isFollowHeading
        compassButton.setBearing(angle, active = isActive)
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

        AppUpdateManager.checkAndDownloadUpdate(this)

        AppPrefs.restoreFromExternalStorage(this)
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

        OsmTileEngine.appContext = applicationContext
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
            mapView.animateBearingTo(0f, activeOnEnd = false)
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
            mapView.animateBearingTo(targetHeading, activeOnEnd = true)
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
                    val totalSpans = (mapView.routeSegments.maxOfOrNull { it.spanIndex } ?: -1) + 1
                    for (s in 0 until totalSpans) {
                        val selSeg = mapView.routeSegments.firstOrNull { it.spanIndex == s && it.isSelected }
                            ?: mapView.routeSegments.firstOrNull { it.spanIndex == s }
                        if (selSeg != null && selSeg.points.size >= 2) {
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
            mapView.routeWaypoints.clear()
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
                            mapView.routeWaypoints.clear()
                            mapView.routeWaypoints.addAll(initialResult.effectiveWaypoints)
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
                    mapView.routeWaypoints.clear()
                    mapView.routeWaypoints.addAll(result.effectiveWaypoints)
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
                    mapView.setCenter(marker.lat, marker.lon)
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
                        "📌 Глава 1: Жести керування картою",
                        "• Переміщення: проведення одним пальцем по екрану для вільного перегляду карти.\n" +
                        "• Масштаб (Pinch-to-zoom): плавне зведення або розведення двох пальців (підтримка масштабів z2 - z18).\n" +
                        "• Дабл-тап драг: подвійний тап з утриманням пальця та рухом вгору/вниз — швидке масштабування та поворот навколо фіксованого якоря.\n" +
                        "• Обертання: плавний поворот двома пальцями для довільного орієнтування карти під кутом руху.\n" +
                        "• Створення та редагування міток: швидкий подвійний тап або довге натискання на вільному місці карти відкриває створення маркера, а тап по існуючому маркеру — діалог редагування чи видалення."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Елементи керування та кнопки карти",
                        "• «Меню» (вгорі ліворуч): головне меню додатку (Карти, Маркери, Треки, Локації, Визначник, Енциклопедія, Налаштування).\n" +
                        "• «Локації» (кнопка з міткою зліва від лінійки): швидкий вибір і фільтрація 22 категорій точок інтересу (POI).\n" +
                        "• «Лінійка» (зліва від прапорця): вмикання інтерактивного вимірювання відстаней та BRouter маршрутизації.\n" +
                        "• «Створити маркер» (прапорець): миттєве збереження точки за поточними GPS-координатами вашого місцеположення.\n" +
                        "• «Запис треку»: старт (синя кнопка) та зупинка (червоний квадрат STOP) фонового запису шляху.\n" +
                        "• «Центрувати на мені» (приціл праворуч): центрування камери на поточній GPS-позиції та вмикання режиму автослідування.\n" +
                        "• «Компас» (стрілка праворуч): індикатор азимута півночі за сенсорами; одиночний тап вирівнює карту строго на північ.\n" +
                        "• Банер завантаження (вгорі праворуч): з'являється автоматично, якщо для поточної області огляду немає векторної карти або доступне оновлення."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Автономний векторний рушій Mapsforge",
                        "• 100% ОФЛАЙН: рушій рендерить карту безпосередньо з локальних векторних файлів .map без використання растрових тайлів, кешу інтернету чи мобільного зв'язку.\n" +
                        "• Черга рендерингу пріоритету (LIFO): центральні тайли екрана промальовуються в першу чергу (~10 мс на тайл).\n" +
                        "• Плавне масштабування: автоматичне векторне перевикористання батьківських та дочірніх тайлів під час зумування виключає появу порожніх екранів."
                    )
                ),
                defaultExpanded = true
            )

            addSpoilerSection(
                "", "Точки інтересу («Локації» / POI)",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Категорії та налаштування",
                        "• Кнопка з міткою на карті або пункт меню «Локації» відкривають перелік 22 офіційних категорій POI.\n" +
                        "• Доступні категорії: Кафе/ресторани, Магазини/продукти, Лікарні/аптеки, Заправки/АЗС/зарядки електромобілів, Туризм/пам'ятки, Проживання/готелі, Спорт, Транспорт тощо.\n" +
                        "• Пошуковий рядок у шапці: миттєво фільтрує категорії за ключовим словом.\n" +
                        "• Чекбокси вибору: вмикають показ відповідних категорій на карті; вибір зберігається у файлі /sdcard/mushroom/poi/poi_config.json."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Відображення на карті та кластеризація",
                        "• Точки відображаються на карті з зуму z13; сусідні точки кластеризуються у круглі бейджі з лічильником кількості.\n" +
                        "• Текстові назви локацій з'являються з масштабу z15 для збереження читабельності карти.\n" +
                        "• Зчитування даних відбувається 100% офлайн безпосередньо з локальних файлів .poi."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Розумна автоназва грибних міток",
                        "• При створенні маркера на відстані до 50 метрів від будь-якої видимої точки інтересу назва маркера автоматично заповнюється назвою цього об'єкта."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = LocationIconDrawable(density, Color.parseColor("#10B981"), 16)
            )

            addSpoilerSection(
                "📏", "Інтерактивна лінійка та BRouter маршрути",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Режим лінійки (Вимірювання дистанції)",
                        "• Вмикання: кнопка з лінійкою на карті (підсвічується бірюзовим кольором #00E5FF).\n" +
                        "• Одинарний тап по карті: додає точку вимірювання; над точкою показується бейдж із двома значеннями: відстань поточного відрізка / сумарна дистанція від старту.\n" +
                        "• Керування точками: тап по точці видаляє її; затискання й перетягування переміщує точку; тап по прямій лінії між точками вставляє нову проміжну точку."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Побудова маршрутів BRouter (Автоматично для 1-ї точки!)",
                        "• Кнопка «Маршрут» у правому верхньому куті доступна як для кількох точок, так і для ОДНІЄЇ точки!\n" +
                        "• Робота для 1 точки: якщо на лінійці встановлено лише 1 точку, натискання «Маршрут» автоматично додає друге положення — поточні GPS-координати вашої стрілки користувача — і миттєво запускає прокладання маршруту від вас до цієї точки!\n" +
                        "• Вибір профілю руху: «Автомобіль» (car-test), «Пішки» (hiking/foot), «Велосипед» (trekking) або «Відхилити» (повернення до звичайної лінійки).\n" +
                        "• BRouter автономно розраховує до 3 альтернативних варіантів шляху між кожною парою точок за навігаційними файлами .rd5 сітки 5°x5°.\n" +
                        "• Непридатні дублікати шляхів автоматично відсіюються, а перетини розділяються на відрізки."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Вибір альтернативних ділянок та збереження в трек",
                        "• Альтернативні маршрути зображуються сірими лініями, а активний обраний шлях — яскраво-блакитним кольором.\n" +
                        "• Клік по сірій ділянці обирає її та показує спливаючу підказку про довжину й переваги вибраного шляху.\n" +
                        "• Вихід із режиму (якщо точок > 1) пропонує діалог: «Зберегти трек» (обрані блакитні ділянки BRouter і прямі лінії зберігаються у трек; у списку треків у назві вказується точна довжина у км), «Видалити» (скидання точок) або «Скасувати»."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🏠", "База адрес, точок інтересу (POI) та офлайн-пошук",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Локальна база адрес та POI addr.db",
                        "• База зберігається за шляхом /sdcard/mushroom/addresses/addr.db.\n" +
                        "• База побудована на SQLite: містить нормалізовану ієрархію admin_places -> streets -> houses (координати в мікроградусах, WITHOUT ROWID) та повну підтримку точок інтересу poi_places із накопичувальним каталогом poi_types.\n" +
                        "• Каталог 180 канонічних OSM-тегів: повна типізація об'єктів (лікарні, аптеки, АЗС, зарядні станції, джерела води, лісові масиви, парки, вбиральні тощо) з автоматичним каскадним автоперекладом на мову країни через Google Translate та MyMemory API.\n" +
                        "• Повнотекстовий індекс пошуку FTS5 з безпечним fallback до FTS4: індексує назви міст, вулиць, номери будинків, власні назви POI, перекладені описи типу та системні теги."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Інтелектуальний гібридний пошук (Адреси + POI)",
                        "• Відкриття: меню «Маркери» -> кнопка «Додати адресу» (під кнопкою «Вставити координати з буфера»).\n" +
                        "• Живий гібридний пошук: миттєво знаходить і точні адреси, і об'єкти інтересу (наприклад: «Чернігів Мазепи 68-Б», «чер азс», «чер маз заправка», «зарядка», «аптека», «ліс», «вода», «парк»).\n" +
                        "• Семантичні синоніми та сегрегація: розпізнає синоніми та частини мови з чітким розділенням понять (парки відпочинку не плутаються з автостоянками, аптеки — з лікарнями).\n" +
                        "• Підтримка літерних номерів: розумний парсер знаходить будинки як з дефісом, так і разом (68-Б, 68б, 68 б).\n" +
                        "• Динамічна квота 20 / 10 (ліміт 30): якщо знайдено і адреси, і POI — виводиться до 20 адрес та 10 POI; якщо результати є лише для одного або чогось менше — залишкова квота автоматично переходить до іншого.\n" +
                        "• Сортування за GPS: за наявності сигналу GPS результати автоматично ранжуються за відстанню від вашого поточного місцеположення.\n" +
                        "• Візуальні емодзі-іконки: кожен тип об'єкта відображається з тематичною іконкою (💊, ⛽, ⚡, 🏥, 🌲, 💧, 🌳, 🚻 тощо)."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Збереження маркера та навігація",
                        "• Вибір адреси або POI зі списку автоматично відкриває стандартне вікно створення маркера з точними координатами та назвою вибраного об'єкта.\n" +
                        "• До створеної точки можна одразу прокласти маршрут пішки, на авто чи велосипеді за допомогою BRouter лінійки або експортувати у GPX."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "", "Грибні точки та маркери",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Способи створення та налаштування маркерів",
                        "• Подвійний або довгий тап по карті: створення точки в будь-якому місці (з автопідстановкою назви найближчої POI).\n" +
                        "• Кнопка з прапорцем: швидке збереження маркера за вашими поточними GPS-координатами.\n" +
                        "• Кнопка «Додати адресу»: створення маркера за адресою з офлайн-бази.\n" +
                        "• Вставка з буфера: імпорт координат із Google Maps або месенджерів.\n" +
                        "• Налаштування точки: назва, тип гриба (Білий, Лисичка, Підберезовик тощо), опис і вибір індивідуального кольору з палітри."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Керування маркерами та експорт GPX",
                        "• Меню «Маркери»: перегляд збережених точок, перехід до маркера на карті в один клік, редагування та видалення.\n" +
                        "• Чекбокси видимості: індивідуальне або групове вмикання/вимикання відображення міток на карті.\n" +
                        "• Експорт у GPX: збереження всіх або вибраних точок у стандартний файл .gpx для обміну з друзями або завантаження в інші навігатори.\n" +
                        "• Зберігання: 100% ОФЛАЙН у файлі /sdcard/mushroom/markers/markers.json."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )

            addSpoilerSection(
                "", "Запис та аналіз GPS-треків",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Фоновий сервіс запису треку",
                        "• Кнопка запису на карті: старт (синя лінія) та зупинка (червоний індикатор STOP).\n" +
                        "• Працює через Foreground Service з постійним сповіщенням у системній шторці Android; стійкий до присипляння системою та Doze Mode.\n" +
                        "• Інтелектуальна фільтрація: точки записуються лише при реальному зміщенні від 2.5 м та точності GPS до 35 м.\n" +
                        "• Індикація в реальному часі: відображення пройденої дистанції та тривалості запису."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Керування треками та імпорт/експорт GPX",
                        "• Меню «Треки»: список записаних маршрутів; у назві кожного треку в дужках обов'язково відображається точна довжина в кілометрах (наприклад: `Маршрут 1 (3.85 км)`), а також час та кількість точок.\n" +
                        "• Чекбокси видимості для показу треків на карті.\n" +
                        "• Повний імпорт та експорт стандартних GPX-файлів."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )

            addSpoilerSection(
                "📥", "Завантаження карт, оновлень та ресурсів",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Строгий порядок та послідовність завантаження",
                        "• Процес завантаження компонентів виконується у суворо визначеному порядку:\n" +
                        "  1. Версія додатку (перевірка актуальності APK через GitHub).\n" +
                        "  2. Оглядова карта світу (world.map).\n" +
                        "  3. Векторна карта країни (.map) — виділено 50% шкали прогрес-бару з відображенням кількості регіонів (наприклад: Україна, 25 регіонів).\n" +
                        "  4. Точки інтересу POI (.poi) — завантажуються одразу після карти країни.\n" +
                        "  5. Файли маршрутизації BRouter (.rd5).\n" +
                        "  6. База адрес (addr.db) — покрокова заливка 25 регіонів країни з live-індикацією.\n" +
                        "  7. Енциклопедія грибів (mushrooms.db).\n" +
                        "  8. Файли нейромережевого визначника грибів (model.zip)."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Фоновий прогрес-бар знизу екрана",
                        "• При старті завантаження стартовий екран або діалог налаштування миттєво закривається.\n" +
                        "• Прогрес завантаження відображається у вигляді тонкої акуратної смуги в самому низу карти з компактною пігулкою поточного статусу, не заважаючи іншим діям.\n" +
                        "• Для тривалого завантаження карти країни виділено 50% шкали індикатора.\n" +
                        "• При завантаженні оновлення додатку після завершення автоматично пропонується встановлення APK та перезапуск."
                    ),
                    HelpChapter(
                        "📌 Глава 3: Пакети країн та оновлення",
                        "• Меню «🗺️ Карти»: завантаження виконується виключно по цілих суверенних країнах (понад 195 країн світу).\n" +
                        "• Пакет країни містить векторну карту (.map), точки інтересу (.poi) та сегменти маршрутизації (.rd5).\n" +
                        "• Автоматична перевірка актуальності файлів на сервері з відображенням бейджів [Оновлення] або [Завантажено]."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🔬", "AI Визначник грибів (Нейромережа)",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Фотографування та завантаження ракурсів",
                        "• Кнопки «📷 Камера» та «🖼️ Галерея» підтримують додавання до 5 фотографій одного гриба.\n" +
                        "• Рекомендовані ракурси: 1) капелюшок зверху, 2) гіменофор (трубочки або пластинки) знизу, 3) ніжка цілком, 4) поздовжній розріз м'якоті, 5) гриб у середовищі зростання (мох, дерево, ґрунт).\n" +
                        "• Можна комбінувати свіжі фото з камери та збережені фото з галереї."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Офлайн-розпізнавання та результати",
                        "• Кнопка «🔍 Визначити гриб» запускає ансамблевий аналіз нейромережею.\n" +
                        "• Робота 100% ОФЛАЙН: ваги моделі зберігаються у файлі model.zip (~60 МБ) та виконуються локально на процесорі пристрою.\n" +
                        "• Результат: список найбільш ймовірних видів грибів із відсотком впевненості.\n" +
                        "• Клік по картці результату миттєво відкриває відповідну статтю в Енциклопедії."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "📖", "Офлайн Енциклопедія грибів",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Локальна база знань",
                        "• 100% автономна робота з локальної бази SQLite (mushrooms.db ~538 МБ); жодних онлайн-запитів.\n" +
                        "• Швидкий пошук за українськими, латинськими (науковими) або англійськими назвами."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Фільтрація та детальна інформація",
                        "• Комбінований фільтр: за їстівністю (🟢 Їстівні, 🔴 Неїстівні, ☠️ Смертельно отруйні) та типом гіменофора (🧽 Трубчасті, 🍂 Пластинчасті, 🍄 Інші).\n" +
                        "• Картка виду містить: фотографії, морфологію капелюшка та ніжки, тип м'якоті, сезон та місця зростання, а також чіткі попередження про небезпечні отруйні двійники."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🔋", "Робота у фоні та енергозбереження",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Оптимізація батареї Android (Doze Mode)",
                        "• Пункт меню «🔋 Робота у фоні (без обмежень)» відкриває налаштування живлення для додатка Mushroom.\n" +
                        "• Обов'язково виберіть «Без обмежень», щоб Android не вимикав GPS-модуль, коли екран вимкнено і телефон лежить у кишені."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Сенсори орієнтації та компас",
                        "• Додаток використовує апаратні сенсори магнітометра та акселерометра для плавної орієнтації стрілки користувача.\n" +
                        "• Кнопка компаса на карті повертає орієнтацію карти строго на північ."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🌐", "Зведення: Що працює Офлайн, а що Онлайн",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Працює 100% ОФЛАЙН (у лісі без зв'язку)",
                        "✓ Векторні карти Mapsforge: масштабування, поворот, панорамування, компас.\n" +
                        "✓ Точки інтересу (POI): показ 22 категорій, фільтрація, кластеризація.\n" +
                        "✓ BRouter маршрутизація: прокладання шляхів (Авто, Пішки, Велосипед) з вибором відрізків.\n" +
                        "✓ Лінійка: вимірювання відстаней та авто-маршрут з 1 точки за вашим GPS.\n" +
                        "✓ База адрес addr.db: миттєвий офлайн-пошук адрес та створення маркерів.\n" +
                        "✓ Грибні точки: створення, автоназва від POI, редагування, колір, експорт у GPX.\n" +
                        "✓ GPS-треки: фоновий запис, фільтрація шумів, підрахунок кілометражу, GPX імпорт/експорт.\n" +
                        "✓ AI Визначник грибів: розпізнавання до 5 фото за локальною моделлю.\n" +
                        "✓ Енциклопедія грибів: пошук та перегляд сотень видів у базі SQLite."
                    ),
                    HelpChapter(
                        "📌 Глава 2: Потребує ОНЛАЙН (інтернет перед виїздом у ліс)",
                        "✓ Початкове завантаження векторних карт країн (.map), POI (.poi) та маршрутизації (.rd5).\n" +
                        "✓ Завантаження бази адрес (addr.db).\n" +
                        "✓ Завантаження бази енциклопедії (mushrooms.db ~538 МБ).\n" +
                        "✓ Завантаження файлів нейромережі (model.zip ~60 МБ).\n" +
                        "✓ Перевірка та автоматичне оновлення версії додатка з GitHub."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "💾", "Структура пам'яті пристрою",
                listOf(
                    HelpChapter(
                        "📌 Глава 1: Папка /sdcard/mushroom",
                        "• /sdcard/mushroom/maps/ — файли векторних карт (.map).\n" +
                        "• /sdcard/mushroom/navigation/ — сегменти маршрутизації BRouter (.rd5).\n" +
                        "• /sdcard/mushroom/poi/ — точки інтересу (.poi) та poi_config.json.\n" +
                        "• /sdcard/mushroom/addresses/ — база адрес (addr.db).\n" +
                        "• /sdcard/mushroom/markers/ — збережені маркери (markers.json).\n" +
                        "• /sdcard/mushroom/tracks/ — збережені та експортовані GPX-треки.\n" +
                        "• /sdcard/mushroom/mushrooms/ — база енциклопедії mushrooms.db та AI модель.\n" +
                        "• /sdcard/mushroom/settings/ — налаштування додатка.\n" +
                        "• Усі файли в папці /sdcard/mushroom надійно зберігаються при оновленнях та перевстановленнях додатку."
                    )
                ),
                defaultExpanded = false
            )
        } else {
            addSpoilerSection(
                "🗺️", "Mapsforge Vector Maps, Navigation & Gestures",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Map Navigation Gestures",
                        "• Pan: drag with a single finger to browse the map freely.\n" +
                        "• Pinch-to-zoom: spread or pinch two fingers to zoom smoothly (supports zoom levels z2 to z18).\n" +
                        "• Double-tap Drag: double-tap, hold, and drag up/down to rapidly zoom and rotate around a fixed pivot lever.\n" +
                        "• Rotation: rotate with two fingers to orient the map towards your travel heading.\n" +
                        "• Create & Edit Markers: quick double-tap or long-press on an empty spot creates a waypoint; tapping an existing marker opens edit or delete options."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: On-Screen Controls & Header Buttons",
                        "• Menu (top left): main drawer menu (Maps, Markers, Tracks, Locations, Classifier, Encyclopedia, Settings).\n" +
                        "• Locations (POI button, left of ruler): rapid selection and category filtering for 22 Points of Interest categories.\n" +
                        "• Ruler (left of flag): activates distance measurement and BRouter route planning.\n" +
                        "• Create Marker (flag icon): instantly saves a waypoint at your current GPS coordinates.\n" +
                        "• Record Track: start (blue button) and stop (red STOP square) background path recording.\n" +
                        "• Center on Me (crosshair): snaps the camera to your GPS position with auto-follow.\n" +
                        "• Compass (arrow): sensor-driven heading indicator; single tap aligns the map strictly to North.\n" +
                        "• Download/Update Banner (top right): prompts when viewing an area lacking vector map coverage or when updates are available."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Offline Vector Mapsforge Engine",
                        "• 100% OFFLINE: renders maps directly from local .map vector files without raster tiles, web caches, or mobile connectivity.\n" +
                        "• Priority Render Queue (LIFO): visible screen center tiles render first (~10 ms per tile).\n" +
                        "• Smooth Multi-Tier Fallback: dynamic overscaling and underscaling during zooms eliminate blank screens."
                    )
                ),
                defaultExpanded = true
            )

            addSpoilerSection(
                "", "Points of Interest (Locations / POI)",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Categories & Setup",
                        "• Location pin button on map or 'Locations' menu opens 22 official POI categories.\n" +
                        "• Available categories: Cafes/Restaurants, Grocery/Shops, Hospitals/Pharmacies, Gas/EV Chargers, Tourism/Attractions, Accommodation/Hotels, Sports, Transit, etc.\n" +
                        "• Search field at the top: instantly filters categories by keyword.\n" +
                        "• Checkboxes: toggle category visibility; preferences persist in /sdcard/mushroom/poi/poi_config.json."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Map Display & Clustering",
                        "• POI icons appear on map starting at zoom z13; adjacent points cluster into badges with counter numbers.\n" +
                        "• POI labels are displayed starting at zoom z15 to maintain clear map visibility.\n" +
                        "• Data is queried 100% offline directly from local .poi files."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Smart Marker Auto-Naming",
                        "• When adding a waypoint within 50 meters of any visible POI, the marker title is automatically filled with that location's name."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = LocationIconDrawable(density, Color.parseColor("#10B981"), 16)
            )

            addSpoilerSection(
                "📏", "Interactive Ruler & BRouter Route Planning",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Distance Measurement Mode",
                        "• Activation: tap the ruler button on the map (highlighted in tactical cyan #00E5FF).\n" +
                        "• Single tap on map: places a measurement node; displays real-time badge (segment length / cumulative total distance from start).\n" +
                        "• Node manipulation: tap a node to delete it; long-press and drag to move it; tap the line connecting nodes to insert an intermediate point."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: BRouter Route Planning (Automatic for 1 Point!)",
                        "• The 'Route' button in the top right appears for multiple points and for a SINGLE point!\n" +
                        "• 1-Point Route Behavior: with only 1 point on the ruler, clicking 'Route' automatically appends your current GPS user position as the starting point and calculates routes directly to the target!\n" +
                        "• Transport profile selection: Car (car-test), Foot (hiking/foot), Bicycle (trekking), or Dismiss (returns to basic ruler).\n" +
                        "• BRouter calculates up to 3 alternative routes between each point pair using offline .rd5 navigation tiles (5°x5° grid).\n" +
                        "• Duplicate paths are filtered out, and intersections are split into clean segments."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Alternative Selection & Save to Track",
                        "• Alternative paths are shown in gray; selected path is highlighted in bright cyan-blue.\n" +
                        "• Tapping any alternative selects it and shows a toast with distance and terrain details.\n" +
                        "• Exiting ruler mode (when points > 1) prompts: 'Save Track' (stores chosen BRouter segments and straight spans into track; distance in km is included in title), 'Delete' (resets nodes), or 'Cancel'."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🏠", "Address Database, Points of Interest (POI) & Search",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Local SQLite Database addr.db",
                        "• Stored at /sdcard/mushroom/addresses/addr.db.\n" +
                        "• SQLite schema: normalized admin_places -> streets -> houses hierarchy (coordinates in microdegrees, WITHOUT ROWID), plus comprehensive Points of Interest table poi_places linked to poi_types catalog.\n" +
                        "• Comprehensive 180 canonical OSM tags: complete object categorization (hospitals, pharmacies, gas stations, EV charging, drinking water, springs, forests, parks, restrooms, etc.) with automatic multi-source translation via Google Translate and MyMemory APIs.\n" +
                        "• FTS5 / FTS4 full-text index: indexes settlements, street names, house numbers, POI names, localized descriptions, and system tags."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Intelligent Hybrid Search (Addresses + POI)",
                        "• Access: Markers menu -> 'Add Address' button (under 'Paste from clipboard').\n" +
                        "• Real-time hybrid search: instantly resolves addresses, street names, and Points of Interest by name or localized category (e.g., 'gas station', 'charging', 'pharmacy', 'forest', 'spring', 'water', 'park').\n" +
                        "• Semantic synonyms & isolation: parses colloquial phrases with strict category boundaries (recreation parks are separated from parking lots; pharmacies from general clinics).\n" +
                        "• Alphanumeric house parsing: recognizes hyphenated and combined house numbers (68-B, 68b, 68 b).\n" +
                        "• Dynamic 20 / 10 quota allocation (limit 30): when both match, balances up to 20 addresses and 10 POIs; if only one matches or either has fewer results, leftover slots are dynamically allocated to the other.\n" +
                        "• GPS proximity sorting: with active GPS location, suggestions are ranked automatically by nearest distance.\n" +
                        "• Thematic emoji badges: each POI type displays a distinct visual icon (💊, ⛽, ⚡, 🏥, 🌲, 💧, 🌳, 🚻, etc.)."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Waypoint Creation & Routing",
                        "• Selecting any result opens the standard waypoint creation dialog with exact coordinates and title pre-filled.\n" +
                        "• Saved points can immediately be routed to via BRouter ruler (foot, car, bicycle) or exported to standard GPX."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "", "Mushroom Spots & Markers",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Marker Creation & Editing",
                        "• Quick double-tap or long-press on map: adds waypoint (auto-names if near a POI).\n" +
                        "• Floating flag button: saves point at your real-time GPS coordinates.\n" +
                        "• 'Add Address' dialog: saves point from offline address database.\n" +
                        "• Paste from clipboard: imports coordinates from Google Maps, messengers, or geo-links.\n" +
                        "• Marker details: name, mushroom type (Porcini, Chanterelle, Boletus, etc.), notes, and custom color from palette."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Markers Menu & GPX Export",
                        "• Markers menu: list of all spots, one-click camera centering, edit and delete.\n" +
                        "• Visibility checkboxes: toggle display of individual or all markers on the map.\n" +
                        "• GPX Export: export waypoints to standard .gpx files for sharing or handheld GPS import.\n" +
                        "• Storage: 100% OFFLINE in /sdcard/mushroom/markers/markers.json."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )

            addSpoilerSection(
                "", "Track Recording & GPS Logging",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Background Logging Service",
                        "• Map track button: start (blue polyline) and stop (red STOP square).\n" +
                        "• Runs via Foreground Service with ongoing notification; immune to Android Doze Mode throttling.\n" +
                        "• GPS noise filter: logs coordinates only when displacement >= 2.5 m and accuracy <= 35 m.\n" +
                        "• Live metrics: active distance counter and elapsed trip duration."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Tracks Library & GPX Support",
                        "• Tracks menu: shows route list; each track title displays total distance in km in parentheses (e.g. `Forest Path (4.25 km)`), duration, and point count.\n" +
                        "• Checkboxes to display multiple tracks simultaneously on map in distinct colors.\n" +
                        "• Complete import and export of standard GPX files."
                    )
                ),
                defaultExpanded = false,
                iconDrawable = TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )

            addSpoilerSection(
                "📥", "Data Downloads & App Updates",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Strict Download Sequence",
                        "• Setup and update operations execute in a strict priority order:\n" +
                        "  1. App Version (checks and downloads latest APK via GitHub).\n" +
                        "  2. World Overview Map (world.map).\n" +
                        "  3. Country Vector Map (.map) — allocated 50% of the progress bar scale, displaying region count (e.g. Ukraine, 25 regions).\n" +
                        "  4. Points of Interest (POI, .poi) — downloaded immediately after the country map.\n" +
                        "  5. BRouter Routing Segments (.rd5).\n" +
                        "  6. Address Database (addr.db) — step-by-step ingestion across 25 country regions with live feedback.\n" +
                        "  7. Mushroom Encyclopedia (mushrooms.db).\n" +
                        "  8. AI Neural Network Classifier (model.zip)."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Unobtrusive Bottom Progress Bar",
                        "• When downloads start, the setup dialog dismisses immediately.\n" +
                        "• Progress is indicated by a slim horizontal bar at the very bottom of the map screen with a sleek floating status pill, never obstructing user actions.\n" +
                        "• 50% of the progress bar is reserved for the country map download.\n" +
                        "• App updates prompt APK installation and restart immediately upon completion."
                    ),
                    HelpChapter(
                        "📌 Chapter 3: Country Packages & Update Checks",
                        "• Maps menu: downloads are strictly organized by whole sovereign countries (195+ countries).\n" +
                        "• Bundle includes vector map (.map), POIs (.poi), and routing segments (.rd5).\n" +
                        "• Automatic server timestamp checks display [Update] or [Ready] status tags."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🔬", "AI Mushroom Classifier (Neural Network)",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Multi-Angle Photo Capture",
                        "• Camera and Gallery buttons support uploading up to 5 photos of a specimen.\n" +
                        "• Recommended angles: 1) top cap, 2) hymenophore (gills or pores) underneath, 3) whole stem, 4) longitudinal flesh cut, 5) natural habitat (moss, tree, soil).\n" +
                        "• Combine instant camera photos and existing gallery images."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: On-Device Inference & Results",
                        "• '🔍 Identify Mushroom' button executes neural network ensemble inference.\n" +
                        "• Operates 100% OFFLINE: model weights reside in model.zip (~60 MB) and run directly on smartphone CPU.\n" +
                        "• Displays top matching species with confidence ratings.\n" +
                        "• Tapping a result card opens the species article in the Encyclopedia."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "📖", "Offline Mushroom Encyclopedia",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Offline Knowledge Base",
                        "• 100% autonomous operation from local SQLite database (mushrooms.db ~538 MB); zero network calls.\n" +
                        "• Rapid search by Ukrainian, Latin scientific, or English names."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Filtering & Detailed Morphology",
                        "• Combined filter: edibility (🟢 Edible, 🔴 Inedible, ☠️ Deadly Poisonous) and hymenophore (🧽 Pores, 🍂 Gills, 🍄 Other).\n" +
                        "• Species profile: photo galleries, cap/stem traits, fruiting seasons, distribution, and critical warnings about poisonous lookalikes."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🔋", "Background Operation & Battery Optimization",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Android Doze Mode Exemption",
                        "• Menu item '🔋 Background Service (Unrestricted)' opens system battery settings for Mushroom.\n" +
                        "• Ensure 'Unrestricted' is selected so Android will not suspend the GPS chip when the screen is locked in your pocket."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: Orientation Sensors & Compass",
                        "• App blends magnetometer and accelerometer sensors for fluid user arrow orientation.\n" +
                        "• Compass button on map immediately rotates view back to geographic North."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "🌐", "Summary: Offline vs Online Operations",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: 100% OFFLINE Operations (in deep forest)",
                        "✓ Mapsforge vector map rendering: pan, zoom, rotate, compass.\n" +
                        "✓ Points of Interest (POI): 22 categories, search, cluster badges.\n" +
                        "✓ BRouter navigation: routes (Car, Foot, Bike) with segment selection.\n" +
                        "✓ Ruler: distance measurement and 1-point auto-route from your GPS.\n" +
                        "✓ Address database addr.db: rapid offline address lookup and marker creation.\n" +
                        "✓ Mushroom waypoints: add, POI auto-name, edit, custom palette colors, GPX export.\n" +
                        "✓ GPS track recording: noise filtering, km metrics, GPX import/export.\n" +
                        "✓ AI Classifier: recognition of up to 5 photos with local model weights.\n" +
                        "✓ Encyclopedia: search and review hundreds of species in SQLite database."
                    ),
                    HelpChapter(
                        "📌 Chapter 2: ONLINE Operations Required (before entering forest)",
                        "✓ Initial download of country vector maps (.map), POIs (.poi), and routing (.rd5).\n" +
                        "✓ Download of address database (addr.db).\n" +
                        "✓ Download of encyclopedia database (mushrooms.db ~538 MB).\n" +
                        "✓ Download of AI neural network archive (model.zip ~60 MB).\n" +
                        "✓ Checking and downloading app APK updates via GitHub Releases."
                    )
                ),
                defaultExpanded = false
            )

            addSpoilerSection(
                "💾", "Device Storage Structure",
                listOf(
                    HelpChapter(
                        "📌 Chapter 1: Folder /sdcard/mushroom",
                        "• /sdcard/mushroom/maps/ — vector map files (.map).\n" +
                        "• /sdcard/mushroom/navigation/ — BRouter routing segments (.rd5).\n" +
                        "• /sdcard/mushroom/poi/ — Points of Interest (.poi) and poi_config.json.\n" +
                        "• /sdcard/mushroom/addresses/ — address database (addr.db).\n" +
                        "• /sdcard/mushroom/markers/ — saved waypoints (markers.json).\n" +
                        "• /sdcard/mushroom/tracks/ — recorded and imported GPX tracks.\n" +
                        "• /sdcard/mushroom/mushrooms/ — encyclopedia mushrooms.db and AI model.\n" +
                        "• /sdcard/mushroom/settings/ — app configuration files.\n" +
                        "• All content in /sdcard/mushroom completely survives app updates and reinstalls."
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
                    routeWaypoints.clear()
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
                    routeWaypoints.clear()
                }
                invalidate()
            }
        val routeSegments = mutableListOf<RouteSegment>()
        val routeWaypoints = mutableListOf<Pair<Double, Double>>()

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

        private val poiCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#10B981")
        }
        private val poiRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 1.8f * resources.displayMetrics.density
        }
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
            val totalSpans = (routeSegments.maxOfOrNull { it.spanIndex } ?: -1) + 1
            var total = 0f
            for (s in 0 until totalSpans) {
                val sel = routeSegments.firstOrNull { it.spanIndex == s && it.isSelected }
                    ?: routeSegments.firstOrNull { it.spanIndex == s }
                total += sel?.distanceMeters ?: 0f
            }
            return total
        }

        fun findRouteSegmentAt(screenX: Float, screenY: Float, maxDistPx: Float): RouteSegment? {
            if (!isRouteMode || routeSegments.isEmpty()) return null
            var bestSeg: RouteSegment? = null
            var minDist = maxDistPx

            for (seg in routeSegments) {
                if (seg.points.size < 2) continue
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
                    if (d < minDist) {
                        minDist = d
                        bestSeg = seg
                    }
                }
            }
            return bestSeg
        }

        private var cachedPoiMinLat = 0.0
        private var cachedPoiMaxLat = 0.0
        private var cachedPoiMinLon = 0.0
        private var cachedPoiMaxLon = 0.0
        private var cachedPoiZoom = -1f
        private var isPoiLoading = false

        private val poiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "PoiLoader").apply { isDaemon = true }
        }
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
                                    for (s in routeSegments) {
                                        if (s.spanIndex == hitSeg.spanIndex) {
                                            s.isSelected = (s.id == hitSeg.id)
                                        }
                                    }
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
            activeOnEnd: Boolean,
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
                if (activeOnEnd) compassButton.setActive(true)
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
                        if (activeOnEnd) compassButton.setActive(true)
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

        fun zoomIn() {
            if (zoomLevel < MAX_MAP_ZOOM) {
                setFollowLocation(false)
                setFollowHeading(false)
                zoomLevel = (zoomLevel + 1.0f).coerceAtMost(MAX_MAP_ZOOM)
                saveMapState()
                invalidate()
            }
        }

        fun zoomOut() {
            if (zoomLevel > MIN_MAP_ZOOM) {
                setFollowLocation(false)
                setFollowHeading(false)
                zoomLevel = (zoomLevel - 1.0f).coerceAtLeast(MIN_MAP_ZOOM)
                saveMapState()
                invalidate()
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
            compassHeading = metrics.compassHeading
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
                        activeOnEnd = true,
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
            drawOsmTiles(canvas, cx, cy, baseZoom, scale)

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

        private fun drawOsmTiles(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
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
