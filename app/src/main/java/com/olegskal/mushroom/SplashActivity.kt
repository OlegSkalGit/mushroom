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
import com.olegskal.mushroom.service.RadarForegroundService
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
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
        AppLogger.log("SplashActivity", "onCreate", true, "SplashActivity launched.")

        MushroomStorageManager.initStorage()

        if (RadarForegroundService.isRunning) {
            startActivity(createSingleTopIntent<RadarMapActivity>())
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
                Toast.makeText(applicationContext, "Для роботи навігатора потрібен доступ до GPS.", Toast.LENGTH_LONG).show()
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
        AlertDialog.Builder(this)
            .setTitle("Налаштування Mushroom")
            .setMessage(
                "Для надійної роботи в лісі програмі потрібні:\n" +
                        (if (needsBgLoc) "• Доступ до геолокації у фоні (\"Дозволяти завжди\") для запису треку з вимкненим екраном.\n" else "") +
                        (if (needsBattery) "• Вимкнення оптимізації заряду для безперервного GPS.\n" else "") +
                        (if (needsAllFiles) "• Доступ до сховища для зберігання карт, міток та налаштувань у папці /sdcard/mushroom (дані не видаляються при перевстановленні)." else "")
            )
            .setPositiveButton("Налаштувати") { _, _ ->
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

                if (needsBgLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                } else if (needsBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Продовжити") { _, _ ->
                startMushroomServiceAndFinish()
            }
            .setCancelable(false)
            .show()
    }

    private fun startMushroomServiceAndFinish() {
        val serviceIntent = Intent(this, RadarForegroundService::class.java)
        com.olegskal.mushroom.util.ServiceUtils.startRadarForegroundService(this, serviceIntent)
        startActivity(createSingleTopIntent<RadarMapActivity>())
        finish()
    }
}
