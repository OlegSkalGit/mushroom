package com.olegskal.mushroom.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import com.olegskal.mushroom.util.getAppVersionName
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AppUpdateManager {

    private const val REPO_OWNER = "OlegSkalGit"
    private const val REPO_NAME = "mushroom"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun extractVersionNumbers(str: String): List<Int> {
        val regex = Regex("""\d+""")
        return regex.findAll(str).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    fun isVersionNewer(remote: List<Int>, local: List<Int>): Boolean {
        val minLen = minOf(remote.size, local.size)
        for (i in 0 until minLen) {
            if (remote[i] > local[i]) return true
            if (remote[i] < local[i]) return false
        }
        return remote.size > local.size
    }

    data class CachedUpdate(
        val hasUpdate: Boolean,
        val latestVerName: String,
        val installedVerName: String,
        val downloadUrl: String,
        val assetSize: Long,
        val checkedAtMs: Long
    )

    @Volatile
    var cachedUpdate: CachedUpdate? = null

    private const val PREF_KEY_CACHED_RELEASE_NAME = "cached_release_name"
    private const val PREF_KEY_CACHED_RELEASE_URL = "cached_release_url"
    private const val PREF_KEY_CACHED_RELEASE_SIZE = "cached_release_size"

    fun getCachedAppUpdate(context: Context): CachedUpdate? {
        val currentVer = context.applicationContext.getAppVersionName()
        val inMemory = cachedUpdate
        if (inMemory != null && inMemory.installedVerName == currentVer) {
            return inMemory
        }

        val prefs = AppPrefs.getPrefs(context)
        val name = prefs.getString(PREF_KEY_CACHED_RELEASE_NAME, null)
        val url = prefs.getString(PREF_KEY_CACHED_RELEASE_URL, null)
        val size = prefs.getLong(PREF_KEY_CACHED_RELEASE_SIZE, 25_000_000L)
        if (name.isNullOrEmpty() || url.isNullOrEmpty()) return null

        val currentNums = extractVersionNumbers(currentVer)
        val remoteNums = extractVersionNumbers(name)
        val hasUpdate = isVersionNewer(remoteNums, currentNums)
        val loaded = CachedUpdate(hasUpdate, name, currentVer, url, size, System.currentTimeMillis())
        cachedUpdate = loaded
        return loaded
    }

    fun saveCachedAppUpdate(context: Context, hasUpdate: Boolean, releaseName: String, downloadUrl: String, assetSize: Long = 25_000_000L) {
        val currentVer = context.applicationContext.getAppVersionName()
        cachedUpdate = CachedUpdate(hasUpdate, releaseName, currentVer, downloadUrl, assetSize, System.currentTimeMillis())
        AppPrefs.getPrefs(context).edit()
            .putString(PREF_KEY_CACHED_RELEASE_NAME, if (hasUpdate) releaseName else "")
            .putString(PREF_KEY_CACHED_RELEASE_URL, if (hasUpdate) downloadUrl else "")
            .putLong(PREF_KEY_CACHED_RELEASE_SIZE, if (hasUpdate) assetSize else 0L)
            .apply()
    }

    fun checkUpdateStatusAsync(
        context: Context,
        onResult: (hasUpdate: Boolean, latestVerName: String, installedVerName: String, downloadUrl: String, assetSize: Long) -> Unit
    ) {
        executor.execute {
            val installedVersionName = context.applicationContext.getAppVersionName()
            val localInstalledVer = extractVersionNumbers(installedVersionName)
            val releasesList = fetchReleasesFromUrl("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=100")
            if (releasesList.isEmpty()) {
                mainHandler.post { onResult(false, "", installedVersionName, "", 0L) }
                return@execute
            }
            var latestRemoteVer: List<Int> = emptyList()
            var latestRemoteName = ""
            var latestRemoteUrl = ""
            var latestRemoteSize = 25_000_000L
            for (release in releasesList) {
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val assetSize = asset.optLong("size", 25_000_000L)
                    if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                        val ver = extractVersionNumbers(assetName)
                        if (isVersionNewer(ver, latestRemoteVer)) {
                            latestRemoteVer = ver
                            latestRemoteName = assetName
                            latestRemoteUrl = downloadUrl
                            latestRemoteSize = assetSize
                        }
                    }
                }
            }
            val hasUpdate = latestRemoteVer.isNotEmpty() && isVersionNewer(latestRemoteVer, localInstalledVer)
            saveCachedAppUpdate(context, hasUpdate, latestRemoteName, latestRemoteUrl, latestRemoteSize)
            mainHandler.post { onResult(hasUpdate, latestRemoteName, installedVersionName, latestRemoteUrl, latestRemoteSize) }
        }
    }

    private fun fetchReleasesFromUrl(urlStr: String): List<org.json.JSONObject> {
        var conn: HttpURLConnection? = null
        val result = mutableListOf<org.json.JSONObject>()
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mushroom-Updater/1.0")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (jsonText.startsWith("{")) {
                    result.add(org.json.JSONObject(jsonText))
                } else if (jsonText.startsWith("[")) {
                    val arr = org.json.JSONArray(jsonText)
                    for (i in 0 until arr.length()) {
                        result.add(arr.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log("AppUpdateManager", "fetchReleasesFromUrl", false, "Error fetching from $urlStr: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return result
    }

    fun getApkDownloadDir(): File {
        val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!pubDir.exists()) {
            try { pubDir.mkdirs() } catch (_: Exception) {}
        }
        val direct = File("/storage/emulated/0/Download")
        if (pubDir.exists() && pubDir.canWrite()) return pubDir
        if (!direct.exists()) {
            try { direct.mkdirs() } catch (_: Exception) {}
        }
        if (direct.exists() && direct.canWrite()) return direct
        return pubDir
    }

    fun getApkFile(verName: String): File {
        val name = if (verName.endsWith(".apk", ignoreCase = true)) verName else "$verName.apk"
        return File(getApkDownloadDir(), name)
    }

    fun installApk(context: Context, apkFile: File) {
        mainHandler.post {
            try {
                if (!apkFile.exists()) {
                    AppLogger.log("AppUpdateManager", "installApk", false, "APK file does not exist: ${apkFile.absolutePath}")
                    return@post
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLogger.log("AppUpdateManager", "installApk", true, "Launched installer intent for ${apkFile.name}")
            } catch (e: Exception) {
                AppLogger.log("AppUpdateManager", "installApk", false, "Failed to launch installer intent: ${e.message}")
            }
        }
    }

    fun downloadApkDirect(
        urlStr: String,
        destFile: File,
        onBytesRead: (Long) -> Unit,
        onSizeDiscovered: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        return com.olegskal.mushroom.ui.InitialSetupDialog.downloadFileWithResume(
            urlStr = urlStr,
            destFile = destFile,
            onBytesRead = onBytesRead,
            onSizeDiscovered = onSizeDiscovered,
            isCancelled = isCancelled
        )
    }
}
