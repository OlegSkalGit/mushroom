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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.olegskal.mushroom.RadarMapActivity
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.math.ProcessedLocationMetrics
import com.olegskal.mushroom.math.RadarMath
import com.olegskal.mushroom.math.TrajectoryFilter
import com.olegskal.mushroom.model.MushroomTrack
import com.olegskal.mushroom.model.TrackPoint
import com.olegskal.mushroom.receiver.AlarmWatchdogReceiver
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.LocationUtils
import com.olegskal.mushroom.util.ServiceUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RadarForegroundService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val CHANNEL_ID = "mushroom_tracker_channel"
        const val NOTIF_ID = 2001

        const val ACTION_STOP_SERVICE = "com.olegskal.mushroom.ACTION_STOP_SERVICE"
        const val ACTION_START_RECORDING = "com.olegskal.mushroom.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.olegskal.mushroom.ACTION_STOP_RECORDING"
        const val EXTRA_START_IN_DEEP_SLEEP = "extra_start_in_deep_sleep"

        @Volatile
        var isRunning = false
        @Volatile
        var instance: RadarForegroundService? = null
        @Volatile
        var lastMetrics: ProcessedLocationMetrics? = null
        @Volatile
        var metricsListener: ((ProcessedLocationMetrics) -> Unit)? = null
        @Volatile
        var serviceStateListener: ((Boolean) -> Unit)? = null
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var dbHelper: DatabaseHelper

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLocation: Location? = null
    private var trajectoryFilter = TrajectoryFilter()

    // Compass
    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private var significantMotionSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravityData = FloatArray(3)
    private val geomagneticData = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var currentAzimuth = 0f

    // Track recording
    @Volatile
    var isRecording: Boolean = false
        private set
    private var activeTrack: MushroomTrack? = null
    val currentActiveTrack: MushroomTrack?
        get() = activeTrack
    private var lastRecordedPoint: TrackPoint? = null
    private var recordingStartTimeMs: Long = 0L

    // Power saving / Deep sleep
    @Volatile
    var isDeepSleepState: Boolean = false
    private var stationaryStartTimeMs: Long = 0L
    private var isSignificantMotionActive: Boolean = false
    private var isPowerConnected: Boolean = false

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastLocationTimeMs: Long = System.currentTimeMillis()
    private val WATCHDOG_INTERVAL_MS = 30000L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkWatchdogStall()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val sigMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            isSignificantMotionActive = false
            if (isRunning && isDeepSleepState) {
                AppLogger.log("ForegroundService", "onTrigger", true, "Motion detected. Waking up...")
                wakeUpFromDeepSleep("Significant Motion")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        AppPrefs.setUserStopped(this, false)

        MushroomStorageManager.initStorage()
        dbHelper = DatabaseHelper(this)
        dbHelper.restoreDataFromExternalStorageIfDbEmpty()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Грибник: пошук GPS..."))

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        initSensors()
        initPowerReceiver()

        AppLogger.log("ForegroundService", "onCreate", true, "Service started. Initializing GPS...")
        registerGpsUpdates(if (isRecording) 2000L else 4000L, force = true)

        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        AlarmWatchdogReceiver.scheduleNextAlarm(this)

        val last = LocationUtils.getLastKnownLocationCascade(locationManager)
        if (last != null) {
            lastLocation = last
            publishMetrics(last, 0f)
        }
    }

    private fun initSensors() {
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }

    private fun initPowerReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(powerReceiver, filter)
        } catch (_: Exception) {}
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isPowerConnected = true
                    if (isDeepSleepState) wakeUpFromDeepSleep("Power Connected")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isPowerConnected = false
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_START_IN_DEEP_SLEEP, false) == true) {
            enterDeepSleep()
        }
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelfAndCleanup()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                startTrackRecording()
            }
            ACTION_STOP_RECORDING -> {
                stopTrackRecording()
            }
        }
        return START_NOT_STICKY
    }

    fun startTrackRecording() {
        if (isRecording) return
        isRecording = true
        AppPrefs.setRecordingTrack(this, true)
        acquireWakeLock()

        val now = System.currentTimeMillis()
        recordingStartTimeMs = now
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val title = "Трек від ${sdf.format(Date(now))}"

        val track = MushroomTrack(
            id = now,
            title = title,
            startTime = now,
            endTime = now
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

        registerGpsUpdates(2000L, force = true)
        updateNotification()
        Toast.makeText(this, "Запис треку розпочато", Toast.LENGTH_SHORT).show()
        AppLogger.log("ForegroundService", "startTrackRecording", true, "Started track ${track.id}")
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
            AppLogger.log("ForegroundService", "stopTrackRecording", true, "Stopped track ${track.id}, dist: ${track.distanceMeters}m")
        }
        activeTrack = null
        lastRecordedPoint = null

        releaseWakeLock()
        registerGpsUpdates(4000L, force = true)
        updateNotification()
        Toast.makeText(this, "Запис треку завершено і збережено", Toast.LENGTH_SHORT).show()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mushroom:TrackWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // max 12 hours safety
            AppLogger.log("ForegroundService", "acquireWakeLock", true, "WakeLock acquired for background track recording.")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.log("ForegroundService", "releaseWakeLock", true, "WakeLock released.")
            }
        } catch (_: Exception) {}
    }

    private fun registerGpsUpdates(intervalMs: Long, force: Boolean = false) {
        try {
            locationManager.removeUpdates(this)
            val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, intervalMs, 0f, this)
                }
            }
        } catch (e: SecurityException) {
            AppLogger.log("ForegroundService", "registerGpsUpdates", false, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            AppLogger.log("ForegroundService", "registerGpsUpdates", false, "Error: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        lastLocationTimeMs = System.currentTimeMillis()
        lastLocation = location

        val traj = trajectoryFilter.processLocation(location)
        val speedKmh = traj.averageSpeedKmh

        if (isRecording && activeTrack != null) {
            processTrackRecordingPoint(location)
        }

        // Deep sleep evaluation when NOT recording and stationary > 3 min
        if (!isRecording && !isPowerConnected) {
            if (traj.isStationary) {
                if (stationaryStartTimeMs == 0L) stationaryStartTimeMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - stationaryStartTimeMs >= 3 * 60 * 1000L) {
                    enterDeepSleep()
                    return
                }
            } else {
                stationaryStartTimeMs = 0L
            }
        } else {
            stationaryStartTimeMs = 0L
        }

        publishMetrics(location, speedKmh, traj.trajectoryBearing, traj.isStationary)
    }

    private fun processTrackRecordingPoint(location: Location) {
        val track = activeTrack ?: return
        // Ignore inaccurate points
        if (location.hasAccuracy() && location.accuracy > 35f) return

        val prev = lastRecordedPoint
        if (prev == null) {
            val pt = TrackPoint(location.latitude, location.longitude, location.altitude, System.currentTimeMillis(), location.speed)
            track.points.add(pt)
            lastRecordedPoint = pt
            dbHelper.addPointToTrack(track.id, pt, 0f, 0L)
            return
        }

        val dist = RadarMath.calculateDistance(prev.lat, prev.lon, location.latitude, location.longitude)
        // Add point only if moved at least 2.5 meters to prevent sitting jitter
        if (dist >= 2.5f) {
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
        val isGpsDisabled = LocationUtils.isGpsDisabled(this, locationManager)
        val metrics = RadarMath.evaluateLocationData(
            location = loc,
            speedKmh = speedKmh,
            trajectoryBearing = bearing,
            compassHeading = currentAzimuth,
            isStationary = isStationary,
            isGpsDisabled = isGpsDisabled,
            isDeepSleep = isDeepSleepState,
            isRecordingTrack = isRecording,
            recordedDistanceMeters = activeTrack?.distanceMeters ?: 0f,
            recordedDurationSec = activeTrack?.durationSec ?: 0L
        )
        lastMetrics = metrics
        metricsListener?.invoke(metrics)
    }

    private fun enterDeepSleep() {
        if (isDeepSleepState) return
        isDeepSleepState = true
        AppLogger.log("ForegroundService", "enterDeepSleep", true, "Entering energy saving deep sleep...")
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}

        if (significantMotionSensor != null) {
            isSignificantMotionActive = sensorManager.requestTriggerSensor(sigMotionListener, significantMotionSensor)
        }

        lastLocation?.let { publishMetrics(it, 0f, isStationary = true) }
        updateNotification("Грибник у режимі сну (очікування руху)")
    }

    fun wakeUpFromDeepSleep(reason: String) {
        if (!isDeepSleepState) return
        isDeepSleepState = false
        AppLogger.log("ForegroundService", "wakeUpFromDeepSleep", true, "Waking up: $reason")
        if (isSignificantMotionActive && significantMotionSensor != null) {
            try {
                sensorManager.cancelTriggerSensor(sigMotionListener, significantMotionSensor)
            } catch (_: Exception) {}
            isSignificantMotionActive = false
        }
        stationaryStartTimeMs = 0L
        registerGpsUpdates(if (isRecording) 2000L else 4000L, force = true)
    }

    fun checkWatchdogStall() {
        if (isDeepSleepState) return
        val elapsed = System.currentTimeMillis() - lastLocationTimeMs
        if (elapsed >= 60000L) {
            AppLogger.log("ForegroundService", "checkWatchdogStall", false, "No GPS updates for ${elapsed / 1000}s. Re-registering GPS...")
            registerGpsUpdates(if (isRecording) 2000L else 4000L, force = true)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azRad = orientationAngles[0]
                var azDeg = Math.toDegrees(azRad.toDouble()).toFloat()
                if (azDeg < 0f) azDeg += 360f
                currentAzimuth = azDeg
                lastMetrics?.let { m ->
                    metricsListener?.invoke(m.copy(compassHeading = currentAzimuth))
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravityData, 0, 3)
                hasGravity = true
                computeOrientationFallback()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagneticData, 0, 3)
                hasGeomagnetic = true
                computeOrientationFallback()
            }
        }
    }

    private fun computeOrientationFallback() {
        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravityData, geomagneticData)) {
                val actualOrientation = FloatArray(3)
                SensorManager.getOrientation(r, actualOrientation)
                var az = Math.toDegrees(actualOrientation[0].toDouble()).toFloat()
                if (az < 0f) az += 360f
                currentAzimuth = az
                lastMetrics?.let { m ->
                    metricsListener?.invoke(m.copy(compassHeading = currentAzimuth))
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Грибник: Фоновий трекінг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постійне сповіщення про роботу навігації та запис треку"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, RadarMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mushroom - Навігатор грибника")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isRecording) {
            val stopRecIntent = Intent(this, RadarForegroundService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            val pStopRec = PendingIntent.getService(
                this, 1, stopRecIntent,
                ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Зупинити запис", pStopRec)
        }

        return builder.build()
    }

    private fun updateNotification(textOverride: String? = null) {
        val notifText = textOverride ?: if (isRecording && activeTrack != null) {
            val km = activeTrack!!.distanceMeters / 1000f
            val sec = (System.currentTimeMillis() - activeTrack!!.startTime) / 1000L
            val m = sec / 60
            val s = sec % 60
            String.format(Locale.US, "Запис треку: %.2f км | %02d:%02d", km, m, s)
        } else {
            "Грибник: активний"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(notifText))
    }

    fun stopSelfAndCleanup() {
        isRunning = false
        AppPrefs.setUserStopped(this, true)
        watchdogHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {}
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        stopForeground(true)
        stopSelf()
        serviceStateListener?.invoke(false)
        instance = null
        AppLogger.log("ForegroundService", "stopSelfAndCleanup", true, "Service stopped completely.")
    }

    override fun onDestroy() {
        stopSelfAndCleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
