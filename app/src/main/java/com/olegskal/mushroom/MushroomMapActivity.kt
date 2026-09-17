package com.olegskal.mushroom

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.*
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
        val MIN_MAP_ZOOM = MapDownloadManager.DEFAULT_MIN_ZOOM.toFloat()
        val MAX_MAP_ZOOM = MapDownloadManager.DEFAULT_MAX_ZOOM.toFloat()
        val MIN_BASE_ZOOM = MapDownloadManager.DEFAULT_MIN_ZOOM
        val MAX_BASE_ZOOM = MapDownloadManager.DEFAULT_MAX_ZOOM
    }

    private lateinit var mapView: MushroomMapView
    private lateinit var dbHelper: DatabaseHelper

    // UI overlays
    private lateinit var tvRecordingBadge: TextView
    private lateinit var btnCenter: Button
    private lateinit var compassButton: CompassButton

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

        isFollowLocation = AppPrefs.getFollowUser(this)

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
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (12 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menuBtnSize = (44 * resources.displayMetrics.density).toInt()
        val btnMenu = Button(this).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(menuBtnSize, menuBtnSize)
            setOnClickListener {
                showMainMenuDialog()
            }
        }

        val titleTv = TextView(this).apply {
            text = "🌲 Mushroom"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        topHeaderRow.addView(btnMenu)
        topHeaderRow.addView(titleTv)
        topInfoPanel.addView(topHeaderRow)

        tvRecordingBadge = TextView(this).apply {
            text = "⏺️ ЗАПИС ТРЕКУ: 0.00 км (00:00)"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }
        topInfoPanel.addView(tvRecordingBadge)

        rootLayout.addView(topInfoPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        // Floating Control Buttons (Right Side: Zoom & Compass & Center)
        val rightControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 0, 24, 0)
        }

        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val btnMargin = (4 * resources.displayMetrics.density).toInt()
        val ctrlParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
            setMargins(0, btnMargin, 0, btnMargin)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        compassButton = CompassButton(this).apply {
            layoutParams = ctrlParams
            setBearing(-mapView.mapBearing)
            setOnClickListener {
                mapView.alignToNorth()
                Toast.makeText(this@MushroomMapActivity, "Карту вирівняно на Північ", Toast.LENGTH_SHORT).show()
            }
        }

        btnCenter = Button(this).apply {
            text = "⌖"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = ctrlParams
            setOnClickListener {
                isFollowLocation = true
                AppPrefs.setFollowUser(this@MushroomMapActivity, true)
                mapView.centerOnCurrentLocation()
            }
        }

        val btnZoomIn = Button(this).apply {
            text = "＋"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = ctrlParams
            setOnClickListener { mapView.zoomIn() }
        }

        val btnZoomOut = Button(this).apply {
            text = "－"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = ctrlParams
            setOnClickListener { mapView.zoomOut() }
        }

        rightControls.addView(compassButton)
        rightControls.addView(btnCenter)
        rightControls.addView(btnZoomIn)
        rightControls.addView(btnZoomOut)

        val padR = (12 * resources.displayMetrics.density).toInt()
        val topOffset = (68 * resources.displayMetrics.density).toInt()
        val rightParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = topOffset
            rightMargin = padR
        }
        rootLayout.addView(rightControls, rightParams)

        setContentView(rootLayout)

        OsmTileEngine.onTileReadyListener = {
            if (!isTileRedrawPending) {
                isTileRedrawPending = true
                uiHandler.postDelayed(tileRedrawRunnable, 35L)
            }
        }

        handleIncomingIntent(intent)
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
            startActivityForResult(Intent.createChooser(intent, "Виберіть GPX файл"), REQ_CODE_IMPORT_GPX)
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка вибору файлу: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "У надісланому тексті не знайдено координат", Toast.LENGTH_SHORT).show()
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
        AddMarkerDialog.show(
            this,
            dbHelper,
            lat,
            lon,
            initialName = "Отримана мітка",
            initialType = "📍 Знайдене місце"
        ) {
            mapView.reloadMarkers()
        }
        Toast.makeText(this, String.format(Locale.US, "Отримано координати: %.5f, %.5f", lat, lon), Toast.LENGTH_LONG).show()
    }

    private fun importGpxFromUri(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
            if (stream == null) {
                Toast.makeText(this, "Не вдалося відкрити файл", Toast.LENGTH_SHORT).show()
                return
            }
            val fileName = getFileNameFromUri(uri) ?: "Імпортований трек"
            val cleanTitle = fileName.removeSuffix(".gpx").removeSuffix(".xml")
            val track = stream.use { GeoDataExchange.parseGpx(it, cleanTitle) }
            if (track != null && track.points.isNotEmpty()) {
                dbHelper.insertTrack(track)
                mapView.reloadTracks()
                isFollowLocation = false
                mapView.fitTrackBounds(track)
                val km = track.distanceMeters / 1000f
                Toast.makeText(this, "Імпортовано трек \"${track.title}\" (довжина: ${String.format(Locale.US, "%.2f", km)} км)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "У файлі не знайдено валідного GPX треку", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLogger.log("MushroomMapActivity", "importGpxFromUri", false, "Error importing GPX: ${e.message}")
            Toast.makeText(this, "Помилка читання GPX: ${e.message}", Toast.LENGTH_SHORT).show()
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

    fun toggleTrackRecording() {
        val s = MushroomTrackingService.instance
        if (s?.isRecording == true) {
            s.stopTrackRecording()
            updateRecordingUi(false, 0f, 0L)
            mapView.reloadTracks()
        } else {
            if (s != null) {
                s.startTrackRecording()
                updateRecordingUi(true, 0f, 0L)
            } else {
                val intent = Intent(this, MushroomTrackingService::class.java).apply {
                    action = MushroomTrackingService.ACTION_START_RECORDING
                }
                startService(intent)
                updateRecordingUi(true, 0f, 0L)
            }
        }
    }

    private fun updateRecordingUi(isRec: Boolean, distMeters: Float, durationSec: Long) {
        if (isRec) {
            tvRecordingBadge.visibility = View.VISIBLE
            val km = distMeters / 1000f
            val m = durationSec / 60
            val s = durationSec % 60
            tvRecordingBadge.text = String.format(Locale.US, "⏺️ ЗАПИС ТРЕКУ: %.2f км (%02d:%02d)", km, m, s)
        } else {
            tvRecordingBadge.visibility = View.GONE
        }
    }

    private fun updateLiveStats() {
        val s = MushroomTrackingService.instance
        val isRec = s?.isRecording == true
        val metrics = currentMetrics ?: MushroomTrackingService.lastMetrics
        if (metrics != null) {
            if (isRec) {
                updateRecordingUi(true, metrics.recordedDistanceMeters, metrics.recordedDurationSec)
            } else {
                updateRecordingUi(false, 0f, 0L)
            }
        }
    }

    private fun showMainMenuDialog() {
        val dialog = Dialog(this)
        val container = UiUtils.createDarkDialogContainer(this)
        val itemParams = UiUtils.createStandardItemParams()

        val titleTv = TextView(this).apply {
            text = "🌲 Меню грибника"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleTv)

        // 1. Markers
        val btnMarkers = UiUtils.createStyledButton(this, "🍄 Мітка", itemParams) {
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
        val trackText = if (isRec) "⏹️ Трек (триває запис...)" else "🧭 Трек"
        val btnTracks = UiUtils.createStyledButton(this, trackText, itemParams) {
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
        val btnDownloadMaps = UiUtils.createStyledButton(this, "🗺️ Завантажити офлайн карти", itemParams) {
            dialog.dismiss()
            RegionDownloadDialog.show(this@MushroomMapActivity) {
                mapView.invalidate()
            }
        }
        container.addView(btnDownloadMaps)
        container.addView(UiUtils.createDialogDivider(this))

        // 4. Exit
        val btnQuit = UiUtils.createStyledButton(this, "🚪 Вихід з програми", itemParams) {
            dialog.dismiss()
            val stopIntent = Intent(this@MushroomMapActivity, MushroomTrackingService::class.java).apply {
                action = MushroomTrackingService.ACTION_STOP_SERVICE
            }
            startService(stopIntent)
            finishAffinity()
        }
        container.addView(btnQuit)

        dialog.setContentView(container)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapView.reloadMarkers()
        mapView.reloadTracks()

        MushroomTrackingService.serviceStateListener = { isRunning ->
            if (!isRunning) runOnUiThread { finish() }
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
            val compassBearing = -(currentFilteredAzimuth - mapView.mapBearing)
            compassButton.setBearing(compassBearing)
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
            color = Color.WHITE
            textSize = 28f
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
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
        private var anchorX = 0f
        private var anchorY = 0f

        init {
            val savedZoom = AppPrefs.getMapZoom(context).toFloat()
            zoomLevel = savedZoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val count = event.pointerCount
            val density = resources.displayMetrics.density

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.uptimeMillis()
                    val tapDist = hypot((event.x - lastTapUpX).toDouble(), (event.y - lastTapUpY).toDouble()).toFloat()

                    if (now - lastTapUpTime < 350L && tapDist < 80f * density) {
                        isDoubleTapDrag = true
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
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isDragging = false
                        isMultiTouch = false
                    }
                    downTime = now
                    downX = event.x
                    downY = event.y
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (isDoubleTapDrag) {
                        isDoubleTapDrag = false
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
                    if (isDoubleTapDrag) {
                        isFollowLocation = false
                        val curX = event.x
                        val curY = event.y
                        val dx = curX - lastTouchX
                        val dy = curY - lastTouchY

                        // 1. Pan with moving touch
                        if (abs(dx) > 1f || abs(dy) > 1f) {
                            panMap(dx, dy)
                            lastTouchX = curX
                            lastTouchY = curY
                        }

                        // 2. Zoom and Rotate relative to anchor
                        val curDist = hypot((curX - anchorX).toDouble(), (curY - anchorY).toDouble()).toFloat()
                        val minGestureDist = 20f * density

                        if (curDist >= minGestureDist) {
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
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (abs(dx) > 3f || abs(dy) > 3f) {
                            isDragging = true
                            isFollowLocation = false
                            panMap(dx, dy)
                            lastTouchX = event.x
                            lastTouchY = event.y
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
                        AppPrefs.setMapZoom(context, zoomLevel.roundToInt())
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (isDoubleTapDrag) {
                        isDoubleTapDrag = false
                        lastTapUpTime = 0L
                        AppPrefs.setMapZoom(context, zoomLevel.roundToInt())
                        invalidate()
                    } else {
                        isDragging = false
                        isMultiTouch = false
                        val now = SystemClock.uptimeMillis()
                        val tapDist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                        if (now - downTime < 300L && tapDist < 30f * density) {
                            lastTapUpTime = now
                            lastTapUpX = event.x
                            lastTapUpY = event.y
                        } else {
                            lastTapUpTime = 0L
                        }
                        AppPrefs.setMapZoom(context, zoomLevel.roundToInt())
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    isMultiTouch = false
                    isDoubleTapDrag = false
                    lastTapUpTime = 0L
                }
            }
            return true
        }

        fun alignToNorth() {
            if (mapBearing == 0f) return
            var diff = 0f - mapBearing
            while (diff < -180f) diff += 360f
            while (diff > 180f) diff -= 360f
            val start = mapBearing
            val end = start + diff

            val animator = ValueAnimator.ofFloat(start, end).apply {
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
                    }
                })
            }
            animator.start()
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
        }

        fun centerOnCurrentLocation() {
            currentLocation?.let {
                mapCenterLat = it.latitude
                mapCenterLon = it.longitude
                invalidate()
            }
        }

        fun zoomIn() {
            if (zoomLevel < MAX_MAP_ZOOM) {
                zoomLevel = (zoomLevel + 1.0f).coerceAtMost(MAX_MAP_ZOOM)
                AppPrefs.setMapZoom(context, zoomLevel.roundToInt())
                invalidate()
            }
        }

        fun zoomOut() {
            if (zoomLevel > MIN_MAP_ZOOM) {
                zoomLevel = (zoomLevel - 1.0f).coerceAtLeast(MIN_MAP_ZOOM)
                AppPrefs.setMapZoom(context, zoomLevel.roundToInt())
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
            AppPrefs.setMapZoom(context, 13)
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

                    val bmp = OsmTileEngine.getTile(baseZoom, clampedTx, ty)
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, screenLeft, screenTop, null)
                    } else {
                        canvas.drawRect(screenLeft, screenTop, screenLeft + tileSize, screenTop + tileSize, tileGridPaint)
                    }
                }
            }
        }

        private fun drawTracks(canvas: Canvas, cx: Float, cy: Float, baseZoom: Int, scale: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, baseZoom)
            trackPaint.strokeWidth = 8f / scale
            liveTrackPaint.strokeWidth = 9f / scale

            for (track in tracksList) {
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
            val s = MushroomTrackingService.instance
            if (s?.isRecording == true) {
                val active = s.currentActiveTrack
                if (active != null && active.points.size >= 2) {
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
