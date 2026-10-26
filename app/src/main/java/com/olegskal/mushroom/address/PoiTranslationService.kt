package com.olegskal.mushroom.address

import android.database.sqlite.SQLiteDatabase
import com.olegskal.mushroom.util.AppLogger
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object PoiTranslationService {

    private const val TAG = "PoiTranslationService"

    fun resolveLanguageCode(countryCode: String): String {
        return when (countryCode.uppercase().trim()) {
            "UA" -> "uk"
            "PL" -> "pl"
            "DE", "AT" -> "de"
            "FR" -> "fr"
            "RO", "MD" -> "ro"
            "IT" -> "it"
            "ES" -> "es"
            "PT" -> "pt"
            "CZ" -> "cs"
            "SK" -> "sk"
            "HU" -> "hu"
            "GR" -> "el"
            "BG" -> "bg"
            "TR" -> "tr"
            "NL" -> "nl"
            "SE" -> "sv"
            "NO" -> "no"
            "FI" -> "fi"
            "DK" -> "da"
            "LT" -> "lt"
            "LV" -> "lv"
            "EE" -> "et"
            "HR" -> "hr"
            "RS", "ME", "BA" -> "sr"
            "SI" -> "sl"
            "GB", "US", "CA", "AU", "NZ" -> "en"
            else -> countryCode.lowercase().trim()
        }
    }

    /**
     * Перевіряє наявність перекладів для країни в таблиці poi_types.
     * Якщо перекладів немає або вони неповні - перекладає через API (Google GTX / MyMemory)
     * і записує в poi_types.
     * Повертає мапу tag -> poi_types.id для швидкого присвоєння точкам.
     */
    fun ensureTranslationsForCountry(
        db: SQLiteDatabase,
        countryCode: String,
        logCallback: (String) -> Unit = {}
    ): Map<String, Long> {
        val cCode = countryCode.uppercase().trim()
        val langCode = resolveLanguageCode(cCode)

        AddressDb.ensureTablesExist(db)

        val resultMap = mutableMapOf<String, Long>()

        // 1. Читаємо вже наявні переклади з бази
        val cursor = db.rawQuery(
            "SELECT id, tag FROM poi_types WHERE country_code = ?;",
            arrayOf(cCode)
        )
        cursor.use {
            while (it.moveToNext()) {
                resultMap[it.getString(1)] = it.getLong(0)
            }
        }

        val allTags = PoiTagCatalog.TAGS
        val missing = allTags.filter { !resultMap.containsKey(it.tag) }

        if (missing.isEmpty()) {
            return resultMap
        }

        logCallback("Отримання перекладів типів точок для $cCode (${missing.size} нових)...")

        // 2. Якщо мова англійська - переклад не потрібен
        val translatedMap = mutableMapOf<String, String>()
        if (langCode == "en") {
            for (m in missing) {
                translatedMap[m.tag] = m.descEn
            }
        } else {
            // Спроба 1: Пакетний переклад через Google Translate (GTX)
            val gSuccess = tryBatchTranslateGoogle(missing, langCode, translatedMap)
            if (!gSuccess) {
                // Спроба 2: Резервний MyMemory API
                logCallback("Резервний переклад через MyMemory API...")
                tryTranslateMyMemory(missing, langCode, translatedMap)
            }
        }

        // 3. Зберігаємо в poi_types
        db.beginTransaction()
        var stmt: android.database.sqlite.SQLiteStatement? = null
        try {
            stmt = db.compileStatement("""
                INSERT OR REPLACE INTO poi_types (group_category, tag, desc_en, desc_local, country_code)
                VALUES (?, ?, ?, ?, ?);
            """.trimIndent())

            for (def in missing) {
                val localDesc = translatedMap[def.tag]?.ifBlank { def.descEn } ?: def.descEn
                stmt.bindString(1, def.groupCategory)
                stmt.bindString(2, def.tag)
                stmt.bindString(3, def.descEn)
                stmt.bindString(4, localDesc)
                stmt.bindString(5, cCode)
                val newId = stmt.executeInsert()
                if (newId > 0) {
                    resultMap[def.tag] = newId
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            AppLogger.log(TAG, "ensureTranslationsForCountry", false, "Error inserting poi_types: ${e.message}")
        } finally {
            try { stmt?.close() } catch (_: Exception) {}
            db.endTransaction()
        }

        // Оновлюємо id тих, що збереглися
        val curRefresh = db.rawQuery(
            "SELECT id, tag FROM poi_types WHERE country_code = ?;",
            arrayOf(cCode)
        )
        curRefresh.use {
            while (it.moveToNext()) {
                resultMap[it.getString(1)] = it.getLong(0)
            }
        }

        return resultMap
    }

    /**
     * Джерело 1: Google Translate GTX (пакетний запит для всього списку)
     */
    private fun tryBatchTranslateGoogle(
        items: List<PoiTagDef>,
        targetLang: String,
        outMap: MutableMap<String, String>
    ): Boolean {
        return try {
            val combinedText = items.joinToString("\n") { it.descEn }
            val encoded = URLEncoder.encode(combinedText, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=$targetLang&dt=t&q=$encoded"

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return false
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val jsonArray = JSONArray(body)
            val chunks = jsonArray.getJSONArray(0)
            val translatedLines = mutableListOf<String>()

            for (i in 0 until chunks.length()) {
                val pair = chunks.getJSONArray(i)
                val piece = pair.getString(0)
                if (piece.isNotBlank()) {
                    translatedLines.addAll(piece.split("\n").map { it.trim() }.filter { it.isNotBlank() })
                }
            }

            if (translatedLines.size >= items.size) {
                for (i in items.indices) {
                    outMap[items[i].tag] = translatedLines[i]
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.log(TAG, "tryBatchTranslateGoogle", false, "Google Translate error: ${e.message}")
            false
        }
    }

    /**
     * Джерело 2: MyMemory Translation API (послідовні запити для пропущених)
     */
    private fun tryTranslateMyMemory(
        items: List<PoiTagDef>,
        targetLang: String,
        outMap: MutableMap<String, String>
    ) {
        for (item in items) {
            if (outMap.containsKey(item.tag)) continue
            try {
                val enc = URLEncoder.encode(item.descEn, "UTF-8")
                val urlStr = "https://api.mymemory.translated.net/get?q=$enc&langpair=en|$targetLang"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    val obj = org.json.JSONObject(body)
                    val trans = obj.optJSONObject("responseData")?.optString("translatedText")
                    if (!trans.isNullOrBlank()) {
                        outMap[item.tag] = trans.trim()
                        continue
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {}

            // Фолбек на англійський опис
            outMap[item.tag] = item.descEn
        }
    }
}
