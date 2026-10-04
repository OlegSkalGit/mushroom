package com.olegskal.mushroom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.olegskal.mushroom.map.OsmTileEngine
import com.olegskal.mushroom.service.MushroomTrackingService
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.ServiceUtils
import com.olegskal.mushroom.util.createSingleTopIntent

class SplashActivity : Activity() {

    private var awaitingSettings = false

    companion object {
        private const val REQ_PERMS = 1001
    }

    override fun onResume() {
        super.onResume()
        if (awaitingSettings) {
            awaitingSettings = false
            checkBackgroundLocationAndStorage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        OsmTileEngine.appContext = applicationContext
        AppLogger.log("SplashActivity", "onCreate", true, "SplashActivity launched.")

        MushroomStorageManager.initStorage()
        com.olegskal.mushroom.mushrooms.MushroomClassifierTab.isWarningDismissed = false

        if (MushroomTrackingService.isRunning) {
            startActivity(createSingleTopIntent<MushroomMapActivity>())
            finish()
            return
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            AppLogger.log("SplashActivity", "checkAndRequestPermissions", false, "Requesting missing permissions: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        } else {
            onBasicPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasLocation) {
                onBasicPermissionsGranted()
            } else {
                Toast.makeText(applicationContext, AppPrefs.t(this, "Для роботи навігатора потрібен доступ до GPS.", "GPS location access is required for navigator to work."), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun onBasicPermissionsGranted() {
        checkBackgroundLocationAndStorage()
    }

    private fun checkBackgroundLocationAndStorage() {
        val needsBgLoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val needsBattery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !pm.isIgnoringBatteryOptimizations(packageName)

        val needsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

        if (needsBgLoc || needsBattery || needsAllFiles) {
            showExplanationDialog(needsBgLoc, needsBattery, needsAllFiles)
        } else {
            startMushroomServiceAndFinish()
        }
    }

    private fun showExplanationDialog(needsBgLoc: Boolean, needsBattery: Boolean, needsAllFiles: Boolean) {
        val isUk = AppPrefs.isUk(this)
        val msg = if (isUk) {
            "Для надійної роботи в лісі додатку потрібні:\n" +
                    (if (needsBgLoc) "• Доступ до геолокації у фоні (\"Дозволяти завжди\") для запису треку при вимкненому екрані.\n" else "") +
                    (if (needsBattery) "• Вимкнення оптимізації батареї для безперервного GPS-трекінгу.\n" else "") +
                    (if (needsAllFiles) "• Доступ до пам'яті для збереження офлайн-карт, міток і налаштувань у /sdcard/mushroom (зберігаються після перевстановлення)." else "")
        } else {
            "For reliable operation in the woods, the app needs:\n" +
                    (if (needsBgLoc) "• Background location access (\"Allow all the time\") to record tracks with the screen off.\n" else "") +
                    (if (needsBattery) "• Disabling battery optimization for continuous GPS tracking.\n" else "") +
                    (if (needsAllFiles) "• Storage access to save offline maps, markers, and settings in /sdcard/mushroom (data persists after reinstall)." else "")
        }
        AlertDialog.Builder(this)
            .setTitle(AppPrefs.t(this, "Налаштування Mushroom", "Mushroom Settings"))
            .setMessage(msg)
            .setPositiveButton(AppPrefs.t(this, "Налаштувати", "Configure")) { _, _ ->
                awaitingSettings = true
                if (needsAllFiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                        return@setPositiveButton
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            return@setPositiveButton
                        } catch (_: Exception) {}
                    }
                }

                if (needsBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ServiceUtils.requestIgnoreBatteryOptimizations(this@SplashActivity)
                } else if (needsBgLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton(AppPrefs.t(this, "Продовжити", "Continue")) { _, _ ->
                startMushroomServiceAndFinish()
            }
            .setCancelable(false)
            .show()
    }

    private fun startMushroomServiceAndFinish() {
        val serviceIntent = Intent(this, MushroomTrackingService::class.java)
        ServiceUtils.startTrackingService(this, serviceIntent)
        startActivity(createSingleTopIntent<MushroomMapActivity>())
        finish()
    }
}
