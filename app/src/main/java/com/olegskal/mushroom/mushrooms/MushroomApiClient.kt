package com.olegskal.mushroom.mushrooms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
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
    val hymenium: String = "gills",
    val observationsCount: Int = 0,
    val rank: String = "species",
    val family: String? = null,
    val order: String? = null,
    val genus: String? = null,
    val englishCommonName: String? = null,
    val conservationStatus: String? = null
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

    private var diskCacheDir: File? = null

    fun initDiskCache(baseDir: File) {
        val dir = File(baseDir, "cache/mushrooms")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        diskCacheDir = dir
    }

    fun searchTaxa(
        query: String,
        lang: String,
        page: Int = 1,
        perPage: Int = 24,
        onResult: (List<MushroomTaxon>, Boolean) -> Unit
    ) {
        executor.execute {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val urlString = "https://api.inaturalist.org/v1/taxa?taxon_id=47170&q=$encodedQuery&has[]=photos&locale=$lang&per_page=$perPage&page=$page"
            executeTaxaRequest(urlString, lang, onResult)
        }
    }

    fun getPopularTaxa(
        isUkraine: Boolean,
        lang: String,
        page: Int = 1,
        perPage: Int = 24,
        onResult: (List<MushroomTaxon>, Boolean) -> Unit
    ) {
        executor.execute {
            val urlString = if (isUkraine) {
                // Agaricomycetes in Ukraine (place_id=8860, taxon_id=50814)
                "https://api.inaturalist.org/v1/observations/species_counts?taxon_id=50814&place_id=8860&locale=$lang&per_page=$perPage&page=$page"
            } else {
                // Worldwide popular Agaricomycetes (rank=species, taxon_id=50814)
                "https://api.inaturalist.org/v1/taxa?taxon_id=50814&rank=species&is_active=true&order_by=observations_count&order=desc&has[]=photos&locale=$lang&per_page=$perPage&page=$page"
            }
            executeTaxaRequest(urlString, lang, onResult)
        }
    }

    fun getTaxonDetails(
        taxonId: Int,
        scientificName: String,
        lang: String,
        onResult: (MushroomTaxon?) -> Unit
    ) {
        executor.execute {
            try {
                val resolvedTaxonId = if (taxonId > 0) {
                    taxonId
                } else {
                    val searchUrl = "https://api.inaturalist.org/v1/taxa?taxon_id=47170&q=${URLEncoder.encode(scientificName, "UTF-8")}&locale=$lang"
                    val jsonStr = fetchUrlString(searchUrl)
                    val root = JSONObject(jsonStr)
                    val resArr = root.optJSONArray("results")
                    if (resArr != null && resArr.length() > 0) {
                        resArr.getJSONObject(0).optInt("id", 0)
                    } else 0
                }

                if (resolvedTaxonId > 0) {
                    val detailUrl = "https://api.inaturalist.org/v1/taxa/$resolvedTaxonId?locale=$lang"
                    val jsonStr = fetchUrlString(detailUrl)
                    val root = JSONObject(jsonStr)
                    val resArr = root.optJSONArray("results")
                    if (resArr != null && resArr.length() > 0) {
                        val obj = resArr.getJSONObject(0)
                        var taxon = parseTaxonObject(obj, lang)

                        // If Wikipedia summary is missing in current language, fallback to English
                        if (taxon.wikipediaSummary.isNullOrBlank()) {
                            // 1. Try iNaturalist locale=en
                            try {
                                val enUrl = "https://api.inaturalist.org/v1/taxa/$resolvedTaxonId?locale=en"
                                val enJson = fetchUrlString(enUrl)
                                val enObj = JSONObject(enJson).optJSONArray("results")?.optJSONObject(0)
                                val enSummary = enObj?.optString("wikipedia_summary", "")?.takeIf { it.isNotBlank() }
                                if (enSummary != null) {
                                    taxon = taxon.copy(wikipediaSummary = cleanHtml(enSummary))
                                }
                            } catch (ignored: Exception) {}

                            // 2. If still blank, try Wikipedia REST API directly for English summary
                            if (taxon.wikipediaSummary.isNullOrBlank()) {
                                try {
                                    val wikiRestUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(taxon.scientificName, "UTF-8")}"
                                    val wikiJson = fetchUrlString(wikiRestUrl)
                                    val extract = JSONObject(wikiJson).optString("extract", "").takeIf { it.isNotBlank() }
                                    if (extract != null) {
                                        taxon = taxon.copy(wikipediaSummary = extract)
                                    }
                                } catch (ignored: Exception) {}
                            }
                        }

                        mainHandler.post { onResult(taxon) }
                        return@execute
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post { onResult(null) }
        }
    }

    private fun executeTaxaRequest(urlString: String, lang: String, onResult: (List<MushroomTaxon>, Boolean) -> Unit) {
        try {
            val jsonStr = fetchUrlString(urlString)
            val root = JSONObject(jsonStr)
            val resultsArr = root.optJSONArray("results") ?: JSONArray()
            val totalResults = root.optInt("total_results", 0)
            val perPage = root.optInt("per_page", 24)
            val page = root.optInt("page", 1)
            val hasMore = resultsArr.length() >= perPage && ((page * perPage) < totalResults || totalResults == 0)

            val list = mutableListOf<MushroomTaxon>()
            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.getJSONObject(i)
                val taxonObj = if (item.has("taxon")) item.getJSONObject("taxon") else item
                val taxon = parseTaxonObject(taxonObj, lang)
                if (taxon.scientificName != "Unknown species") {
                    list.add(taxon)
                }
            }

            mainHandler.post {
                onResult(list, hasMore)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post {
                onResult(emptyList(), false)
            }
        }
    }

    private fun parseTaxonObject(obj: JSONObject, lang: String): MushroomTaxon {
        val id = obj.optInt("id", 0)
        val scientificName = obj.optString("name", "Unknown species")
        val preferredCommon = obj.optString("preferred_common_name", "")
        val englishCommon = obj.optString("english_common_name", "").takeIf { it.isNotBlank() }
        val common = when {
            preferredCommon.isNotEmpty() -> preferredCommon
            !englishCommon.isNullOrEmpty() -> englishCommon
            else -> scientificName
        }

        var defaultPhotoUrl: String? = null
        val defaultPhotoObj = obj.optJSONObject("default_photo")
        if (defaultPhotoObj != null) {
            val med = defaultPhotoObj.optString("medium_url")
            val sq = defaultPhotoObj.optString("square_url")
            defaultPhotoUrl = when {
                med.isNotEmpty() -> med
                sq.isNotEmpty() -> sq
                else -> null
            }
        }
        if (defaultPhotoUrl == null) {
            val photosArr = obj.optJSONArray("photos")
            if (photosArr != null && photosArr.length() > 0) {
                val pObj = photosArr.getJSONObject(0)
                val u = pObj.optString("medium_url").takeIf { it.isNotEmpty() }
                    ?: pObj.optString("url").takeIf { it.isNotEmpty() }
                defaultPhotoUrl = u
            }
        }

        val photoUrls = mutableListOf<String>()
        if (defaultPhotoUrl != null) photoUrls.add(defaultPhotoUrl)

        val taxonPhotos = obj.optJSONArray("taxon_photos")
        if (taxonPhotos != null) {
            for (j in 0 until taxonPhotos.length()) {
                val pObj = taxonPhotos.getJSONObject(j).optJSONObject("photo")
                val url = pObj?.optString("medium_url") ?: pObj?.optString("large_url")
                if (!url.isNullOrEmpty() && !photoUrls.contains(url)) {
                    photoUrls.add(url)
                }
            }
        }

        val wikiSummary = obj.optString("wikipedia_summary", "").takeIf { it.isNotBlank() }
        val wikiUrl = obj.optString("wikipedia_url", "").takeIf { it.isNotBlank() }
        val observationsCount = obj.optInt("observations_count", 0)
        val rank = obj.optString("rank", "species")

        // Parse taxonomic hierarchy from ancestors
        var family: String? = null
        var order: String? = null
        var genus: String? = null
        val ancestors = obj.optJSONArray("ancestors")
        if (ancestors != null) {
            for (i in 0 until ancestors.length()) {
                val anc = ancestors.getJSONObject(i)
                when (anc.optString("rank")) {
                    "family" -> family = anc.optString("name")
                    "order" -> order = anc.optString("name")
                    "genus" -> genus = anc.optString("name")
                }
            }
        }

        // Parse conservation status
        val conservationStatuses = obj.optJSONArray("conservation_statuses")
        var conservationStatus: String? = null
        if (conservationStatuses != null && conservationStatuses.length() > 0) {
            val cs = conservationStatuses.getJSONObject(0)
            val st = cs.optString("status")
            val auth = cs.optString("authority")
            if (st.isNotEmpty()) {
                conservationStatus = if (auth.isNotEmpty()) "$st ($auth)" else st
            }
        }

        val meta = MycoKnowledge.resolveMetadata(scientificName)

        return MushroomTaxon(
            id = id,
            scientificName = scientificName,
            commonName = common,
            defaultPhotoUrl = defaultPhotoUrl,
            photoUrls = photoUrls,
            wikipediaSummary = cleanHtml(wikiSummary),
            wikipediaUrl = wikiUrl,
            edibility = meta.edibility,
            hymenium = meta.hymenium,
            observationsCount = observationsCount,
            rank = rank,
            family = family,
            order = order,
            genus = genus,
            englishCommonName = englishCommon,
            conservationStatus = conservationStatus
        )
    }

    private fun cleanHtml(html: String?): String? {
        if (html == null) return null
        return html.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun fetchUrlString(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "MushroomApp/1.0 (Android; Contact: olegskal)")
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("HTTP Error: $responseCode")
        }

        val reader = conn.inputStream.bufferedReader()
        val content = reader.readText()
        reader.close()
        conn.disconnect()
        return content
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

        executor.execute {
            val diskFile = getDiskCacheFile(urlString)
            if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    memoryCache.put(urlString, bmp)
                    mainHandler.post { callback(bmp) }
                    return@execute
                }
            }

            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.connect()

                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        memoryCache.put(urlString, bmp)
                        if (diskFile != null) {
                            try {
                                FileOutputStream(diskFile).use { it.write(bytes) }
                            } catch (ignored: Exception) {}
                        }
                        mainHandler.post { callback(bmp) }
                        return@execute
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // ignore
            }
            mainHandler.post { callback(null) }
        }
    }

    private fun getDiskCacheFile(url: String): File? {
        val dir = diskCacheDir ?: return null
        val hash = md5(url)
        return File(dir, "$hash.img")
    }

    private fun md5(s: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digested = md.digest(s.toByteArray())
            digested.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            s.hashCode().toString()
        }
    }
}
