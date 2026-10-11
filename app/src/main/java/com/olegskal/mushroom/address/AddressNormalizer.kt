package com.olegskal.mushroom.address

import java.util.regex.Pattern

object AddressNormalizer {

    private val abbrevPatterns = listOf(
        // Ukrainian
        Pair(Pattern.compile("\\bвул\\.(?!\\w)|\\bвул\\b", Pattern.CASE_INSENSITIVE), "вулиця"),
        Pair(Pattern.compile("\\bпров\\.(?!\\w)|\\bпров\\b", Pattern.CASE_INSENSITIVE), "провулок"),
        Pair(Pattern.compile("\\bпросп\\.(?!\\w)|\\bпр\\.(?!\\w)|\\bпросп\\b", Pattern.CASE_INSENSITIVE), "проспект"),
        Pair(Pattern.compile("\\bбул\\.(?!\\w)|\\bб-р\\.(?!\\w)|\\bб-р(?!\\w)|\\bбул\\b", Pattern.CASE_INSENSITIVE), "бульвар"),
        Pair(Pattern.compile("\\bпл\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "площа"),
        Pair(Pattern.compile("\\bнаб\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "набережна"),
        Pair(Pattern.compile("\\bтуп\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "тупик"),
        Pair(Pattern.compile("\\bузв\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "узвіз"),
        Pair(Pattern.compile("\\bпр-д\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "проїзд"),
        // English
        Pair(Pattern.compile("\\bSt\\.(?!\\w)|\\bSt\\b", Pattern.CASE_INSENSITIVE), "Street"),
        Pair(Pattern.compile("\\bAve\\.(?!\\w)|\\bAve\\b", Pattern.CASE_INSENSITIVE), "Avenue"),
        Pair(Pattern.compile("\\bRd\\.(?!\\w)|\\bRd\\b", Pattern.CASE_INSENSITIVE), "Road"),
        Pair(Pattern.compile("\\bBlvd\\.(?!\\w)|\\bBlvd\\b", Pattern.CASE_INSENSITIVE), "Boulevard"),
        Pair(Pattern.compile("\\bDr\\.(?!\\w)|\\bDr\\b", Pattern.CASE_INSENSITIVE), "Drive"),
        Pair(Pattern.compile("\\bLn\\.(?!\\w)|\\bLn\\b", Pattern.CASE_INSENSITIVE), "Lane"),
        Pair(Pattern.compile("\\bCt\\.(?!\\w)|\\bCt\\b", Pattern.CASE_INSENSITIVE), "Court"),
        Pair(Pattern.compile("\\bPl\\.(?!\\w)|\\bPl\\b", Pattern.CASE_INSENSITIVE), "Place"),
        Pair(Pattern.compile("\\bSq\\.(?!\\w)|\\bSq\\b", Pattern.CASE_INSENSITIVE), "Square"),
        Pair(Pattern.compile("\\bHwy\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "Highway"),
        // French
        Pair(Pattern.compile("\\bR\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "Rue"),
        Pair(Pattern.compile("\\bAv\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "Avenue"),
        Pair(Pattern.compile("\\bBd\\.(?!\\w)|\\bBd\\b", Pattern.CASE_INSENSITIVE), "Boulevard"),
        Pair(Pattern.compile("\\bAll\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "Allée"),
        Pair(Pattern.compile("\\bImp\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "Impasse"),
        // German
        Pair(Pattern.compile("\\bStr\\.(?!\\w)", Pattern.CASE_INSENSITIVE), "Straße"),
        // Polish
        Pair(Pattern.compile("\\bul\\.(?!\\w)|\\bul\\b", Pattern.CASE_INSENSITIVE), "ulica"),
        Pair(Pattern.compile("\\bal\\.(?!\\w)|\\bal\\b", Pattern.CASE_INSENSITIVE), "aleja")
    )

    private val postPrefixes = mapOf(
        "вулиця" to "Вулиця", "провулок" to "Провулок", "проспект" to "Проспект",
        "бульвар" to "Бульвар", "площа" to "Площа", "набережна" to "Набережна",
        "узвіз" to "Узвіз", "тупик" to "Тупик", "алея" to "Алея", "проїзд" to "Проїзд",
        "тракт" to "Тракт", "ulica" to "Ulica", "aleja" to "Aleja", "plac" to "Plac"
    )

    private val settlementPrefixes = listOf(
        Pattern.compile("^(?:City|Town|Village|Borough|Municipality)\\s+of\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:Commune|Ville)\\s+de\\s+(?:la\\s+|l')?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:Municipio|Ayuntamiento)\\s+de\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^Comune\\s+di\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:Stadt|Gemeinde|Markt|Verbandsgemeinde)\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:Miasto\\s+i\\s+Gmina|Gmina|Miasto)\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:городской\\s+округ|муниципальный\\s+округ|город|г\\.|с\\.|пос\\.|смт)\\s+", Pattern.CASE_INSENSITIVE)
    )

    private val settlementSuffixes = listOf(
        Pattern.compile("\\s+(?:міська|селищна|сільська)?\\s*громада.*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\s+(?:городской|муниципальный)\\s+округ.*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\s+Municipality$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\s+Borough$", Pattern.CASE_INSENSITIVE)
    )

    fun canonicalStreetNameUniversal(raw: String): String {
        if (raw.isBlank()) return ""
        var n = raw.trim().replace(Regex("[,;]+$"), "").replace(Regex("\\s+"), " ")
        for ((pat, repl) in abbrevPatterns) {
            n = pat.matcher(n).replaceAll(repl)
        }
        val words = n.split(" ").filter { it.isNotBlank() }
        if (words.size > 1 && postPrefixes.containsKey(words.last().lowercase())) {
            val pref = postPrefixes[words.last().lowercase()]!!
            n = "$pref " + words.subList(0, words.size - 1).joinToString(" ")
        } else if (n.isNotEmpty()) {
            n = n[0].uppercaseChar() + n.substring(1)
        }
        return n.replace(Regex("\\s+"), " ").trim()
    }

    fun cleanSettlementNameUniversal(raw: String, shortName: String? = null): String {
        if (!shortName.isNullOrBlank()) return shortName.trim()
        if (raw.isBlank()) return ""
        var n = raw.trim()
        for (pat in settlementPrefixes) {
            n = pat.matcher(n).replaceAll("").trim()
        }
        for (pat in settlementSuffixes) {
            n = pat.matcher(n).replaceAll("").trim()
        }
        return n
    }
}
