package com.olegskal.mushroom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

class MushroomTrackingService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val CHANNEL_ID = "mushroom_tracker_channel"
        const val NOTIF_ID = 2001

        const val ACTION_STOP_SERVICE = "com.olegskal.mushroom.ACTION_STOP_SERVICE"
        const val ACTION_START_RECORDING = "com.olegskal.mushroom.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.olegskal.mushroom.ACTION_STOP_RECORDING"

        const val GPS_FULL_UPDATE_INTERVAL_MS = 1000L // 1 second full rate

        @Volatile
        var isRunning = false
        @Volatile
        var instance: MushroomTrackingService? = null
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        AppPrefs.setUserStopped(this, false)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        dbHelper = DatabaseHelper(this)

        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Пошук супутників GPS..."))

        registerSensors()
        registerGpsUpdates()

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
                    updateNotification()
                    AppLogger.log("TrackingService", "onCreate", true, "Resumed track recording: ${found.id}")
                }
            }
        }

        serviceStateListener?.invoke(true)
        AppLogger.log("TrackingService", "onCreate", true, "MushroomTrackingService started. GPS at full 1-sec rate.")
    }

    private fun registerSensors() {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        }
        accelerometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun registerGpsUpdates() {
        try {
            locationManager.removeUpdates(this)
            val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, GPS_FULL_UPDATE_INTERVAL_MS, 0f, this)
                }
            }
        } catch (e: SecurityException) {
            AppLogger.log("TrackingService", "registerGpsUpdates", false, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            AppLogger.log("TrackingService", "registerGpsUpdates", false, "Error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        updateNotification()
        Toast.makeText(this, "Запис треку розпочато", Toast.LENGTH_SHORT).show()
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
        updateNotification()
        Toast.makeText(this, "Запис треку збережено", Toast.LENGTH_SHORT).show()
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
            compassHeading = currentAzimuth,
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

    private var lastSentAzimuth = -999f
    private var lastAzimuthTime = 0L

    private fun dispatchAzimuthUpdate(azDeg: Float) {
        currentAzimuth = azDeg
        val now = System.currentTimeMillis()
        var diff = kotlin.math.abs(azDeg - lastSentAzimuth)
        if (diff > 180f) diff = 360f - diff
        if (diff >= 2.0f && (now - lastAzimuthTime >= 100L)) {
            lastSentAzimuth = azDeg
            lastAzimuthTime = now
            val m = lastMetrics
            if (m != null) {
                metricsListener?.invoke(m.copy(compassHeading = currentAzimuth))
            } else {
                val loc = lastLocation ?: Location("sensor").apply {
                    latitude = 50.4501
                    longitude = 30.5234
                }
                publishMetrics(loc, 0f, currentAzimuth, isStationary = true)
            }
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
                dispatchAzimuthUpdate(azDeg)
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
                dispatchAzimuthUpdate(az)
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
        val launchIntent = Intent(this, MushroomMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mushroom - Навігатор грибника")
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
        releaseWakeLock()
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
        AppLogger.log("TrackingService", "stopSelfAndCleanup", true, "Service stopped completely.")
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
