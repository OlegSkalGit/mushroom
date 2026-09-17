package com.olegskal.mushroom.util

import android.content.Context
import java.io.File

object AppLogger {

    const val isLoggingEnabled: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun initNewSession(context: Context) {}

    @Suppress("UNUSED_PARAMETER")
    fun setLoggingEnabled(context: Context, enabled: Boolean) {}

    fun getTodayFileName(): String = ""

    @Suppress("UNUSED_PARAMETER")
    fun getAvailableLogFiles(context: Context): List<File> = emptyList()

    @Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
    inline fun log(module: String, functionName: String, isSuccess: Boolean, details: String) {}

    @Suppress("UNUSED_PARAMETER")
    fun readLogText(fileName: String? = null): String = ""

    @Suppress("UNUSED_PARAMETER")
    fun deleteLogFile(fileName: String?): Boolean = false
}
