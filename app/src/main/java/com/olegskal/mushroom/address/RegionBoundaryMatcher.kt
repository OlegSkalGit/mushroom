package com.olegskal.mushroom.address

import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class RegionBoundaryMatcher(
    val regions: List<RegionPolygon>
) {
    companion object {
        private const val TAG = "RegionBoundaryMatcher"

        data class RegionPolygon(
            val isoCode: String,
            val name: String,
            val minLat: Double,
            val maxLat: Double,
            val minLon: Double,
            val maxLon: Double,
            val rings: List<List<DoubleArray>> // [ [ [lon, lat], ... ], ... ]
        ) {
            val bboxArea: Double = (maxLat - minLat) * (maxLon - minLon)
        }

        fun fromGeoJsonFile(file: File): RegionBoundaryMatcher? {
            if (!file.exists() || file.length() < 100L) return null
            return try {
                val text = file.readText(Charsets.UTF_8)
                val root = JSONObject(text)
                val features = root.optJSONArray("features") ?: return null
                val list = mutableListOf<RegionPolygon>()

                for (i in 0 until features.length()) {
                    val feat = features.optJSONObject(i) ?: continue
                    val props = feat.optJSONObject("properties") ?: JSONObject()
                    val iso = props.optString("shapeISO").ifBlank {
                        props.optString("ISO3166-2").ifBlank {
                            props.optString("code")
                        }
                    }.trim()
                    val name = props.optString("shapeName").ifBlank {
                        props.optString("name").ifBlank {
                            props.optString("name:uk")
                        }
                    }.trim()

                    val geom = feat.optJSONObject("geometry") ?: continue
                    val gType = geom.optString("type")
                    val coords = geom.optJSONArray("coordinates") ?: continue

                    var minLat = 90.0
                    var maxLat = -90.0
                    var minLon = 180.0
                    var maxLon = -180.0
                    val allRings = mutableListOf<List<DoubleArray>>()

                    fun parseRing(ringJson: org.json.JSONArray) {
                        val ring = ArrayList<DoubleArray>(ringJson.length())
                        for (pIdx in 0 until ringJson.length()) {
                            val pt = ringJson.optJSONArray(pIdx) ?: continue
                            val lon = pt.optDouble(0)
                            val lat = pt.optDouble(1)
                            if (lat < minLat) minLat = lat
                            if (lat > maxLat) maxLat = lat
                            if (lon < minLon) minLon = lon
                            if (lon > maxLon) maxLon = lon
                            ring.add(doubleArrayOf(lon, lat))
                        }
                        if (ring.size >= 3) {
                            allRings.add(ring)
                        }
                    }

                    if (gType.equals("Polygon", ignoreCase = true)) {
                        for (rIdx in 0 until coords.length()) {
                            val rJson = coords.optJSONArray(rIdx) ?: continue
                            parseRing(rJson)
                        }
                    } else if (gType.equals("MultiPolygon", ignoreCase = true)) {
                        for (pIdx in 0 until coords.length()) {
                            val pJson = coords.optJSONArray(pIdx) ?: continue
                            for (rIdx in 0 until pJson.length()) {
                                val rJson = pJson.optJSONArray(rIdx) ?: continue
                                parseRing(rJson)
                            }
                        }
                    }

                    if (allRings.isNotEmpty() && iso.isNotBlank()) {
                        list.add(RegionPolygon(iso, name, minLat, maxLat, minLon, maxLon, allRings))
                    }
                }

                if (list.isEmpty()) return null
                // Сортуємо від менших до більших за BBox (міста-анклави типу Київ/Севастополь перевіряються першими)
                list.sortBy { it.bboxArea }
                RegionBoundaryMatcher(list)
            } catch (e: Exception) {
                AppLogger.log(TAG, "fromGeoJsonFile", false, "Error parsing boundaries GeoJSON: ${e.message}")
                null
            }
        }

        fun getCountryIso3(countryCode: String): String {
            val code = countryCode.uppercase().trim()
            return when (code) {
                "UA" -> "UKR"
                "PL" -> "POL"
                "DE" -> "DEU"
                "FR" -> "FRA"
                "IT" -> "ITA"
                "ES" -> "ESP"
                "RO" -> "ROU"
                "SK" -> "SVK"
                "CZ" -> "CZE"
                "HU" -> "HUN"
                "AT" -> "AUT"
                "CH" -> "CHE"
                "GB" -> "GBR"
                "US" -> "USA"
                "CA" -> "CAN"
                "BY" -> "BLR"
                "MD" -> "MDA"
                "BG" -> "BGR"
                "HR" -> "HRV"
                "RS" -> "SRB"
                "NO" -> "NOR"
                "SE" -> "SWE"
                "FI" -> "FIN"
                else -> try {
                    Locale("", code).isO3Country.uppercase()
                } catch (_: Exception) {
                    code
                }
            }
        }

        fun downloadAndLoad(
            countryCode: String,
            logFn: (String) -> Unit = {}
        ): RegionBoundaryMatcher? {
            val cleanCode = countryCode.uppercase().trim()
            val targetDir = MushroomStorageManager.addressesDir.apply { if (!exists()) mkdirs() }
            val boundaryFile = File(targetDir, "boundaries_$cleanCode.geojson")

            // 1. Якщо файл уже кешовано локально
            if (boundaryFile.exists() && boundaryFile.length() > 1024L) {
                val matcher = fromGeoJsonFile(boundaryFile)
                if (matcher != null) {
                    logFn("Використовуємо кешовані межі регіонів для $cleanCode")
                    return matcher
                }
            }

            // 2. Якщо це Україна і поруч у папці адрес уже є завантажений файл
            val candidateLocal = File(targetDir, "geoBoundaries-UKR-ADM1_simplified.geojson")
            if (cleanCode == "UA" && candidateLocal.exists() && candidateLocal.length() > 1024L) {
                try {
                    candidateLocal.copyTo(boundaryFile, overwrite = true)
                    val matcher = fromGeoJsonFile(boundaryFile)
                    if (matcher != null) return matcher
                } catch (_: Exception) {}
            }

            // 3. Завантаження з офіційного відкритого API geoBoundaries
            val iso3 = getCountryIso3(cleanCode)
            logFn("Завантаження меж регіонів із geoBoundaries для $cleanCode ($iso3)...")
            try {
                val apiUrl = "https://www.geoboundaries.org/api/current/gbOpen/$iso3/ADM1/"
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mushroom-Address-App")
                if (conn.responseCode == 200) {
                    val apiJsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val apiJson = JSONObject(apiJsonText)
                    val dlUrl = apiJson.optString("simplifiedGeometryGeoJSON").ifBlank {
                        apiJson.optString("gjDownloadURL")
                    }
                    if (dlUrl.isNotBlank()) {
                        val dlConn = URL(dlUrl).openConnection() as HttpURLConnection
                        dlConn.connectTimeout = 15000
                        dlConn.readTimeout = 25000
                        dlConn.setRequestProperty("User-Agent", "Mushroom-Address-App")
                        if (dlConn.responseCode == 200) {
                            val tempFile = File(targetDir, "temp_boundaries_$cleanCode.geojson")
                            dlConn.inputStream.use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (tempFile.length() > 1024L) {
                                tempFile.copyTo(boundaryFile, overwrite = true)
                                tempFile.delete()
                                val matcher = fromGeoJsonFile(boundaryFile)
                                if (matcher != null) {
                                    logFn("Успішно завантажено та активовано ${matcher.regions.size} контурів регіонів для $cleanCode")
                                    return matcher
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(TAG, "downloadAndLoad", false, "Failed to download boundaries from geoBoundaries: ${e.message}")
            }

            return null
        }

        fun deleteBoundaryFile(countryCode: String) {
            try {
                val cleanCode = countryCode.uppercase().trim()
                val targetDir = MushroomStorageManager.addressesDir
                File(targetDir, "boundaries_$cleanCode.geojson").delete()
                File(targetDir, "temp_boundaries_$cleanCode.geojson").delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * Швидке O(1) визначення ISO-коду регіону за координатами точки.
     * Працює через BBox + Ray-casting.
     */
    fun findRegionCode(lat: Double, lon: Double): String? {
        for (r in regions) {
            if (lat in r.minLat..r.maxLat && lon in r.minLon..r.maxLon) {
                for (ring in r.rings) {
                    if (pointInPolygon(lat, lon, ring)) {
                        return r.isoCode
                    }
                }
            }
        }
        return null
    }

    private fun pointInPolygon(lat: Double, lon: Double, ring: List<DoubleArray>): Boolean {
        var inside = false
        val n = ring.size
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[i][0] // lon
            val yi = ring[i][1] // lat
            val xj = ring[j][0]
            val yj = ring[j][1]

            val intersect = ((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
            if (intersect) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
