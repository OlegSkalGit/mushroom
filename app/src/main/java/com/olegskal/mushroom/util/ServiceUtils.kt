package com.olegskal.mushroom.util

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.olegskal.mushroom.service.RadarForegroundService

/**
 * Utility functions for service management and common Intent creations.
 */
object ServiceUtils {

    val PENDING_INTENT_IMMUTABLE_FLAGS: Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    fun startRadarForegroundService(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            AppLogger.log("ServiceUtils", "startRadarForegroundService", false, "Failed to start service: ${e.message}")
        }
    }
}

inline fun <reified T : Activity> Context.createSingleTopIntent(): Intent {
    return Intent(this, T::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}

fun Context.getAppVersionName(): String {
    return try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    } catch (e: Exception) {
        "1.0"
    }
}
