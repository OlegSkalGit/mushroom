package com.olegskal.mushroom.mushrooms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

data class MushroomTaxon(
    val id: Int,
    val scientificName: String,
    val commonName: String,
    val defaultPhotoUrl: String?,
    val photoUrls: List<String> = emptyList(),
    val wikipediaSummary: String? = null,
    val wikipediaUrl: String? = null,
    val edibility: String = "unknown",
    val hymenium: String = "other",
    val observationsCount: Int = 0,
    val rank: String = "species",
    val family: String? = null,
    val order: String? = null,
    val genus: String? = null,
    val englishCommonName: String? = null,
    val conservationStatus: String? = null,
    val phylum: String? = null,
    val taxonClass: String? = null,
    val lookalikesUk: String? = null,
    val lookalikesEn: String? = null,
    val propsCap: String? = null,
    val propsHymenium: String? = null,
    val propsStem: String? = null,
    val propsFlesh: String? = null,
    val propsSeason: String? = null,
    val propsHabitat: String? = null,
    val ecologyUk: String? = null,
    val ecologyEn: String? = null,
    val toxicityUk: String? = null,
    val toxicityEn: String? = null,
    val useUk: String? = null,
    val useEn: String? = null
)

object MushroomApiClient {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    var appContext: android.content.Context? = null

    fun setContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    fun loadBitmap(urlString: String?, callback: (Bitmap?) -> Unit) {
        if (urlString.isNullOrEmpty()) {
            callback(null)
            return
        }

        val cached = memoryCache.get(urlString)
        if (cached != null) {
            callback(cached)
            return
        }

        if (urlString.startsWith("db://photo/")) {
            val photoId = urlString.removePrefix("db://photo/").toIntOrNull()
            executor.execute {
                val ctx = appContext
                if (photoId != null && ctx != null) {
                    val blob = MushroomDatabaseManager.loadPhotoBlob(ctx, photoId)
                    if (blob != null) {
                        val bmp = BitmapFactory.decodeByteArray(blob, 0, blob.size)
                        if (bmp != null) {
                            memoryCache.put(urlString, bmp)
                            mainHandler.post { callback(bmp) }
                            return@execute
                        }
                    }
                }
                mainHandler.post { callback(null) }
            }
            return
        }

        callback(null)
    }
}
