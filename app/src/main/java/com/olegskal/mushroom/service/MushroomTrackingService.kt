package com.olegskal.mushroom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.olegskal.mushroom.MushroomMapActivity
import com.olegskal.mushroom.R
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.math.GeoMath
import com.olegskal.mushroom.math.ProcessedLocationMetrics
import com.olegskal.mushroom.math.TrajectoryFilter
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.model.TrackPoint
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.LocationUtils
import com.olegskal.mushroom.util.ServiceUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MushroomTrackingService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID = "mushroom_tracker_channel"
        const val NOTIF_ID = 2001

        const val ACTION_STOP_SERVICE = "com.olegskal.mushroom.ACTION_STOP_SERVICE"
        const val ACTION_START_RECORDING = "com.olegskal.mushroom.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.olegskal.mushroom.ACTION_STOP_RECORDING"
        const val ACTION_RESTORE_NOTIFICATION = "com.olegskal.mushroom.ACTION_RESTORE_NOTIFICATION"
        const val ACTION_NOTIFICATION_DISMISSED = "com.olegskal.mushroom.ACTION_NOTIFICATION_DISMISSED"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_TRACK_COLOR = "extra_track_color"

        const val GPS_FOREGROUND_INTERVAL_MS = 1000L // 1s active on screen
        const val GPS_FOREGROUND_MIN_DIST = 0f
        const val GPS_BACKGROUND_INTERVAL_MS = 5000L // 5s in pocket when recording
        const val GPS_BACKGROUND_MIN_DIST = 15f      // 15m in pocket when recording

        @Volatile
        var isRunning = false
        @Volatile
        var instance: MushroomTrackingService? = null
        @Volatile
        var isAppInForeground = false
            set(value) {
                field = value
                instance?.let { s ->
                    s.updateGpsState(force = true)
                    if (value) {
                        s.requestSingleNetworkLocation()
                        s.restoreForegroundNotification()
                    }
                }
            }
        @Volatile
        var isNotificationDismissed = false
        @Volatile
        var lastMetrics: ProcessedLocationMetrics? = null
        @Volatile
        var metricsListener: ((ProcessedLocationMetrics) -> Unit)? = null
        @Volatile
        var serviceStateListener: ((Boolean) -> Unit)? = null

        val currentServiceLocation: Location?
            get() = instance?.lastLocation ?: lastMetrics?.location

        fun ensureServiceAndNotification(context: Context) {
            try {
                val s = instance
                if (s == null || !isRunning) {
                    val serviceIntent = Intent(context, MushroomTrackingService::class.java).apply {
                        action = ACTION_RESTORE_NOTIFICATION
                    }
                    ServiceUtils.startTrackingService(context, serviceIntent)
                } else {
                    s.restoreForegroundNotification()
                }
            } catch (e: Exception) {
                AppLogger.log("TrackingService", "ensureServiceAndNotification", false, "Error: ${e.message}")
            }
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper

    private var wakeLock: PowerManager.WakeLock? = null
    var lastLocation: Location? = null
        private set
    private var trajectoryFilter = TrajectoryFilter()
    private var lastNotificationText: String? = null

    // Track recording
    @Volatile
    var isRecording: Boolean = false
        private set
    private var activeTrack: MushroomTrack? = null
    val currentActiveTrack: MushroomTrack?
        get() = activeTrack
    private var lastRecordedPoint: TrackPoint? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        AppPrefs.setUserStopped(this, false)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dbHelper = DatabaseHelper(this)

        createNotificationChannel()
        val initialNotif = AppPrefs.t(this, "Пошук супутників GPS...", "Searching for GPS satellites...")
        val notif = buildNotification(initialNotif)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gpsSwitchReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gpsSwitchReceiver, filter)
        }

        updateGpsState(force = true)
        requestSingleNetworkLocation()

        // Resume track recording if was active prior to process kill
        if (AppPrefs.isRecordingTrack(this)) {
            val savedTrackId = AppPrefs.getActiveTrackId(this)
            if (savedTrackId > 0L) {
                val tracks = dbHelper.getAllTracks()
                val found = tracks.firstOrNull { it.id == savedTrackId }
                if (found != null) {
                    activeTrack = found
                    isRecording = true
                    lastRecordedPoint = found.points.lastOrNull()
                    acquireWakeLock()
                    updateGpsState(force = true)
                    updateNotification()
                    AppLogger.log("TrackingService", "onCreate", true, "Resumed track recording: ${found.id}")
                }
            }
        }

        serviceStateListener?.invoke(true)
        AppLogger.log("TrackingService", "onCreate", true, "MushroomTrackingService started.")
    }

    private fun fetchInitialLocationCascade() {
        if (lastLocation == null || lastLocation?.provider != LocationManager.GPS_PROVIDER) {
            val lastKnown = LocationUtils.getLastKnownLocationCascade(locationManager)
            if (lastKnown != null) {
                lastLocation = lastKnown
                publishMetrics(lastKnown, 0f, isStationary = true)
                hasUpdatedActiveNotification = true
                updateNotification()
            }
        }
    }

    private val gpsSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                AppLogger.log("TrackingService", "gpsSwitchReceiver", true, "Location providers changed, checking GPS")
                updateGpsState(force = true)
                if (isAppInForeground) {
                    requestSingleNetworkLocation()
                }
            }
        }
    }

    private var hasUpdatedActiveNotification = false
    private var isGpsRegistered = false

    fun updateGpsState(force: Boolean = false) {
        if (!isRunning) return
        try {
            locationManager.removeUpdates(this)
            isGpsRegistered = false

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                AppLogger.log("TrackingService", "updateGpsState", false, "GPS provider disabled.")
                return
            }

            if (isAppInForeground) {
                // Foreground / Screen active: 1s, 0m
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_FOREGROUND_INTERVAL_MS,
                    GPS_FOREGROUND_MIN_DIST,
                    this
                )
                isGpsRegistered = true
                AppLogger.log("TrackingService", "updateGpsState", true, "GPS set to FOREGROUND (1s, 0m).")
            } else if (isRecording) {
                // Background & Recording: 5s, 15m
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_BACKGROUND_INTERVAL_MS,
                    GPS_BACKGROUND_MIN_DIST,
                    this
                )
                isGpsRegistered = true
                AppLogger.log("TrackingService", "updateGpsState", true, "GPS set to BACKGROUND RECORDING (5s, 15m).")
            } else {
                // Background & NOT recording: keep updates removed!
                AppLogger.log("TrackingService", "updateGpsState", true, "GPS paused in background (not recording).")
            }
        } catch (e: SecurityException) {
            isGpsRegistered = false
            AppLogger.log("TrackingService", "updateGpsState", false, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            isGpsRegistered = false
            AppLogger.log("TrackingService", "updateGpsState", false, "Error: ${e.message}")
        }
    }

    fun registerGpsUpdates(force: Boolean = false) {
        updateGpsState(force)
    }

    fun requestSingleNetworkLocation() {
        try {
            fetchInitialLocationCascade()
            if (lastLocation == null || lastLocation?.provider != LocationManager.GPS_PROVIDER) {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    val singleListener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                            if (lastLocation == null || lastLocation?.provider != LocationManager.GPS_PROVIDER) {
                                this@MushroomTrackingService.onLocationChanged(loc)
                            }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L,
                        0f,
                        singleListener,
                        mainLooper
                    )
                }
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelfAndCleanup()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                val title = intent.getStringExtra(EXTRA_TRACK_TITLE)
                val color = if (intent.hasExtra(EXTRA_TRACK_COLOR)) intent.getIntExtra(EXTRA_TRACK_COLOR, 0xFF2196F3.toInt()) else null
                startTrackRecording(title, color)
            }
            ACTION_STOP_RECORDING -> {
                stopTrackRecording()
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                isNotificationDismissed = true
                if (isAppInForeground) {
                    restoreForegroundNotification()
                }
            }
            ACTION_RESTORE_NOTIFICATION -> {
                restoreForegroundNotification()
            }
            else -> {
                if (isNotificationDismissed || isAppInForeground) {
                    restoreForegroundNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    fun startTrackRecording(customTitle: String? = null, customColor: Int? = null) {
        if (isRecording) return
        isRecording = true
        AppPrefs.setRecordingTrack(this, true)
        acquireWakeLock()
        updateGpsState(force = true)

        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val title = customTitle?.trim()?.ifEmpty { null } ?: "Track (${sdf.format(Date(now))})"
        val color = customColor ?: 0xFF2196F3.toInt()

        val track = MushroomTrack(
            id = now,
            title = title,
            startTime = now,
            endTime = now,
            color = color
        )
        activeTrack = track
        AppPrefs.setActiveTrackId(this, track.id)
        dbHelper.insertTrack(track)

        lastLocation?.let { loc ->
            val pt = TrackPoint(loc.latitude, loc.longitude, loc.altitude, now, loc.speed)
            track.points.add(pt)
            lastRecordedPoint = pt
            dbHelper.addPointToTrack(track.id, pt, 0f, 0L)
        }

        updateNotification()
        Toast.makeText(this, AppPrefs.t(this, "Запис треку розпочато", "Track recording started"), Toast.LENGTH_SHORT).show()
        AppLogger.log("TrackingService", "startTrackRecording", true, "Started track ${track.id}")
    }

    fun stopTrackRecording() {
        if (!isRecording) return
        isRecording = false
        AppPrefs.setRecordingTrack(this, false)
        AppPrefs.setActiveTrackId(this, -1L)

        activeTrack?.let { track ->
            track.endTime = System.currentTimeMillis()
            track.durationSec = maxOf(1L, (track.endTime - track.startTime) / 1000L)
            dbHelper.updateTrack(track)
            MushroomStorageManager.saveTrackToGpx(track)
            AppLogger.log("TrackingService", "stopTrackRecording", true, "Stopped track ${track.id}, dist: ${track.distanceMeters}m")
        }
        activeTrack = null
        lastRecordedPoint = null

        releaseWakeLock()
        updateGpsState(force = true)
        updateNotification()
        Toast.makeText(this, AppPrefs.t(this, "Трек збережено", "Track recording saved"), Toast.LENGTH_SHORT).show()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mushroom:TrackWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // max 12h safety
            AppLogger.log("TrackingService", "acquireWakeLock", true, "WakeLock acquired for background track recording.")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.log("TrackingService", "releaseWakeLock", true, "WakeLock released.")
            }
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        lastLocation = location

        if ((!hasUpdatedActiveNotification || isNotificationDismissed) && !isRecording) {
            hasUpdatedActiveNotification = true
            updateNotification(forceForeground = isNotificationDismissed && isAppInForeground)
        }

        val traj = trajectoryFilter.processLocation(location)
        val speedKmh = traj.averageSpeedKmh

        if (isRecording && activeTrack != null) {
            processTrackRecordingPoint(location)
        }

        publishMetrics(location, speedKmh, traj.trajectoryBearing, traj.isStationary)
    }

    private fun processTrackRecordingPoint(location: Location) {
        val track = activeTrack ?: return
        if (location.hasAccuracy() && location.accuracy > 35f) return

        val prev = lastRecordedPoint
        if (prev == null) {
            val pt = TrackPoint(location.latitude, location.longitude, location.altitude, System.currentTimeMillis(), location.speed)
            track.points.add(pt)
            lastRecordedPoint = pt
            dbHelper.addPointToTrack(track.id, pt, 0f, 0L)
            return
        }

        val dist = GeoMath.calculateDistance(prev.lat, prev.lon, location.latitude, location.longitude)
        val minRequiredDist = maxOf(15f, if (location.hasAccuracy()) location.accuracy else 15f)
        if (dist >= minRequiredDist) {
            val now = System.currentTimeMillis()
            val pt = TrackPoint(location.latitude, location.longitude, location.altitude, now, location.speed)
            track.points.add(pt)
            track.distanceMeters += dist
            track.durationSec = maxOf(1L, (now - track.startTime) / 1000L)
            track.endTime = now
            lastRecordedPoint = pt
            dbHelper.addPointToTrack(track.id, pt, track.distanceMeters, track.durationSec)
            updateNotification()
        }
    }

    private fun publishMetrics(
        loc: Location,
        speedKmh: Float,
        bearing: Float = loc.bearing,
        isStationary: Boolean = false
    ) {
        val isGpsDisabled = LocationUtils.isGpsDisabled(locationManager)
        val metrics = GeoMath.evaluateLocationData(
            location = loc,
            speedKmh = speedKmh,
            trajectoryBearing = bearing,
            compassHeading = if (loc.hasBearing()) loc.bearing else 0f,
            isStationary = isStationary,
            isGpsDisabled = isGpsDisabled,
            isDeepSleep = false,
            isRecordingTrack = isRecording,
            recordedDistanceMeters = activeTrack?.distanceMeters ?: 0f,
            recordedDurationSec = activeTrack?.durationSec ?: 0L
        )
        lastMetrics = metrics
        metricsListener?.invoke(metrics)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Mushroom: Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for navigation and track recording"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MushroomMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AppPrefs.t(this, "Лісовий навігатор Грибника", "Mushroom Navigation"))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mushroom_notif)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isRecording) {
            val stopRecIntent = Intent(this, MushroomTrackingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            val pStopRec = PendingIntent.getService(
                this, 1, stopRecIntent,
                ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
            )
            builder.addAction(android.R.drawable.ic_media_pause, AppPrefs.t(this, "Зупинити запис", "Stop Recording"), pStopRec)
        }

        val dismissedIntent = Intent(this, MushroomTrackingService::class.java).apply {
            action = ACTION_NOTIFICATION_DISMISSED
        }
        val pDismissed = PendingIntent.getService(
            this, 2, dismissedIntent,
            ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
        )
        builder.setDeleteIntent(pDismissed)

        return builder.build()
    }

    fun updateNotification(textOverride: String? = null, forceForeground: Boolean = false) {
        val notifText = textOverride ?: if (isRecording && activeTrack != null) {
            val km = activeTrack!!.distanceMeters / 1000f
            val sec = (System.currentTimeMillis() - activeTrack!!.startTime) / 1000L
            val m = sec / 60
            val s = sec % 60
            val fmt = if (AppPrefs.isUk(this)) "Запис треку: %.2f км | %02d:%02d" else "Recording track: %.2f km | %02d:%02d"
            String.format(Locale.US, fmt, km, m, s)
        } else if (lastLocation != null) {
            val acc = if (lastLocation!!.hasAccuracy()) " (±${lastLocation!!.accuracy.toInt()}м)" else ""
            AppPrefs.t(this, "GPS: активний$acc", "GPS: active$acc")
        } else {
            AppPrefs.t(this, "Пошук супутників GPS...", "Searching for GPS satellites...")
        }

        if (notifText == lastNotificationText && !forceForeground && !isNotificationDismissed) {
            return
        }
        lastNotificationText = notifText

        val notification = buildNotification(notifText)
        if (forceForeground || isNotificationDismissed) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                isNotificationDismissed = false
            } catch (e: Exception) {
                AppLogger.log("TrackingService", "updateNotification", false, "startForeground error: ${e.message}")
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, notification)
                } catch (_: Exception) {}
            }
        } else {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, notification)
            } catch (_: Exception) {}
        }
    }

    fun restoreForegroundNotification() {
        if (!isRunning) return
        isNotificationDismissed = false
        updateNotification(forceForeground = true)
    }

    fun stopSelfAndCleanup() {
        if (isRecording) {
            stopTrackRecording()
        }
        isRunning = false
        AppPrefs.setUserStopped(this, true)
        releaseWakeLock()
        try {
            locationManager.removeUpdates(this)
            isGpsRegistered = false
        } catch (_: Exception) {}
        stopForeground(true)
        stopSelf()
        serviceStateListener?.invoke(false)
        instance = null
        AppLogger.log("TrackingService", "stopSelfAndCleanup", true, "Service stopped completely.")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        com.olegskal.mushroom.mushrooms.MushroomClassifierTab.isWarningDismissed = false
        if (!isRecording) {
            stopSelfAndCleanup()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(gpsSwitchReceiver)
        } catch (_: Exception) {}
        stopSelfAndCleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onProviderEnabled(provider: String) {
        AppLogger.log("TrackingService", "onProviderEnabled", true, "Provider enabled: $provider")
        registerGpsUpdates()
        fetchInitialLocationCascade()
    }

    override fun onProviderDisabled(provider: String) {
        AppLogger.log("TrackingService", "onProviderDisabled", true, "Provider disabled: $provider")
        registerGpsUpdates()
    }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
