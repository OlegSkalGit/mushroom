package com.olegskal.mushroom.mushrooms

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File

data class MycoProps(
    val cap: String = "",
    val hymenophore: String = "",
    val stem: String = "",
    val flesh: String = "",
    val season: String = "",
    val habitat: String = ""
)

data class MycoClassInfo(
    val edibility: String,
    val hymenium: String
)

data class MycoSpeciesData(
    val edibility: String, // "edible", "inedible", "unknown"
    val hymenium: String = "other", // "gills", "tubes", "other"
    val lookalikesUk: String? = null,
    val lookalikesEn: String? = null,
    val propsUk: MycoProps? = null,
    val propsEn: MycoProps? = null
)

object MycoKnowledge {

    val KNOWLEDGE: Map<String, MycoSpeciesData> = mapOf(
        "amanita phalloides" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "СМЕРТЕЛЬНА НЕБЕЗПЕКА! Часто плутають із Зеленою Сироїжкою (у сироїжки ніколи немає вольви та кільця!) або Печерицею (у зрілих печериць пластинки рожеві чи шоколадно-коричневі, а в поганки — завжди чисто білі).",
            lookalikesEn = "FATAL DANGER! Often confused with green Russula (Russulas lack a volva and ring) or Field Agaricus (Agaricus have pink-to-brown gills; death cap gills remain pure white).",
            propsUk = MycoProps(
                cap = "Оливково-зелена, шовковиста 5-15 см",
                hymenophore = "Пластинки завжди чисто БІЛІ",
                stem = "Біла, з візерунком, кільцем та вільною вольвою (мішечком) внизу!",
                flesh = "Білий, не змінює колір",
                season = "Липень — Листопад",
                habitat = "Листяні ліси (дуб, бук, береза)"
            ),
            propsEn = MycoProps(
                cap = "Olive-green, silky 5-15 cm",
                hymenophore = "Gills always pure WHITE",
                stem = "White with membranous ring and free sack-like volva at base!",
                flesh = "White, unchanging",
                season = "July — November",
                habitat = "Deciduous oak and beech woods"
            )
        ),
        "amanita virosa" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Мухомор білий смердючий. Смертельно отруйний! Плутають із білими печерицями. Має неприємний запах, білі пластинки та вольву внизу ніжки.",
            lookalikesEn = "Destroying Angel. Lethally toxic! Confused with white champignon mushrooms. Note pure white gills, persistent ring, and basal volva cup."
        ),
        "galerina marginata" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "СМЕРТЕЛЬНИЙ ДВІЙНИК ОПЕНЬКІВ! Росте на пнях. На відміну від опенька, має коричневий споровий порошок, темнішу основу ніжки та борошнистий запах.",
            lookalikesEn = "DEADLY LOOKALIKE OF HONEY FUNGUS! Contains amatoxins. Note rusty-brown spore print, smooth silky-fibrillose stem, and mealy smell."
        ),
        "cortinarius rubellus" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Павутинник красивіший. Смертельно отруйний (орелланін відмовляє нирки через 1-2 тижні). Плутають із лисичками або опеньками.",
            lookalikesEn = "Deadly Webcap. Contains orellanine causing delayed renal necrosis. Rusty orange-brown with web-like cortina."
        ),
        "amanita muscaria" to MycoSpeciesData(
            edibility = "inedible",
            propsUk = MycoProps(
                cap = "Яскраво-червона з білими бородавками",
                hymenophore = "Вільні білі пластинки",
                stem = "Біла з кільцем та бульбою",
                flesh = "Білий, під шкіркою жовтуватий",
                season = "Липень — Листопад",
                habitat = "Хвойні та березові ліси"
            ),
            propsEn = MycoProps(
                cap = "Scarlet red with white warty patches",
                hymenophore = "Free white gills",
                stem = "White with skirt ring and bulbous base",
                flesh = "White, yellowish under skin",
                season = "July — November",
                habitat = "Birch and pine woods"
            ),
            lookalikesUk = "Мухомор Цезаря (Amanita caesarea) — їстівний рідкісний гриб, має помаранчеві пластинки та ніжку без бородавок.",
            lookalikesEn = "Caesar's Mushroom — rare edible with orange-yellow gills and smooth stem."
        ),
        "russula emetica" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Сироїжка блювотна — отруйна, має пекучий гострий смак та яскраву червону шапинку.",
            lookalikesEn = "The Sickener — poisonous, sharp peppery taste, bright red cap."
        ),
        "amanita pantherina" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Мухомор пантерний — сильно отруйний! Плутають із їстівним мухомором сіро-рожевим (Amanita rubescens). У їстівного м'якуш на зломі червоніє, у пантерного — ні!",
            lookalikesEn = "Panther Cap — severe neurotoxicity! Easily confused with edible Blusher (Amanita rubescens). Blusher flesh turns pink/red on bruising; Panther cap remains white."
        ),
        "hypholoma fasciculare" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Несправжній опеньок сірчано-жовтий — ОТРУЙНИЙ. Відрізняється від справжнього опенька сірчано-зеленкуватими пластинками, відсутністю кільця та гірким смаком.",
            lookalikesEn = "Sulphur Tuft — poisonous. Distinguished from edible honey fungus by greenish-yellow gills, lack of distinct ring, and intensely bitter taste."
        ),
        "rubroboletus satanas" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Сатанинський гриб — шлунково-кишковий токсин. Попелясто-біла шапинка, криваво-червоні пори та синіючий м'якуш.",
            lookalikesEn = "Devil's Bolete — chalky white cap, blood-red pores, and blue-staining flesh with foul odor."
        ),
        "paxillus involutus" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Свинуха тонка — НЕБЕЗПЕЧНО! Доведено смертельну кумулятивну токсичність (руйнує еритроцити крові).",
            lookalikesEn = "Brown Roll-rim — TOXIC! Destroys red blood cells through cumulative autoimmune antibodies."
        ),
        "tylopilus felleus" to MycoSpeciesData(
            edibility = "inedible",
            lookalikesUk = "Жовчний гриб (гірчак) — головний двійник білого гриба! Не отруйний, але нестерпно гіркий. Має темну опуклу сітку на ніжці та брудно-рожеві пори.",
            lookalikesEn = "Bitter Bolete — prime lookalike of the Porcini. Not lethal, but intensely bitter. Coarse dark network on stem and pinkish pore mouths."
        ),
        "boletus edulis" to MycoSpeciesData(
            edibility = "edible",
            propsUk = MycoProps(
                cap = "Опукла коричнева 8-25 см",
                hymenophore = "Трубчастий білий -> оливково-жовтий",
                stem = "Товста клубнеподібна з білою сіточкою",
                flesh = "Білий, ароматний, колір НЕ змінює",
                season = "Червень — Листопад",
                habitat = "Дубові, соснові та ялинові ліси"
            ),
            propsEn = MycoProps(
                cap = "Convex brown 8-25 cm",
                hymenophore = "Pores white turning olive-yellow",
                stem = "Club-shaped with fine white netting",
                flesh = "White, unchanging, nutty smell",
                season = "June — November",
                habitat = "Oak, pine, and spruce woodlands"
            ),
            lookalikesUk = "Остерігайтеся жовчного гриба (Tylopilus felleus) з рожевими порами та гірким смаком.",
            lookalikesEn = "Beware of the Bitter Bolete (Tylopilus felleus) with pinkish pore layer and bitter taste."
        ),
        "cantharellus cibarius" to MycoSpeciesData(
            edibility = "edible",
            propsUk = MycoProps(
                cap = "Лійкоподібна жовто-помаранчева",
                hymenophore = "Складки (псевдопластинки), що переходять на ніжку",
                stem = "Зливається з шапинкою",
                flesh = "Білувато-жовтий, запах абрикосів",
                season = "Червень — Жовтень",
                habitat = "Хвойні та листяні ліси"
            ),
            propsEn = MycoProps(
                cap = "Funnel-shaped egg-yellow",
                hymenophore = "Forked gill-like ridges running down stem",
                stem = "Continuous with cap, firm",
                flesh = "Pale yellow, apricot scent",
                season = "June — October",
                habitat = "Coniferous and deciduous forests"
            ),
            lookalikesUk = "Несправжня лисичка (Hygrophoropsis aurantiaca) — яскравіша, з тонкими справжніми пластинками; Омфалот (Omphalotus olearius) — отруйний, росте великими пучками на пнях.",
            lookalikesEn = "False Chanterelle — thin true gills, darker orange; Jack-o'-Lantern (toxic) — clustered on dead hardwood."
        ),
        "leccinum aurantiacum" to MycoSpeciesData(
            edibility = "edible",
            propsUk = MycoProps(
                cap = "Яскраво-оранжева або цегляно-червона",
                hymenophore = "Дрібні білувато-сірі пори",
                stem = "Висока з темними лусочками",
                flesh = "Щільний, синіє або чорніє на зломі",
                season = "Червень — Жовтень",
                habitat = "Під осиками та березами"
            ),
            propsEn = MycoProps(
                cap = "Vivid orange to brick-red",
                hymenophore = "Fine whitish-gray pores",
                stem = "Tall with dark scales",
                flesh = "Firm, stains purplish-black",
                season = "June — October",
                habitat = "Associated with aspen and birch"
            )
        ),
        "suillus luteus" to MycoSpeciesData(edibility = "edible"),
        "suillus granulatus" to MycoSpeciesData(edibility = "edible"),
        "leccinum scabrum" to MycoSpeciesData(edibility = "edible"),
        "imleria badia" to MycoSpeciesData(edibility = "edible"),
        "armillaria mellea" to MycoSpeciesData(edibility = "edible"),
        "macrolepiota procera" to MycoSpeciesData(edibility = "edible"),
        "agaricus arvensis" to MycoSpeciesData(edibility = "edible"),
        "agaricus campestris" to MycoSpeciesData(edibility = "edible"),
        "coprinus comatus" to MycoSpeciesData(edibility = "edible"),
        "pleurotus ostreatus" to MycoSpeciesData(edibility = "edible"),
        "laetiporus sulphureus" to MycoSpeciesData(edibility = "edible"),
        "morchella esculenta" to MycoSpeciesData(edibility = "edible"),
        "lactarius deliciosus" to MycoSpeciesData(edibility = "edible"),
        "lactarius torminosus" to MycoSpeciesData(edibility = "edible"),
        "russula virescens" to MycoSpeciesData(edibility = "edible"),
        "fomes fomentarius" to MycoSpeciesData(edibility = "inedible"),
        "trametes versicolor" to MycoSpeciesData(edibility = "inedible"),
        "schizophyllum commune" to MycoSpeciesData(edibility = "inedible"),
        "cantharellus friesii" to MycoSpeciesData(edibility = "edible"),
        "craterellus cornucopioides" to MycoSpeciesData(edibility = "edible"),
        "craterellus tubaeformis" to MycoSpeciesData(edibility = "edible"),
        "hygrophoropsis aurantiaca" to MycoSpeciesData(edibility = "inedible"),
        "amanita caesarea" to MycoSpeciesData(edibility = "edible"),
        "amanita rubescens" to MycoSpeciesData(edibility = "edible"),
        "amanita gemmata" to MycoSpeciesData(edibility = "inedible"),
        "amanita citrina" to MycoSpeciesData(edibility = "inedible"),
        "amanita regalis" to MycoSpeciesData(edibility = "inedible"),
        "amanita strobiliformis" to MycoSpeciesData(edibility = "inedible"),
        "amanita excelsa" to MycoSpeciesData(edibility = "inedible"),
        "boletus aereus" to MycoSpeciesData(edibility = "edible"),
        "boletus reticulatus" to MycoSpeciesData(edibility = "edible"),
        "boletus pinophilus" to MycoSpeciesData(edibility = "edible"),
        "hypholoma lateritium" to MycoSpeciesData(edibility = "inedible")
    )

    @Volatile
    private var classesMap: Map<String, MycoClassInfo>? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (classesMap != null) return
        loadClassesInternal(context)
    }

    fun reloadClasses(context: Context) {
        appContext = context.applicationContext
        loadClassesInternal(context)
    }

    private fun loadClassesInternal(context: Context) {
        synchronized(this) {
            try {
                context.assets.open("classes.json").bufferedReader(Charsets.UTF_8).use { reader ->
                    classesMap = parseClassesJson(reader.readText())
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun parseClassesJson(jsonStr: String): Map<String, MycoClassInfo> {
        val map = HashMap<String, MycoClassInfo>(3000)
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val species = obj.optString("species").lowercase().trim()
                    .replace('_', ' ')
                    .replace(Regex("\\s+"), " ")
                val rawEdibility = obj.optString("edibility").lowercase().trim()
                val edibility = if (rawEdibility == "edible") "edible" else "inedible"
                val rawHymenium = obj.optString("hymenium").lowercase().trim()
                val hymenium = when (rawHymenium) {
                    "gills" -> "gills"
                    "tubes" -> "tubes"
                    else -> "other"
                }
                if (species.isNotEmpty()) {
                    map[species] = MycoClassInfo(edibility, hymenium)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun ensureClassesLoaded() {
        if (classesMap != null) return
        val ctx = appContext ?: return
        loadClassesInternal(ctx)
    }

    fun resolveMetadata(scientificName: String): MycoSpeciesData {
        val clean = scientificName.lowercase().trim()
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")

        // 1. Edibility & Hymenium from classes.json in assets (source of truth)
        ensureClassesLoaded()
        val map = classesMap
        var resolvedEdibility = "unknown"
        var resolvedHymenium = "other"

        if (map != null) {
            val exactMatch = map[clean]
            if (exactMatch != null) {
                resolvedEdibility = exactMatch.edibility
                resolvedHymenium = exactMatch.hymenium
            } else {
                val parts = clean.split(" ")
                if (parts.size >= 2) {
                    val binomial = "${parts[0]} ${parts[1]}"
                    val binMatch = map[binomial]
                    if (binMatch != null) {
                        resolvedEdibility = binMatch.edibility
                        resolvedHymenium = binMatch.hymenium
                    }
                }
            }
        }

        // 2. Curated detailed knowledge base (props and lookalikes)
        var curated: MycoSpeciesData? = null
        for ((key, data) in KNOWLEDGE) {
            if (clean == key || clean.startsWith("$key ")) {
                curated = data
                break
            }
        }

        if (curated != null) {
            val finalEdibility = if (resolvedEdibility != "unknown") {
                resolvedEdibility
            } else {
                if (curated.edibility == "edible") "edible" else "inedible"
            }
            return MycoSpeciesData(
                edibility = finalEdibility,
                hymenium = resolvedHymenium,
                propsUk = curated.propsUk,
                propsEn = curated.propsEn,
                lookalikesUk = curated.lookalikesUk,
                lookalikesEn = curated.lookalikesEn
            )
        }

        return MycoSpeciesData(
            edibility = resolvedEdibility,
            hymenium = resolvedHymenium
        )
    }

    fun getEdibilityLabel(edibility: String, lang: String): String {
        val isUk = lang == "uk"
        return when (edibility) {
            "edible" -> if (isUk) "🟢 Їстівний" else "🟢 Edible"
            "inedible", "toxic", "deadly", "cond-edible", "poisonous" -> if (isUk) "🔴 Неїстівний" else "🔴 Inedible"
            else -> if (isUk) "❓ Невідомо" else "❓ Unknown"
        }
    }

    fun getEdibilityColor(edibility: String): Int {
        return when (edibility) {
            "edible" -> 0xFF2ECC71.toInt()       // Green
            "inedible", "toxic", "deadly", "cond-edible", "poisonous" -> 0xFFE74C3C.toInt()  // Red
            else -> 0xFF3498DB.toInt()           // Blue
        }
    }

    fun getHymeniumIcon(hymenium: String): String {
        return when (hymenium.lowercase()) {
            "tubes" -> "🧽"
            "gills" -> "🍂"
            else -> "🍄"
        }
    }

    fun getHymeniumLabel(hymenium: String, lang: String): String {
        val isUk = lang == "uk"
        return when (hymenium.lowercase()) {
            "tubes" -> if (isUk) "🧽 Трубчастий" else "🧽 Tubes (Pores)"
            "gills" -> if (isUk) "🍂 Пластинчастий" else "🍂 Gills (Lamellae)"
            else -> if (isUk) "🍄 Інші (дощовики, їжовики тощо)" else "🍄 Other"
        }
    }
}
