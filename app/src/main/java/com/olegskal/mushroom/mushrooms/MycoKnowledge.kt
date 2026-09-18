package com.olegskal.mushroom.mushrooms

data class MycoProps(
    val cap: String = "",
    val hymenophore: String = "",
    val stem: String = "",
    val flesh: String = "",
    val season: String = "",
    val habitat: String = ""
)

data class MycoSpeciesData(
    val edibility: String, // "edible", "cond-edible", "toxic", "deadly", "inedible", "unknown"
    val hymenium: String,  // "tubes", "gills", "other"
    val lookalikesUk: String? = null,
    val lookalikesEn: String? = null,
    val propsUk: MycoProps? = null,
    val propsEn: MycoProps? = null
)

object MycoKnowledge {

    val KNOWLEDGE: Map<String, MycoSpeciesData> = mapOf(
        "amanita phalloides" to MycoSpeciesData(
            edibility = "deadly",
            hymenium = "gills",
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
            edibility = "deadly",
            hymenium = "gills",
            lookalikesUk = "Мухомор білий смердючий. Смертельно отруйний! Плутають із білими печерицями. Має неприємний запах, білі пластинки та вольву внизу ніжки.",
            lookalikesEn = "Destroying Angel. Lethally toxic! Confused with white champignon mushrooms. Note pure white gills, persistent ring, and basal volva cup."
        ),
        "galerina marginata" to MycoSpeciesData(
            edibility = "deadly",
            hymenium = "gills",
            lookalikesUk = "СМЕРТЕЛЬНИЙ ДВІЙНИК ОПЕНЬКІВ! Росте на пнях. На відміну від опенька, має коричневий споровий порошок, темнішу основу ніжки та борошнистий запах.",
            lookalikesEn = "DEADLY LOOKALIKE OF HONEY FUNGUS! Contains amatoxins. Note rusty-brown spore print, smooth silky-fibrillose stem, and mealy smell."
        ),
        "cortinarius rubellus" to MycoSpeciesData(
            edibility = "deadly",
            hymenium = "gills",
            lookalikesUk = "Павутинник красивіший. Смертельно отруйний (орелланін відмовляє нирки через 1-2 тижні). Плутають із лисичками або опеньками.",
            lookalikesEn = "Deadly Webcap. Contains orellanine causing delayed renal necrosis. Rusty orange-brown with web-like cortina."
        ),
        "amanita muscaria" to MycoSpeciesData(
            edibility = "toxic",
            hymenium = "gills",
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
            edibility = "toxic",
            hymenium = "gills",
            lookalikesUk = "Сироїжка блювотна — отруйна, має пекучий гострий смак та яскраву червону шапинку.",
            lookalikesEn = "The Sickener — poisonous, sharp peppery taste, bright red cap."
        ),
        "amanita pantherina" to MycoSpeciesData(
            edibility = "toxic",
            hymenium = "gills",
            lookalikesUk = "Мухомор пантерний — сильно отруйний! Плутають із їстівним мухомором сіро-рожевим (Amanita rubescens). У їстівного м'якуш на зломі червоніє, у пантерного — ні!",
            lookalikesEn = "Panther Cap — severe neurotoxicity! Easily confused with edible Blusher (Amanita rubescens). Blusher flesh turns pink/red on bruising; Panther cap remains white."
        ),
        "hypholoma fasciculare" to MycoSpeciesData(
            edibility = "toxic",
            hymenium = "gills",
            lookalikesUk = "Несправжній опеньок сірчано-жовтий — ОТРУЙНИЙ. Відрізняється від справжнього опенька сірчано-зеленкуватими пластинками, відсутністю кільця та гірким смаком.",
            lookalikesEn = "Sulphur Tuft — poisonous. Distinguished from edible honey fungus by greenish-yellow gills, lack of distinct ring, and intensely bitter taste."
        ),
        "rubroboletus satanas" to MycoSpeciesData(
            edibility = "toxic",
            hymenium = "tubes",
            lookalikesUk = "Сатанинський гриб — шлунково-кишковий токсин. Попелясто-біла шапинка, криваво-червоні пори та синіючий м'якуш.",
            lookalikesEn = "Devil's Bolete — chalky white cap, blood-red pores, and blue-staining flesh with foul odor."
        ),
        "paxillus involutus" to MycoSpeciesData(
            edibility = "toxic",
            hymenium = "gills",
            lookalikesUk = "Свинуха тонка — НЕБЕЗПЕЧНО! Доведено смертельну кумулятивну токсичність (руйнує еритроцити крові).",
            lookalikesEn = "Brown Roll-rim — TOXIC! Destroys red blood cells through cumulative autoimmune antibodies."
        ),
        "tylopilus felleus" to MycoSpeciesData(
            edibility = "inedible",
            hymenium = "tubes",
            lookalikesUk = "Жовчний гриб (гірчак) — головний двійник білого гриба! Не отруйний, але нестерпно гіркий. Має темну опуклу сітку на ніжці та брудно-рожеві пори.",
            lookalikesEn = "Bitter Bolete — prime lookalike of the Porcini. Not lethal, but intensely bitter. Coarse dark network on stem and pinkish pore mouths."
        ),
        "boletus edulis" to MycoSpeciesData(
            edibility = "edible",
            hymenium = "tubes",
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
            hymenium = "gills",
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
            hymenium = "tubes",
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
        "suillus luteus" to MycoSpeciesData(edibility = "edible", hymenium = "tubes"),
        "suillus granulatus" to MycoSpeciesData(edibility = "edible", hymenium = "tubes"),
        "leccinum scabrum" to MycoSpeciesData(edibility = "edible", hymenium = "tubes"),
        "imleria badia" to MycoSpeciesData(edibility = "edible", hymenium = "tubes"),
        "armillaria mellea" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "macrolepiota procera" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "agaricus arvensis" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "agaricus campestris" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "coprinus comatus" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "pleurotus ostreatus" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "laetiporus sulphureus" to MycoSpeciesData(edibility = "cond-edible", hymenium = "tubes"),
        "morchella esculenta" to MycoSpeciesData(edibility = "cond-edible", hymenium = "tubes"),
        "lactarius deliciosus" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "lactarius torminosus" to MycoSpeciesData(edibility = "cond-edible", hymenium = "gills"),
        "russula virescens" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "russula aeruginea" to MycoSpeciesData(edibility = "edible", hymenium = "gills"),
        "fomes fomentarius" to MycoSpeciesData(edibility = "inedible", hymenium = "tubes"),
        "trametes versicolor" to MycoSpeciesData(edibility = "inedible", hymenium = "tubes"),
        "schizophyllum commune" to MycoSpeciesData(edibility = "inedible", hymenium = "gills")
    )

    fun resolveMetadata(scientificName: String): MycoSpeciesData {
        val lower = scientificName.lowercase().trim()

        for ((key, data) in KNOWLEDGE) {
            if (lower == key || lower.startsWith("$key ")) {
                return data
            }
        }

        val genus = lower.split(" ").firstOrNull() ?: ""
        var edibility = "unknown"
        var hymenium = "gills"

        when (genus) {
            "boletus", "leccinum", "suillus", "imleria", "xerocomus", "neoboletus" -> {
                edibility = "edible"
                hymenium = "tubes"
            }
            "cantharellus", "craterellus", "hydnum" -> {
                edibility = "edible"
                hymenium = "gills"
            }
            "amanita" -> {
                edibility = if (lower.contains("rubescens") || lower.contains("caesarea")) "edible" else "toxic"
                hymenium = "gills"
            }
            "galerina", "cortinarius", "inocybe" -> {
                edibility = "deadly"
                hymenium = "gills"
            }
            "fomes", "trametes", "stereum", "ganoderma", "trichaptum", "daedaleopsis" -> {
                edibility = "inedible"
                hymenium = "tubes"
            }
            "lactarius", "lactifluus", "russula" -> {
                edibility = "cond-edible"
                hymenium = "gills"
            }
            "agaricus", "pleurotus", "macrolepiota", "coprinus" -> {
                edibility = "edible"
                hymenium = "gills"
            }
            "hypholoma", "omphalotus", "paxillus", "rubroboletus" -> {
                edibility = "toxic"
            }
        }

        return MycoSpeciesData(edibility = edibility, hymenium = hymenium)
    }

    fun getEdibilityLabel(edibility: String, lang: String): String {
        val isUk = lang == "uk"
        return when (edibility) {
            "edible" -> if (isUk) "🟢 Їстівний" else "🟢 Edible"
            "cond-edible" -> if (isUk) "🟡 Умовно-їстівний" else "🟡 Cond. Edible"
            "toxic" -> if (isUk) "🟠 Отруйний" else "🟠 Toxic"
            "deadly" -> if (isUk) "🔴 Смертельно отруйний" else "🔴 Deadly Toxic"
            "inedible" -> if (isUk) "⚪ Неїстівний" else "⚪ Inedible"
            else -> if (isUk) "❓ Потребує уваги" else "❓ Caution Advised"
        }
    }

    fun getEdibilityColor(edibility: String): Int {
        return when (edibility) {
            "edible" -> 0xFF2ECC71.toInt()       // Green
            "cond-edible" -> 0xFFF1C40F.toInt()  // Yellow
            "toxic" -> 0xFFE67E22.toInt()        // Orange
            "deadly" -> 0xFFE74C3C.toInt()       // Red
            "inedible" -> 0xFF95A5A6.toInt()     // Gray
            else -> 0xFF3498DB.toInt()           // Blue
        }
    }
}
