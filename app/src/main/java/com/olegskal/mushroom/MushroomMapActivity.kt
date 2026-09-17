package com.olegskal.mushroom

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
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

class MushroomMapActivity : Activity() {

    companion object {
        private const val REQ_CODE_IMPORT_GPX = 1010
    }

    private lateinit var mapView: MushroomMapView
    private lateinit var dbHelper: DatabaseHelper

    // UI overlays
    private lateinit var tvTopCoords: TextView
    private lateinit var tvTopStats: TextView
    private lateinit var tvRecordingBadge: TextView
    private lateinit var btnRec: Button
    private lateinit var btnCenter: Button

    private var currentMetrics: ProcessedLocationMetrics? = null
    private var isFollowLocation: Boolean = true
    private var isHeadingUp: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
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

        MushroomStorageManager.initStorage()
        dbHelper = DatabaseHelper(this)
        dbHelper.restoreDataFromExternalStorageIfDbEmpty()

        AppUpdateManager.checkAndDownloadUpdate(this)

        isFollowLocation = AppPrefs.getFollowUser(this)
        isHeadingUp = AppPrefs.isHeadingUp(this)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Map View
        mapView = MushroomMapView(this)
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Top Header Info Bar
        val topInfoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC121212"))
            setPadding(24, 24, 24, 16)
        }

        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnClose = UiUtils.createStyledButton(this, "Згорнути") {
            finish()
        }

        val titleTv = TextView(this).apply {
            text = "🌲 Mushroom"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMenu = UiUtils.createStyledButton(this, "Меню") {
            showMainMenuDialog()
        }

        topHeaderRow.addView(btnClose)
        topHeaderRow.addView(titleTv)
        topHeaderRow.addView(btnMenu)
        topInfoPanel.addView(topHeaderRow)

        tvTopCoords = TextView(this).apply {
            text = "Координати: пошук GPS..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 2)
        }

        tvTopStats = TextView(this).apply {
            text = "Точність: -- | Швидкість: 0 км/год | Компас: --"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
        }

        tvRecordingBadge = TextView(this).apply {
            text = "⏺️ ЗАПИС ТРЕКУ: 0.00 км (00:00)"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, 6, 0, 0)
        }

        topInfoPanel.addView(tvTopCoords)
        topInfoPanel.addView(tvTopStats)
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

        val btnCompass = Button(this).apply {
            text = "🧭 Пн"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 13f
            setOnClickListener {
                isHeadingUp = !isHeadingUp
                AppPrefs.setHeadingUp(this@MushroomMapActivity, isHeadingUp)
                text = if (isHeadingUp) "🧭 Рух" else "🧭 Пн"
                Toast.makeText(this@MushroomMapActivity, if (isHeadingUp) "Орієнтація: за курсом" else "Орієнтація: Північ зверху", Toast.LENGTH_SHORT).show()
                mapView.invalidate()
            }
        }

        btnCenter = Button(this).apply {
            text = "📍 Центр"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 13f
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
            setOnClickListener { mapView.zoomIn() }
        }

        val btnZoomOut = Button(this).apply {
            text = "－"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2A2A2A"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { mapView.zoomOut() }
        }

        rightControls.addView(btnCompass)
        rightControls.addView(btnCenter)
        rightControls.addView(btnZoomIn)
        rightControls.addView(btnZoomOut)

        val rightParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.END
        )
        rootLayout.addView(rightControls, rightParams)

        // Bottom Action Bar
        val bottomActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E6181818"))
            setPadding(16, 12, 16, 16)
            gravity = Gravity.CENTER
        }

        val btnAddMarker = UiUtils.createStyledButton(this, "🍄 +Мітка") {
            val loc = mapView.currentLocation ?: currentMetrics?.location
            val lat = loc?.latitude ?: mapView.mapCenterLat
            val lon = loc?.longitude ?: mapView.mapCenterLon
            val alt = loc?.altitude ?: 0.0
            AddMarkerDialog.show(this, dbHelper, lat, lon, alt) { _ ->
                mapView.reloadMarkers()
            }
        }

        btnRec = UiUtils.createStyledButton(this, "⏺️ REC") {
            toggleTrackRecording()
        }

        val btnMarkersList = UiUtils.createStyledButton(this, "📋 Мітки") {
            val loc = mapView.currentLocation ?: currentMetrics?.location
            MarkersListDialog.show(
                this,
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

        val btnTracksList = UiUtils.createStyledButton(this, "🗺️ Треки") {
            TracksListDialog.show(
                this,
                dbHelper,
                onSelectTrack = { track ->
                    isFollowLocation = false
                    mapView.fitTrackBounds(track)
                },
                onVisibilityChanged = {
                    mapView.reloadTracks()
                }
            )
        }

        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        }
        btnAddMarker.layoutParams = p
        btnRec.layoutParams = p
        btnMarkersList.layoutParams = p
        btnTracksList.layoutParams = p

        bottomActionBar.addView(btnAddMarker)
        bottomActionBar.addView(btnRec)
        bottomActionBar.addView(btnMarkersList)
        bottomActionBar.addView(btnTracksList)

        rootLayout.addView(bottomActionBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        setContentView(rootLayout)

        OsmTileEngine.onTileReadyListener = {
            mapView.postInvalidate()
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

    private fun toggleTrackRecording() {
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
            btnRec.text = "⏹️ СТОП"
            btnRec.setTextColor(Color.parseColor("#FF5252"))
            tvRecordingBadge.visibility = View.VISIBLE
            val km = distMeters / 1000f
            val m = durationSec / 60
            val s = durationSec % 60
            tvRecordingBadge.text = String.format(Locale.US, "⏺️ ЗАПИС: %.2f км (%02d:%02d)", km, m, s)
        } else {
            btnRec.text = "⏺️ REC"
            btnRec.setTextColor(Color.WHITE)
            tvRecordingBadge.visibility = View.GONE
        }
    }

    private fun updateLiveStats() {
        val s = MushroomTrackingService.instance
        val isRec = s?.isRecording == true
        val metrics = currentMetrics ?: MushroomTrackingService.lastMetrics
        if (metrics != null) {
            val loc = metrics.location
            val latStr = String.format(Locale.US, "%.5f°", loc.latitude)
            val lonStr = String.format(Locale.US, "%.5f°", loc.longitude)
            val acc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()}м" else "--"
            val spd = "${metrics.speedKmh.toInt()} км/год"
            val alt = if (loc.hasAltitude()) "${loc.altitude.toInt()}м" else "--"
            val az = "${metrics.compassHeading.toInt()}°"

            tvTopCoords.text = "Шир: $latStr  Довг: $lonStr (Вис: $alt)"
            tvTopStats.text = "Точність: $acc | Швидк: $spd | Компас: $az | ${metrics.gpsStatusStr}"

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

        // 1. Download offline maps
        val btnDownloadMaps = UiUtils.createStyledButton(this, "🗺️ Завантажити офлайн карти", itemParams) {
            dialog.dismiss()
            RegionDownloadDialog.show(this@MushroomMapActivity) {
                mapView.invalidate()
            }
        }
        container.addView(btnDownloadMaps)
        container.addView(UiUtils.createDialogDivider(this))

        // 2. Exit
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
        uiHandler.removeCallbacks(periodicRefreshRunnable)
        MushroomTrackingService.metricsListener = null
        MushroomTrackingService.serviceStateListener = null
    }

    // --- INNER MAP CANVAS VIEW ---
    @SuppressLint("ClickableViewAccessibility")
    inner class MushroomMapView(context: Context) : View(context) {

        var mapCenterLat: Double = 50.4501
        var mapCenterLon: Double = 30.5234
        var zoomLevel: Int = 14

        var currentLocation: Location? = null
        private var compassHeading: Float = 0f
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

        // Gesture handling
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isDragging = false
        private val scaleDetector: ScaleGestureDetector

        init {
            scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    if (factor > 1.25f && zoomLevel < 18) {
                        zoomLevel++
                        invalidate()
                        return true
                    } else if (factor < 0.8f && zoomLevel > 5) {
                        zoomLevel--
                        invalidate()
                        return true
                    }
                    return false
                }
            })

            setOnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                if (scaleDetector.isInProgress) return@setOnTouchListener true

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (abs(dx) > 5 || abs(dy) > 5) {
                            isDragging = true
                            isFollowLocation = false
                            panMap(dx, dy)
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Tap on map
                        }
                    }
                }
                true
            }

            zoomLevel = AppPrefs.getMapZoom(context)
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
            if (zoomLevel < 18) {
                zoomLevel++
                AppPrefs.setMapZoom(context, zoomLevel)
                invalidate()
            }
        }

        fun zoomOut() {
            if (zoomLevel > 5) {
                zoomLevel--
                AppPrefs.setMapZoom(context, zoomLevel)
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
            zoomLevel = 14
            invalidate()
        }

        private fun panMap(dxPx: Float, dyPx: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, zoomLevel)
            var rad = 0.0
            if (isHeadingUp && compassHeading != 0f) {
                rad = Math.toRadians(-compassHeading.toDouble())
            }
            val cosR = cos(rad)
            val sinR = sin(rad)
            val rotatedDx = dxPx * cosR - dyPx * sinR
            val rotatedDy = dxPx * sinR + dyPx * cosR

            val newWorldX = centerWorld.first - rotatedDx
            val newWorldY = centerWorld.second - rotatedDy
            val newCoords = OsmTileEngine.worldPixelToLatLon(newWorldX, newWorldY, zoomLevel)
            mapCenterLat = newCoords.first
            mapCenterLon = newCoords.second
            invalidate()
        }

        fun updateLocationMetrics(metrics: ProcessedLocationMetrics) {
            currentLocation = metrics.location
            compassHeading = metrics.compassHeading
            trajectoryBearing = metrics.trajectoryBearing
            if (isFollowLocation) {
                mapCenterLat = metrics.location.latitude
                mapCenterLon = metrics.location.longitude
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(bgPaint.color)

            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            canvas.save()
            if (isHeadingUp && compassHeading != 0f) {
                canvas.rotate(-compassHeading, cx, cy)
            }

            // 1. Draw OSM Map Tiles
            drawOsmTiles(canvas, cx, cy)

            // 2. Draw Recorded Tracks
            drawTracks(canvas, cx, cy)

            // 3. Draw Markers
            drawMarkers(canvas, cx, cy)

            // 4. Draw Current Position
            drawUserLocation(canvas, cx, cy)

            canvas.restore()
        }

        private fun drawOsmTiles(canvas: Canvas, cx: Float, cy: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, zoomLevel)
            val tileSize = OsmTileEngine.TILE_SIZE

            val startPx = centerWorld.first - cx
            val startPy = centerWorld.second - cy
            val endPx = centerWorld.first + cx
            val endPy = centerWorld.second + cy

            val minTileX = floor(startPx / tileSize).toInt()
            val maxTileX = ceil(endPx / tileSize).toInt()
            val minTileY = floor(startPy / tileSize).toInt()
            val maxTileY = ceil(endPy / tileSize).toInt()

            val maxCoord = 1 shl zoomLevel

            for (tx in minTileX..maxTileX) {
                val clampedTx = (tx % maxCoord + maxCoord) % maxCoord
                for (ty in minTileY..maxTileY) {
                    if (ty < 0 || ty >= maxCoord) continue

                    val screenLeft = (tx * tileSize - centerWorld.first + cx).toFloat()
                    val screenTop = (ty * tileSize - centerWorld.second + cy).toFloat()

                    val bmp = OsmTileEngine.getTile(zoomLevel, clampedTx, ty)
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, screenLeft, screenTop, null)
                    } else {
                        canvas.drawRect(screenLeft, screenTop, screenLeft + tileSize, screenTop + tileSize, tileGridPaint)
                    }
                }
            }
        }

        private fun drawTracks(canvas: Canvas, cx: Float, cy: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, zoomLevel)

            for (track in tracksList) {
                if (!track.isVisible || track.points.size < 2) continue
                trackPaint.color = track.color

                var prevX = -1f
                var prevY = -1f
                for (p in track.points) {
                    val wp = OsmTileEngine.latLonToWorldPixel(p.lat, p.lon, zoomLevel)
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
                        val wp = OsmTileEngine.latLonToWorldPixel(p.lat, p.lon, zoomLevel)
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

        private fun drawMarkers(canvas: Canvas, cx: Float, cy: Float) {
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, zoomLevel)

            for (m in markersList) {
                if (!m.isVisible) continue
                val wp = OsmTileEngine.latLonToWorldPixel(m.lat, m.lon, zoomLevel)
                val sx = (wp.first - centerWorld.first + cx).toFloat()
                val sy = (wp.second - centerWorld.second + cy).toFloat()

                markerPinPaint.color = m.color
                // Pin head circle
                canvas.drawCircle(sx, sy - 18f, 14f, markerPinPaint)
                canvas.drawCircle(sx, sy - 18f, 14f, userRingPaint)
                // Pin stem
                canvas.drawLine(sx, sy - 4f, sx, sy, userRingPaint)

                // Label
                canvas.drawText(m.name, sx + 20f, sy - 8f, markerTextPaint)
            }
        }

        private fun drawUserLocation(canvas: Canvas, cx: Float, cy: Float) {
            val loc = currentLocation ?: return
            val centerWorld = OsmTileEngine.latLonToWorldPixel(mapCenterLat, mapCenterLon, zoomLevel)
            val wp = OsmTileEngine.latLonToWorldPixel(loc.latitude, loc.longitude, zoomLevel)
            val sx = (wp.first - centerWorld.first + cx).toFloat()
            val sy = (wp.second - centerWorld.second + cy).toFloat()

            // Accuracy circle
            if (loc.hasAccuracy()) {
                val metersPerPx = (156543.03392 * cos(Math.toRadians(loc.latitude))) / (1 shl zoomLevel)
                val accPx = (loc.accuracy / metersPerPx).toFloat()
                canvas.drawCircle(sx, sy, accPx, accuracyPaint)
                canvas.drawCircle(sx, sy, accPx, accuracyStrokePaint)
            }

            // Direction arrow if moving
            val bearingToDraw = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else trajectoryBearing
            if (bearingToDraw != 0f) {
                canvas.save()
                canvas.rotate(bearingToDraw, sx, sy)
                val arrowPath = Path().apply {
                    moveTo(sx, sy - 34f)
                    lineTo(sx + 14f, sy + 6f)
                    lineTo(sx, sy - 2f)
                    lineTo(sx - 14f, sy + 6f)
                    close()
                }
                canvas.drawPath(arrowPath, headingArrowPaint)
                canvas.restore()
            }

            // Center blue user dot
            canvas.drawCircle(sx, sy, 14f, userCenterPaint)
            canvas.drawCircle(sx, sy, 14f, userRingPaint)
        }
    }
}
