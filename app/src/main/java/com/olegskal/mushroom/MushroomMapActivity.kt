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
    private lateinit var btnCenter: CenterLocationButton
    private lateinit var compassButton: CompassButton
    private lateinit var btnRuler: RulerButton
    private lateinit var btnAddMarker: FlagMarkerButton
    private lateinit var btnRecordTrack: TrackRecordButton
    private lateinit var btnMenu: Button
    private lateinit var btnRouteToggle: Button
    private lateinit var progressBarRoute: ProgressBar
    private lateinit var btnDownloadMapBanner: Button

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
        if (!follow && ::mapView.isInitialized) {
            mapView.cancelCenterAnimation()
        }
        if (::btnCenter.isInitialized) {
            btnCenter.setActive(!follow)
        }
    }

    fun setFollowHeading(follow: Boolean) {
        isFollowHeading = follow
        if (!follow && ::mapView.isInitialized) {
            mapView.cancelBearingAnimation()
            mapView.resetHeadingTracking()
        }
        if (::compassButton.isInitialized) {
            compassButton.setBearing(-mapView.mapBearing, active = (isFollowHeading || abs(mapView.mapBearing % 360f) > 0.5f))
        }
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

        btnCenter = CenterLocationButton(this).apply {
            layoutParams = ctrlParams
            contentDescription = "Center on Current Location"
            setActive(!isFollowLocation)
            setOnClickListener {
                setFollowLocation(true)
                mapView.centerOnCurrentLocation()
            }
        }

        compassButton = CompassButton(this).apply {
            layoutParams = ctrlParams
            setBearing(-mapView.mapBearing, active = (isFollowHeading || abs(mapView.mapBearing % 360f) > 0.5f))
            setOnClickListener {
                handleCompassClick()
            }
        }

        topHeaderRow.addView(btnMenu)
        topHeaderRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        topHeaderRow.addView(btnRuler)
        topHeaderRow.addView(btnAddMarker)
        topHeaderRow.addView(btnRecordTrack)
        topHeaderRow.addView(btnCenter)
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
        val isNotNorth = (abs(mapView.mapBearing % 360f) > 0.5f)

        if (isFollowHeading) {
            setFollowHeading(false)
            mapView.animateBearingTo(0f, activeOnEnd = false)
            val msg = if (isUk) "Карту вирівняно на північ" else "Map aligned to North"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else if (isNotNorth) {
            setFollowHeading(false)
            mapView.animateBearingTo(0f, activeOnEnd = false)
            val msg = if (isUk) "Карту вирівняно на північ" else "Map aligned to North"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            setFollowHeading(true)
            setFollowLocation(true)
            mapView.centerOnCurrentLocation()
            val targetHeading = mapView.getUserHeading() ?: 0f
            mapView.animateBearingTo(targetHeading, activeOnEnd = true)
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
            if (mapView.rulerPoints.size <= 1) return
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
        val show = mapView.isRulerMode && mapView.rulerPoints.size > 1
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

    fun updateMapDownloadBanner() {
        if (!::btnDownloadMapBanner.isInitialized) return
        val country = MapDownloadManager.findCountryForLocation(mapView.mapCenterLat, mapView.mapCenterLon)
        if (country == null) {
            btnDownloadMapBanner.visibility = View.GONE
            return
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
                "🗺️", "Векторні карти Mapsforge, навігація та жести",
                "• Жести керування картою:\n" +
                "  — Переміщення: проведення одним пальцем по карті.\n" +
                "  — Масштаб (Pinch-to-zoom): плавне зведення або розведення двох пальців.\n" +
                "  — Дабл-тап драг: подвійний тап з утриманням і рухом пальця для швидкого масштабування та обертання навколо фіксованого якоря.\n" +
                "  — Обертання: поворот двома пальцями для довільного орієнтування карти.\n" +
                "  — Додавання та видалення міток: швидкий подвійний тап або довге натискання на вільному місці карти створює новий маркер, а безпосередньо по існуючому маркеру — відкриває діалог його видалення або редагування.\n" +
                "• Кнопки екрана карти:\n" +
                "  — «Локації» (зліва від лінійки): відкриває вибір категорій точок інтересу (POI) та керування їх відображенням.\n" +
                "  — «Лінійка» (зліва від мітки): вмикає режим вимірювання дистанції та побудови маршрутів BRouter.\n" +
                "  — «Створити маркер» (прапорець): збереження грибної точки за поточними GPS-координатами.\n" +
                "  — «Запис треку»: старт або зупинка фонового запису маршруту з індикацією відстані й часу.\n" +
                "  — «Центрувати на мені» (приціл праворуч): центрує карту на поточному GPS-положенні та вмикає автослідування.\n" +
                "  — «Компас» (стрілка праворуч): показує азимут півночі за сенсорами; одиночний тап вирівнює карту на північ.\n" +
                "  — «Меню» (зверху ліворуч): головне меню застосунку.\n" +
                "  — Банер завантаження/оновлення (вгорі праворуч): з'являється автоматично, якщо для регіону огляду ще не завантажено векторну карту або доступне оновлення.\n" +
                "• Режим роботи: 100% ОФЛАЙН. Векторний рушій Mapsforge відтворює карту з файлів .map автономно без растрових тайлів та інтернету."
            )
            addSection(
                "", "Точки інтересу («Локації»)",
                "• Призначення та налаштування:\n" +
                "  — Кнопка з міткою на панелі або пункт «Локації» у головному меню відкривають список 22 категорій POI.\n" +
                "  — Доступні всі офіційні категорії: Кафе/ресторани, Магазини, Лікарні/аптеки, Заправки/АЗС/зарядки електромобілів, Туризм/пам'ятки, Проживання/готелі, Спорт, Транспорт тощо.\n" +
                "  — Поле пошуку вгорі списку категорій: миттєво фільтрує категорії за назвою.\n" +
                "  — Чекбокси: вибір активних категорій для показу. Вибір автоматично запам'ятовується у файлі /sdcard/mushroom/poi/poi_config.json.\n" +
                "• Відображення на мапі:\n" +
                "  — Точки інтересу відображаються на всіх масштабах; сусідні точки кластеризуються у бейджі з лічильником, а назви — з масштабу z15.\n" +
                "  — Автоматична назва маркера: при створенні грибної точки поряд із видимою точкою інтересу (в радіусі 50 м) назва маркера автоматично заповнюється назвою цієї локації.\n" +
                "• Режим роботи: 100% ОФЛАЙН. Точки зчитуються безпосередньо з локальних файлів .poi.",
                LocationIconDrawable(density, Color.parseColor("#10B981"), 16)
            )
            addSection(
                "📏", "Інтерактивна лінійка та режим «Маршрут» (BRouter)",
                "• Режим лінійки:\n" +
                "  — Вмикається кнопкою з лінійкою (підсвічується бірюзовим #00E5FF).\n" +
                "  — Одинарний тап по карті: додає точку вимірювання та відображає над нею відстань (відстань відрізка / сумарна відстань від старту).\n" +
                "  — Тап по існуючій точці видаляє її, тап із протяжкою — переміщує, тап на пряму лінію — вставляє проміжну точку.\n" +
                "• Режим створення маршруту BRouter:\n" +
                "  — Коли на лінійці встановлено більше 1-ї точки, у правому верхньому куті з'являється кнопка «Маршрут».\n" +
                "  — При натисканні пропонується вибір типу транспорту: «Автомобіль», «Пішки», «Велосипед» або «Відхилити» (повернення до лінійки).\n" +
                "  — BRouter автономно прокладає до 3 варіантів маршруту між кожною парою сусідніх точок за навігаційними сегментами .rd5.\n" +
                "  — Дублікати та непридатні шляхи автоматично відсіюються. При перетині маршрути розбиваються на дрібніші відрізки.\n" +
                "  — Пропозиції відображаються сірими лініями, а обраний варіант — яскраво-блакитним кольором.\n" +
                "  — Клік по варіанту відрізка обирає його та показує спливаючу підказку про довжину й переваги обраного шляху.\n" +
                "• Завершення та вихід:\n" +
                "  — При виході з режиму лінійки, якщо точок більше 1, пропонується діалог: «Зберегти трек» (у трек записуються обрані блакитні ділянки BRouter, а не обрані сполучає пряма лінія; у списку треків у дужках відображається довжина у км), «Видалити» (скидання точок) або «Скасувати». Якщо точок 0 або 1 — режим закривається одразу.\n" +
                "• Режим роботи: 100% ОФЛАЙН на базі вбудованих профілів BRouter."
            )
            addSection(
                "", "Грибні точки та маркери",
                "• Додавання та видалення на карті:\n" +
                "  — Створення: подвійний або довгий тап по карті. Якщо поруч є точка інтересу POI, її назва підставляється автоматично.\n" +
                "  — Плаваюча кнопка з прапорцем: швидке збереження точки за поточними GPS-координатами вашого місцезнаходження.\n" +
                "  — Видалення: подвійний або довгий тап безпосередньо по маркеру відкриває підтвердження видалення або редагування.\n" +
                "• Керування в меню «Маркери»:\n" +
                "  — Створення маркерів вручну, вставка координат із буфера обміну (з месенджерів або Google Maps).\n" +
                "  — Чекбокси видимості, вибір індивідуального кольору з палітри, експорт у GPX.\n" +
                "• Режим роботи: 100% ОФЛАЙН у базі SQLite (mushroom.db).",
                MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )
            addSection(
                "", "Запис та аналіз GPS-треків",
                "• Керування записом:\n" +
                "  — Кнопка запису на карті: старт (синя лінія) та зупинка (червоний бейдж STOP).\n" +
                "  — У меню треків: показ дистанції у км (в дужках у назві), тривалості, чекбокси показу на карті, імпорт та експорт стандартних GPX-файлів.\n" +
                "• Режим роботи: 100% ОФЛАЙН. Фоновий сервіс з фільтрацією GPS-шумів працює автономно.",
                TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )
            addSection(
                "📥", "Завантаження карт по країнах",
                "• Завантаження за країнами:\n" +
                "  — Меню «🗺️ Карти»: завантаження виконується виключно по цілих країнах (понад 195 суверенних країн світу). Області прибрано.\n" +
                "  — Пакет країни включає: векторну карту (.map), точки інтересу (.poi) та навігаційні файли BRouter (.rd5 по сітці 5°x5°).\n" +
                "  — Зберігання: карти map — у папку maps, файли poi — у папку poi, файли навігації rd5 — у папку navigation.\n" +
                "  — Перевірка оновлень: автоматичний аналіз актуальності карт на сервері з відображенням бейджів [Оновлення] або [Завантажено].\n" +
                "  — Модальний діалог із відображенням відсотків завантаження та кнопкою зупинки.\n" +
                "• Режим роботи: ПОТРЕБУЄ ОНЛАЙН лише під час завантаження файлів (Wi-Fi рекомендовано перед виїздом у ліс)."
            )
            addSection(
                "🔬", "AI Визначник грибів (Нейромережа)",
                "• Керування та процес розпізнавання:\n" +
                "  — Кнопки «📷 Камера» та «🖼️ Галерея»: підтримка до 5 фото гриба (капелюшок, ніжка, гіменофор, розріз тощо) для комбінованого ансамблевого аналізу.\n" +
                "  — Кнопка «🔍 Визначити гриб»: запуск автономного класифікатора.\n" +
                "  — Тап по картці результату відкриває повну довідку виду в Енциклопедії.\n" +
                "• Що завантажується: архів моделі нейромережі model.zip (~60 МБ). Розпаковується один раз.\n" +
                "• Режим роботи: 100% ОФЛАЙН після завантаження моделі."
            )
            addSection(
                "📖", "Офлайн Енциклопедія грибів",
                "• Керування та пошук:\n" +
                "  — 100% автономна робота: онлайн-режим прибрано для гарантованої безпеки й доступності в лісі без інтернету.\n" +
                "  — Пошуковий рядок: швидкий пошук за українською, латинською або англійською назвою.\n" +
                "  — Комплексний фільтр: фільтрація за їстівністю (🟢 Їстівні, 🔴 Неїстівні) та гіменофором (🧽 Трубчасті, 🍂 Пластинчасті, 🍄 Інші).\n" +
                "  — Картка виду: фотографії, морфологічні ознаки, період збору та застереження про смертельні двійники.\n" +
                "• Режим роботи: 100% ОФЛАЙН на базі локальної бази SQLite (mushrooms.db ~538 МБ)."
            )
            addSection(
                "🔋", "Фоновий режим та оптимізація батареї",
                "• Керування:\n" +
                "  — Пункт меню «🔋 Робота у фоні (без обмежень)» відкриває налаштування Doze Mode Android.\n" +
                "  — Дозвольте роботу без обмежень батареї, щоб система не присипляла GPS при вимкненому екрані.\n" +
                "• Режим роботи: 100% ОФЛАЙН."
            )
            addSection(
                "🌐", "Зведення: Що працює Офлайн, а що Онлайн",
                "• Працює 100% ОФЛАЙН (у лісі без мобільного зв'язку):\n" +
                "  ✓ Векторні карти Mapsforge: масштабування, панорамування, обертання, компас.\n" +
                "  ✓ Точки інтересу (POI): показ лікарень, АЗС, магазинів, транспорту з фільтрацією категорій.\n" +
                "  ✓ Створення та пошук маршрутів BRouter (Авто, Пішки, Велосипед) з вибором відрізків.\n" +
                "  ✓ Лінійка вимірювання дистанцій та збереження у трек.\n" +
                "  ✓ Визначення точних GPS-координат, компас та вирівнювання на північ.\n" +
                "  ✓ Створення, редагування, автоназва від POI та експорт грибних міток.\n" +
                "  ✓ Фоновий запис та перегляд GPS-треків.\n" +
                "  ✓ AI розпізнавання грибів нейромережею (з локальною моделлю).\n" +
                "  ✓ Офлайн Енциклопедія грибів (локальна база SQLite).\n" +
                "• Потребує ОНЛАЙН (інтернет перед виходом у ліс):\n" +
                "  ✓ Завантаження комплекту країни (.map, .poi, .rd5).\n" +
                "  ✓ Перевірка та завантаження оновлень карт і навігації.\n" +
                "  ✓ Одноразове завантаження моделі нейромережі model.zip (~60 МБ).\n" +
                "  ✓ Одноразове завантаження бази енциклопедії mushrooms.db (~538 МБ).\n" +
                "  ✓ Автоматичне оновлення версії додатку через GitHub."
            )
        } else {
            addSection(
                "🗺️", "Mapsforge Vector Maps, Navigation & Gestures",
                "• Map Navigation Gestures:\n" +
                "  — Pan: drag with a single finger across the map.\n" +
                "  — Pinch-to-zoom: spread or pinch two fingers to zoom smoothly.\n" +
                "  — Double-tap Drag: double-tap, hold, and drag to rapidly zoom and rotate around a fixed pivot lever.\n" +
                "  — Rotation: rotate with two fingers to orient the map at any bearing.\n" +
                "  — Add & Delete Markers: quick double-tap or long-press on an empty map spot creates a new marker; directly tapping an existing marker prompts deletion or editing.\n" +
                "• Map Screen Buttons:\n" +
                "  — Locations (POI, left of ruler): opens 22 Points of Interest categories and visibility filters.\n" +
                "  — Ruler (left of flag): activates distance measurement and BRouter route planning.\n" +
                "  — Create Marker (flag icon): saves waypoint at your current real-time GPS position.\n" +
                "  — Record Track: starts/stops background path logging with active distance & time metrics.\n" +
                "  — Center on Me (crosshair): snaps camera to your GPS location with auto-follow.\n" +
                "  — Compass (arrow): sensor-driven heading indicator; single tap aligns map to North.\n" +
                "  — Menu (top left): opens the main application drawer.\n" +
                "  — Download/Update Banner (top right): appears automatically when viewing an area without offline vector coverage or when updates are available.\n" +
                "• Operating Mode: 100% OFFLINE. Native Mapsforge vector engine renders .map files locally without raster tiles or data network."
            )
            addSection(
                "", "Points of Interest (Locations)",
                "• Purpose & Setup:\n" +
                "  — Location pin button on top header or 'Locations' in the main menu opens 22 official Mapsforge POI categories.\n" +
                "  — Includes: Cafes/Restaurants, Grocery/Shops, Hospitals/Pharmacies, Gas/EV Chargers, Tourism/Attractions, Accommodation, Sports, Transportation, etc.\n" +
                "  — Search field at the top of the category list: instantly filters categories by keyword.\n" +
                "  — Checkboxes: toggle category visibility. Preferences are saved automatically to /sdcard/mushroom/poi/poi_config.json.\n" +
                "• Map Display:\n" +
                "  — POI items appear at all zoom levels; nearby points cluster into count badges, with labels displayed at zoom z15 and above.\n" +
                "  — Automatic Marker Naming: when adding a waypoint near a visible POI (within 50 meters), the marker name is automatically populated with the POI name.\n" +
                "• Operating Mode: 100% OFFLINE. Directly queried from local .poi files.",
                LocationIconDrawable(density, Color.parseColor("#10B981"), 16)
            )
            addSection(
                "📏", "Interactive Ruler & BRouter Route Planning",
                "• Ruler Mode:\n" +
                "  — Enabled via the ruler icon (highlighted in tactical cyan #00E5FF).\n" +
                "  — Single tap places a point with real-time distance badges (segment distance / cumulative distance from start).\n" +
                "  — Tapping an existing point deletes it, dragging repositions it, and tapping a line inserts an intermediate point.\n" +
                "• BRouter Route Planning Mode:\n" +
                "  — When more than 1 point is placed on the ruler, the 'Route' button appears in the top right.\n" +
                "  — Tapping 'Route' prompts transport mode: Car, Foot (hiking), Bicycle, or Dismiss (returns to ruler).\n" +
                "  — BRouter calculates up to 3 alternative route variations between each pair of consecutive points using offline .rd5 navigation data.\n" +
                "  — Duplicate and substandard paths are automatically filtered out. Intersecting paths split into fine segments.\n" +
                "  — Proposed paths are drawn in gray; tapping a segment selects it in bright blue with a toast describing distance and route benefits.\n" +
                "• Exit & Save:\n" +
                "  — Exiting ruler mode with >1 points prompts: 'Save Track' (saves selected blue segments into track; unselected spans connect with straight lines; track title includes distance in km), 'Delete' (clears all points), or 'Cancel'. With 0-1 points, exits immediately.\n" +
                "• Operating Mode: 100% OFFLINE using embedded BRouter navigation profiles."
            )
            addSection(
                "", "Mushroom Spots & Markers",
                "• Map Waypoint Management:\n" +
                "  — Creation: double-tap or long-press on map. Nearest POI name within 50m is auto-filled.\n" +
                "  — Floating Flag Button: saves point at your exact current GPS coordinates.\n" +
                "  — Deletion: double-tap or long-press on marker prompts delete/edit.\n" +
                "• Markers Menu:\n" +
                "  — Manual marker creation, clipboard coordinate pasting (from messengers or Google Maps).\n" +
                "  — Visibility checkboxes, custom color palette picker, GPX export.\n" +
                "• Operating Mode: 100% OFFLINE stored in local SQLite database (mushroom.db).",
                MarkerIconDrawable(density, Color.parseColor("#F44336"), 16)
            )
            addSection(
                "", "Track Recording & GPS Logging",
                "• Recording Controls:\n" +
                "  — Floating Track Button on map: start logging (blue polyline) and stop (red STOP square).\n" +
                "  — In Tracks Menu: displays length in km (in parentheses in title), duration, map checkboxes, import and export of standard GPX files.\n" +
                "• Operating Mode: 100% OFFLINE. Background service with GPS noise filtering operates autonomously in forests.",
                TrackIconDrawable(density, Color.parseColor("#2196F3"), 16)
            )
            addSection(
                "📥", "Country Map & Navigation Downloads",
                "• Country Downloads:\n" +
                "  — '🗺️ Maps' menu item: downloads are organized strictly by entire sovereign countries (195+ countries worldwide). Sub-regions/oblasts removed.\n" +
                "  — Country package includes: vector map (.map), Points of Interest (.poi), and BRouter navigation segments (.rd5 on 5°x5° grid).\n" +
                "  — Storage folders: maps in /maps, POIs in /poi, navigation in /navigation.\n" +
                "  — Update checking: automatic server timestamp comparison showing [Update] or [Ready] status badges.\n" +
                "  — Modal progress dialog with percentage indicators and a Stop button.\n" +
                "• Operating Mode: REQUIRES ONLINE only during package download (Wi-Fi recommended before trip)."
            )
            addSection(
                "🔬", "AI Mushroom Identifier (Neural Network)",
                "• Controls & Inference Flow:\n" +
                "  — Camera and Gallery buttons: upload up to 5 photos (cap, stem, hymenophore, cross-section, habitat) for ensemble classification.\n" +
                "  — '🔍 Identify Mushroom' button: executes on-device neural network classifier.\n" +
                "  — Tap on result card: opens species profile in the Encyclopedia.\n" +
                "• What is Downloaded: model.zip neural network archive (~60 MB), unpacked once via Download button.\n" +
                "• Operating Mode: 100% OFFLINE once model is downloaded."
            )
            addSection(
                "📖", "Offline Mushroom Encyclopedia",
                "• Navigation & Search:\n" +
                "  — 100% Offline: online mode removed for complete autonomy and safety in wilderness.\n" +
                "  — Search Bar: search by Ukrainian, English, or scientific Latin names.\n" +
                "  — Filters: simultaneous edibility (🟢 Edible, 🔴 Inedible) and hymenophore (🧽 Tubes, 🍂 Gills, 🍄 Other) filtering.\n" +
                "  — Species Card: photo galleries, key traits, fruiting period, and lethal lookalike warnings.\n" +
                "• Operating Mode: 100% OFFLINE with local SQLite database (mushrooms.db ~538 MB)."
            )
            addSection(
                "🔋", "Background Service & Battery Optimization",
                "• Setup:\n" +
                "  — '🔋 Background Service (Unrestricted)' opens Android Doze Mode battery settings.\n" +
                "  — Allow unrestricted battery access so the operating system does not throttle GPS when the screen is locked.\n" +
                "• Operating Mode: 100% OFFLINE."
            )
            addSection(
                "🌐", "Summary: Offline vs Online Operations",
                "• 100% OFFLINE Operations (deep forest without cell signal):\n" +
                "  ✓ Mapsforge vector map rendering: pan, zoom, rotate, compass.\n" +
                "  ✓ Points of Interest (POI): hospitals, gas stations, shops, transit with category filters.\n" +
                "  ✓ BRouter route generation (Car, Foot, Bike) with alternative segment selection.\n" +
                "  ✓ Interactive distance measuring ruler and track conversion.\n" +
                "  ✓ Real-time GPS location tracking and North orientation.\n" +
                "  ✓ Waypoint creation, editing, nearest POI auto-naming, and GPX export.\n" +
                "  ✓ Background GPS track recording and inspection.\n" +
                "  ✓ AI neural network mushroom classification (local model).\n" +
                "  ✓ Offline Mushroom Encyclopedia (local SQLite database).\n" +
                "• REQUIRES ONLINE (internet connection before departure):\n" +
                "  ✓ Initial country package download (.map, .poi, .rd5).\n" +
                "  ✓ Checking and downloading map/navigation updates.\n" +
                "  ✓ One-time download of AI classifier model archive model.zip (~60 MB).\n" +
                "  ✓ One-time download of offline encyclopedia database mushrooms.db (~538 MB).\n" +
                "  ✓ Automatic app updates via GitHub releases."
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

        if (::compassButton.isInitialized) {
            compassButton.setBearing(-mapView.mapBearing, active = (isFollowHeading || abs(mapView.mapBearing % 360f) > 0.5f))
        }
        if (::btnCenter.isInitialized) {
            btnCenter.setActive(!isFollowLocation)
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

        MushroomTrackingService.instance?.registerGpsUpdates()
        uiHandler.post(periodicRefreshRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            MushroomTrackingService.isAppInForeground = true
            MushroomTrackingService.ensureServiceAndNotification(this)
            MushroomTrackingService.instance?.registerGpsUpdates()
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
                                compassButton.setBearing(-mapBearing, active = true)
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
                                    compassButton.setBearing(-mapBearing, active = true)
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
                            compassButton.setBearing(-mapBearing, active = true)
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
                compassButton.setBearing(-mapBearing, active = activeOnEnd)
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
                    compassButton.setBearing(-mapBearing, active = activeOnEnd || (abs(mapBearing % 360f) > 0.5f))
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mapBearing = normTarget
                        compassButton.setBearing(-mapBearing, active = activeOnEnd)
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
            currentLocation?.let {
                animateCenterTo(it.latitude, it.longitude, durationMs = 320L, saveOnEnd = true, useDecelerate = true)
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
                            compassButton.setBearing(-mapBearing, active = true)
                            invalidate()
                        } else {
                            mapBearing = (mapBearing + bDiff * 0.35f + 360f) % 360f
                            compassButton.setBearing(-mapBearing, active = true)
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
