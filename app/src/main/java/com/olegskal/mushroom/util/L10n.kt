package com.olegskal.mushroom.util

import android.content.Context

object L10n {

    fun lang(context: Context): String = AppPrefs.getAppLang(context)

    fun isUk(context: Context): Boolean = lang(context) == "uk"

    fun t(context: Context, uk: String, en: String): String =
        if (isUk(context)) uk else en

    fun bearingDirection(context: Context, bearing: Float): String {
        val b = if (bearing < 0) bearing + 360f else bearing
        val isUk = isUk(context)
        return when {
            b in 22.5..67.5 -> if (isUk) "Пн-Сх" else "NE"
            b in 67.5..112.5 -> if (isUk) "Сх" else "E"
            b in 112.5..157.5 -> if (isUk) "Пд-Сх" else "SE"
            b in 157.5..202.5 -> if (isUk) "Пд" else "S"
            b in 202.5..247.5 -> if (isUk) "Пд-Зх" else "SW"
            b in 247.5..292.5 -> if (isUk) "Зх" else "W"
            b in 292.5..337.5 -> if (isUk) "Пн-Зх" else "NW"
            else -> if (isUk) "Пн" else "N"
        }
    }
}
