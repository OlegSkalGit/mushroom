package com.olegskal.mushroom.map

import android.content.Context
import android.graphics.Color
import com.olegskal.mushroom.storage.MushroomStorageManager
import com.olegskal.mushroom.util.AppLogger
import com.olegskal.mushroom.util.AppPrefs
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tag
import org.mapsforge.poi.android.storage.AndroidPoiPersistenceManagerFactory
import org.mapsforge.poi.storage.PoiCategoryFilter
import org.mapsforge.poi.storage.PoiCategoryManager
import org.mapsforge.poi.storage.PoiPersistenceManager
import org.mapsforge.poi.storage.PointOfInterest
import org.mapsforge.poi.storage.WhitelistPoiCategoryFilter
import java.io.File
import kotlin.math.*

data class PoiItem(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String,
    val categoryId: String,
    val categoryTitle: String,
    val icon: String = "📍",
    val color: Int = Color.parseColor("#10B981")
)

data class PoiCategoryDef(
    val id: String,
    val titleUk: String,
    val titleEn: String,
    val icon: String,
    val color: Int = Color.parseColor("#10B981"),
    val keywords: List<String> = emptyList()
)

object PoiManager {

    private const val TAG = "PoiManager"
    var appContext: Context? = null

    val DEFAULT_CATEGORIES = listOf(
        PoiCategoryDef("natural", "🌲 Природа, гори, печери, дерева", "🌲 Nature, peaks, caves & trees", "🌲",
            Color.parseColor("#10B981"),
            listOf("natural", "peak", "cave_entrance", "rock", "cliff", "tree", "wood", "forest", "scrub", "heath")),
        PoiCategoryDef("water", "💧 Джерела, питна вода, криниці", "💧 Springs & drinking water", "💧",
            Color.parseColor("#06B6D4"),
            listOf("drinking_water", "spring", "water_point", "well", "drinking water", "fountain")),
        PoiCategoryDef("shelter", "🏕️ Навіси, альтанки, кемпінги", "🏕️ Shelters & campsites", "🏕️",
            Color.parseColor("#D97706"),
            listOf("shelter", "picnic_site", "camp_site", "caravan_site")),
        PoiCategoryDef("forestry", "🌳 Лісництва, лісові кордони", "🌳 Forestry & ranger stations", "🌳",
            Color.parseColor("#059669"),
            listOf("forestry", "ranger_station", "forester")),
        PoiCategoryDef("food", "🍽️ Харчування, ресторани, кафе", "🍽️ Food, restaurants & cafes", "🍽️",
            Color.parseColor("#F97316"),
            listOf("food", "restaurant", "cafe", "fast_food", "bar", "pub", "biergarten", "cafes", "restaurants")),
        PoiCategoryDef("shop", "🛒 Магазини, супермаркети, ринки", "🛒 Shops & supermarkets", "🛒",
            Color.parseColor("#8B5CF6"),
            listOf("shop", "supermarket", "convenience", "bakery", "grocery", "mall", "kiosk", "shops", "supermarkets")),
        PoiCategoryDef("healthcare", "🏥 Охорона здоров'я, лікарні, аптеки", "🏥 Healthcare, hospitals & pharmacies", "🏥",
            Color.parseColor("#EF4444"),
            listOf("healthcare", "health", "hospital", "pharmacy", "clinic", "doctors", "dentist", "veterinary", "hospitals", "pharmacies", "health care")),
        PoiCategoryDef("fuel", "⛽️ Заправки (АЗС)", "⛽️ Gas & petrol stations", "⛽️",
            Color.parseColor("#F59E0B"),
            listOf("fuel", "fuel stations", "gas_station", "petrol")),
        PoiCategoryDef("charging", "🔌 Зарядки електромобілів", "🔌 EV charging stations", "🔌",
            Color.parseColor("#10B981"),
            listOf("charging_station", "charging", "electric vehicle", "fuel:electricity")),
        PoiCategoryDef("highway", "🛣️ Дорожня інфраструктура, паркінги", "🛣️ Highway & parking", "🛣️",
            Color.parseColor("#6B7280"),
            listOf("highway", "parking", "car_park", "rest_area", "services", "motorway_junction")),
        PoiCategoryDef("tourism", "🏰 Туризм, атракції, оглядові місця", "🏰 Tourism & viewpoints", "🏰",
            Color.parseColor("#EC4899"),
            listOf("tourism", "viewpoint", "attraction", "theme_park", "zoo", "museum", "information", "viewpoints", "attractions")),
        PoiCategoryDef("historic", "🏛️ Історія, замки, пам'ятники, меморіали", "🏛️ Historic sites & monuments", "🏛️",
            Color.parseColor("#92400E"),
            listOf("historic", "castle", "monument", "memorial", "archaeological_site", "ruins", "fort", "monuments", "castles")),
        PoiCategoryDef("public_transport", "🚏 Громадський транспорт, зупинки, станції", "🚏 Public transport & stations", "🚏",
            Color.parseColor("#3B82F6"),
            listOf("public_transport", "transport", "bus_stop", "station", "subway_entrance", "platform", "tram_stop", "halt", "bus stops")),
        PoiCategoryDef("finance", "🏦 Фінанси, банки, банкомати", "🏦 Finance, banks & ATMs", "🏦",
            Color.parseColor("#14B8A6"),
            listOf("finance", "bank", "atm", "money", "bureau_de_change", "banks", "atms")),
        PoiCategoryDef("accommodation", "🏨 Проживання, готелі, хостели", "🏨 Accommodation & hotels", "🏨",
            Color.parseColor("#6366F1"),
            listOf("accommodation", "hotel", "motel", "hostel", "guest_house", "chalet", "alpine_hut", "hotels")),
        PoiCategoryDef("amenity", "🚻 Зручності, туалети, пошта, лавки", "🚻 Amenities & public services", "🚻",
            Color.parseColor("#64748B"),
            listOf("amenity", "toilets", "waste_basket", "bench", "post_office", "telephone", "drinking_water", "amenities")),
        PoiCategoryDef("emergency", "🚨 Екстрені служби, рятувальники, поліція", "🚨 Emergency, rescue & police", "🚨",
            Color.parseColor("#DC2626"),
            listOf("emergency", "police", "fire_station", "ambulance_station", "mountain_rescue")),
        PoiCategoryDef("administrative", "🏛️ Адміністрація, мерії, установи", "🏛️ Administrative buildings", "🏛️",
            Color.parseColor("#475569"),
            listOf("administrative", "townhall", "courthouse", "embassy", "government")),
        PoiCategoryDef("education", "🎓 Освіта, школи, дитсадки", "🎓 Education & schools", "🎓",
            Color.parseColor("#0284C7"),
            listOf("education", "school", "university", "college", "kindergarten", "library")),
        PoiCategoryDef("entertainment", "🎭 Розваги, театри, кінотеатри", "🎭 Entertainment & culture", "🎭",
            Color.parseColor("#D946EF"),
            listOf("entertainment", "cinema", "theatre", "nightclub", "arts_centre")),
        PoiCategoryDef("leisure", "🎯 Дозвілля, парки, майданчики", "🎯 Leisure & recreation", "🎯",
            Color.parseColor("#22C55E"),
            listOf("leisure", "park", "playground", "recreation_ground", "garden", "parks")),
        PoiCategoryDef("sport", "🏅 Спорт, стадіони, майданчики", "🏅 Sport & stadiums", "🏅",
            Color.parseColor("#EAB308"),
            listOf("sport", "stadium", "sports_centre", "pitch", "swimming_pool")),
        PoiCategoryDef("man_made", "🗼 Споруди, вежі, щогли, маяки", "🗼 Man-made structures & towers", "🗼",
            Color.parseColor("#78716C"),
            listOf("man_made", "tower", "water_tower", "lighthouse", "windmill", "surveillance")),
        PoiCategoryDef("power", "💡 Енергетика, підстанції", "💡 Power & energy", "💡",
            Color.parseColor("#EAB308"),
            listOf("power", "substation", "generator", "plant")),
        PoiCategoryDef("waterway", "🌊 Водойми, водоспади, шлюзи, дамби", "🌊 Waterways, waterfalls & dams", "🌊",
            Color.parseColor("#0284C7"),
            listOf("waterway", "waterfall", "dam", "lock", "weir", "dock")),
        PoiCategoryDef("landuse", "🏞️ Землекористування, кладовища", "🏞️ Landuse & cemeteries", "🏞️",
            Color.parseColor("#84CC16"),
            listOf("landuse", "cemetery", "allotments", "quarry")),
        PoiCategoryDef("barrier", "🚧 Перешкоди, шлагбауми, ворота", "🚧 Barriers, gates & lift gates", "🚧",
            Color.parseColor("#71717A"),
            listOf("barrier", "gate", "lift_gate", "toll_booth", "bollard")),
        PoiCategoryDef("other", "📍 Інші локації", "📍 Other locations", "📍",
            Color.parseColor("#64748B"),
            listOf("other", "misc", "place", "office", "craft", "point"))
    )

    var isPoiEnabled: Boolean
        get() = activeCategories.isNotEmpty() && MushroomStorageManager.isPoiEnabled()
        set(value) = MushroomStorageManager.setPoiEnabled(value)

    private var activeCategories: MutableSet<String> = mutableSetOf()
    @Volatile private var openManagers: List<PoiPersistenceManager> = emptyList()
    private var lastLoadedFiles = emptyList<String>()

    fun init(context: Context? = null) {
        if (context != null) {
            appContext = context.applicationContext
        }
        val ctx = appContext
        if (ctx != null && !AppPrefs.isPoiDefaultInitialized(ctx)) {
            // За замовчуванням не обрано жодної групи точок
            activeCategories.clear()
            MushroomStorageManager.savePoiCategories(emptySet())
            AppPrefs.setPoiDefaultInitialized(ctx)
        } else {
            val file = MushroomStorageManager.poiConfigFile
            activeCategories.clear()
            if (file.exists()) {
                activeCategories.addAll(MushroomStorageManager.loadPoiCategories())
            }
        }
        refreshPoiFiles()
    }

    fun getActiveCategories(): Set<String> = activeCategories

    fun setCategoryActive(id: String, active: Boolean) {
        if (active) {
            activeCategories.add(id)
        } else {
            activeCategories.remove(id)
        }
        MushroomStorageManager.savePoiCategories(activeCategories)
    }

    fun setAllCategoriesActive(active: Boolean) {
        activeCategories.clear()
        if (active) {
            activeCategories.addAll(DEFAULT_CATEGORIES.map { it.id })
        }
        MushroomStorageManager.savePoiCategories(activeCategories)
    }

    fun isCategoryActive(id: String): Boolean = activeCategories.contains(id)

    fun setActiveCategories(categories: Set<String>) {
        activeCategories.clear()
        activeCategories.addAll(categories)
        isPoiEnabled = categories.isNotEmpty()
        MushroomStorageManager.savePoiCategories(activeCategories)
    }

    fun getCategoryDef(id: String): PoiCategoryDef? {
        return DEFAULT_CATEGORIES.firstOrNull { it.id == id }
    }

    @Synchronized
    fun refreshPoiFiles() {
        try {
            val poiFiles = MushroomStorageManager.poiDir.listFiles { _, name -> name.endsWith(".poi") } ?: emptyArray()
            val currentFilePaths = poiFiles.filter { it.length() > 1024L }.map { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }.sorted()
            if (currentFilePaths == lastLoadedFiles && openManagers.isNotEmpty()) {
                return
            }

            val newManagers = mutableListOf<PoiPersistenceManager>()
            for (f in poiFiles) {
                if (!f.exists() || f.length() < 1024L) continue
                try {
                    // Оптимізація SQLite: індекс за категоріями для миттєвої фільтрації
                    try {
                        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                            f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cat ON poi_category_map(category)")
                        db.close()
                    } catch (_: Throwable) {}

                    val pm = AndroidPoiPersistenceManagerFactory.getPoiPersistenceManager(f.absolutePath)
                    if (pm != null && pm.isValidDataBase) {
                        newManagers.add(pm)
                    }
                } catch (t: Throwable) {
                    AppLogger.log(TAG, "refreshPoiFiles", false, "Error opening POI ${f.name}: ${t.message}")
                }
            }

            val oldManagers = openManagers
            openManagers = newManagers
            lastLoadedFiles = currentFilePaths

            for (m in oldManagers) {
                try { m.close() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            AppLogger.log(TAG, "refreshPoiFiles", false, "Error refreshing POI files: ${t.message}")
        }
    }

    /**
     * Точне вилучення назви POI з тегів бази даних без викривлень.
     * Якщо власна назва відсутня, повертає точний тип об'єкта (наприклад "АЗС", "Аптека", "Джерело"),
     * а не довгу назву загальної категорії.
     */
    fun extractPoiName(tags: Collection<Tag>, isUk: Boolean, categoryDef: PoiCategoryDef): String {
        val nameUk = tags.firstOrNull { it.key.equals("name:uk", ignoreCase = true) }?.value?.trim()
        val nameEn = tags.firstOrNull { it.key.equals("name:en", ignoreCase = true) }?.value?.trim()
        val nameDef = tags.firstOrNull { it.key.equals("name", ignoreCase = true) }?.value?.trim()
        val intName = tags.firstOrNull { it.key.equals("int_name", ignoreCase = true) }?.value?.trim()
        val officialName = tags.firstOrNull { it.key.equals("official_name", ignoreCase = true) }?.value?.trim()
        val altName = tags.firstOrNull { it.key.equals("alt_name", ignoreCase = true) }?.value?.trim()

        val explicitName = if (isUk) {
            nameUk?.ifEmpty { null } ?: nameDef?.ifEmpty { null } ?: nameEn?.ifEmpty { null } ?: intName ?: officialName ?: altName
        } else {
            nameEn?.ifEmpty { null } ?: nameDef?.ifEmpty { null } ?: nameUk?.ifEmpty { null } ?: intName ?: officialName ?: altName
        }
        if (!explicitName.isNullOrBlank()) return explicitName

        val brand = tags.firstOrNull { it.key.equals("brand", ignoreCase = true) }?.value?.trim()
        val operator = tags.firstOrNull { it.key.equals("operator", ignoreCase = true) }?.value?.trim()
        if (!brand.isNullOrBlank()) return brand
        if (!operator.isNullOrBlank()) return operator

        val tagMap = tags.associate { it.key.lowercase() to it.value.lowercase() }
        val specificType = when {
            tagMap["amenity"] == "pharmacy" || tagMap["healthcare"] == "pharmacy" -> if (isUk) "Аптека" else "Pharmacy"
            tagMap["amenity"] == "hospital" || tagMap["healthcare"] == "hospital" -> if (isUk) "Лікарня" else "Hospital"
            tagMap["amenity"] == "clinic" || tagMap["healthcare"] == "clinic" -> if (isUk) "Клініка" else "Clinic"
            tagMap["amenity"] == "doctors" -> if (isUk) "Лікар" else "Doctor"
            tagMap["amenity"] == "dentist" -> if (isUk) "Стоматолог" else "Dentist"
            tagMap["amenity"] == "fuel" -> if (isUk) "АЗС" else "Gas Station"
            tagMap["amenity"] == "charging_station" || tagMap["fuel:electricity"] == "yes" -> if (isUk) "Зарядна станція" else "EV Charging"
            tagMap["amenity"] == "cafe" -> if (isUk) "Кафе" else "Cafe"
            tagMap["amenity"] == "restaurant" -> if (isUk) "Ресторан" else "Restaurant"
            tagMap["amenity"] == "fast_food" -> if (isUk) "Фастфуд" else "Fast Food"
            tagMap["amenity"] == "bar" -> if (isUk) "Бар" else "Bar"
            tagMap["amenity"] == "pub" -> if (isUk) "Паб" else "Pub"
            tagMap["amenity"] == "drinking_water" || tagMap["natural"] == "spring" -> if (isUk) "Джерело" else "Spring"
            tagMap["amenity"] == "shelter" -> if (isUk) "Навіс" else "Shelter"
            tagMap["amenity"] == "bank" -> if (isUk) "Банк" else "Bank"
            tagMap["amenity"] == "atm" -> if (isUk) "Банкомат" else "ATM"
            tagMap["highway"] == "bus_stop" || tagMap["public_transport"] == "platform" -> if (isUk) "Зупинка" else "Bus Stop"
            tagMap["natural"] == "peak" -> if (isUk) "Вершина" else "Peak"
            tagMap["natural"] == "cave_entrance" -> if (isUk) "Печера" else "Cave"
            tagMap["natural"] == "waterfall" || tagMap["waterway"] == "waterfall" -> if (isUk) "Водоспад" else "Waterfall"
            tagMap["tourism"] == "viewpoint" -> if (isUk) "Оглядовий майданчик" else "Viewpoint"
            tagMap["tourism"] == "attraction" -> if (isUk) "Атракція" else "Attraction"
            tagMap["tourism"] == "hotel" -> if (isUk) "Готель" else "Hotel"
            tagMap["tourism"] == "hostel" -> if (isUk) "Хостел" else "Hostel"
            tagMap["tourism"] == "camp_site" -> if (isUk) "Кемпінг" else "Campsite"
            tagMap["tourism"] == "picnic_site" -> if (isUk) "Місце для пікніка" else "Picnic Site"
            tagMap["shop"] == "supermarket" -> if (isUk) "Супермаркет" else "Supermarket"
            tagMap["shop"] == "convenience" || tagMap["shop"] == "grocery" -> if (isUk) "Продукти" else "Grocery"
            tagMap["shop"] == "bakery" -> if (isUk) "Пекарня" else "Bakery"
            tagMap["historic"] == "monument" -> if (isUk) "Пам'ятник" else "Monument"
            tagMap["historic"] == "memorial" -> if (isUk) "Меморіал" else "Memorial"
            tagMap["historic"] == "castle" -> if (isUk) "Замок" else "Castle"
            tagMap["historic"] == "ruins" -> if (isUk) "Руїни" else "Ruins"
            else -> null
        }
        if (specificType != null) return specificType

        return if (isUk) categoryDef.titleUk.replace(Regex("""^[^\wа-яА-ЯіІїЇєЄґҐ]+\s*"""), "").split(",").first().trim()
               else categoryDef.titleEn.replace(Regex("""^[^\w]+\s*"""), "").split(",").first().trim()
    }

    private val CATEGORY_MAP: Map<String, PoiCategoryDef> by lazy {
        DEFAULT_CATEGORIES.associateBy { it.id }
    }

    /**
     * Точне зіставлення OSM-тегів із категорією без помилкових спрацьовувань.
     */
    fun resolveCategoryId(tagMap: Map<String, String>): String? {
        val amenity = tagMap["amenity"]
        val natural = tagMap["natural"]
        val tourism = tagMap["tourism"]
        val highway = tagMap["highway"]
        val shop = tagMap["shop"]
        val historic = tagMap["historic"]
        val leisure = tagMap["leisure"]
        val sport = tagMap["sport"]
        val emergency = tagMap["emergency"]
        val waterway = tagMap["waterway"]
        val power = tagMap["power"]
        val manMade = tagMap["man_made"]
        val barrier = tagMap["barrier"]
        val healthcare = tagMap["healthcare"]
        val publicTransport = tagMap["public_transport"]
        val railway = tagMap["railway"]
        val office = tagMap["office"]

        // 1. Water
        if (amenity == "drinking_water" || amenity == "fountain" || amenity == "water_point" ||
            natural == "spring" || manMade == "water_well" || manMade == "water_tap") {
            return "water"
        }

        // 2. Fuel & EV Charging
        if (amenity == "charging_station" || (tagMap["fuel:electricity"] == "yes" && amenity != "fuel")) return "charging"
        if (amenity == "fuel") return "fuel"

        // 3. Healthcare
        if (healthcare != null || amenity in listOf("pharmacy", "hospital", "clinic", "doctors", "dentist", "veterinary")) {
            return "healthcare"
        }

        // 4. Food
        if (amenity in listOf("restaurant", "cafe", "fast_food", "bar", "pub", "biergarten", "food_court")) {
            return "food"
        }

        // 5. Shelter / Camping
        if (amenity == "shelter" || amenity == "bbq" ||
            tourism in listOf("picnic_site", "camp_site", "caravan_site")) {
            return "shelter"
        }

        // 6. Accommodation
        if (tourism in listOf("hotel", "hostel", "motel", "guest_house", "chalet", "alpine_hut", "apartment")) {
            return "accommodation"
        }

        // 7. Tourism & Viewpoints
        if (tourism in listOf("viewpoint", "attraction", "museum", "zoo", "theme_park", "information", "artwork")) {
            return "tourism"
        }

        // 8. Public Transport
        if (highway == "bus_stop" || railway in listOf("station", "halt", "tram_stop") ||
            amenity == "bus_station" || publicTransport in listOf("platform", "stop_position")) {
            return "public_transport"
        }

        // 9. Finance
        if (amenity in listOf("bank", "atm", "bureau_de_change")) return "finance"

        // 10. Emergency
        if (emergency != null || amenity in listOf("police", "fire_station", "ambulance_station")) {
            return "emergency"
        }

        // 11. Forestry
        if (tagMap.containsKey("forestry") || amenity == "ranger_station" || office == "forestry") {
            return "forestry"
        }

        // 12. Shops
        if (shop != null) return "shop"

        // 13. Historic
        if (historic != null) return "historic"

        // 14. Nature
        if (natural in listOf("peak", "cave_entrance", "rock", "cliff", "tree", "wood", "forest", "scrub")) {
            return "natural"
        }

        // 15. Waterway
        if (waterway != null || natural == "water") return "waterway"

        // 16. Education
        if (amenity in listOf("school", "kindergarten", "university", "college", "library")) {
            return "education"
        }

        // 17. Administrative
        if (amenity in listOf("townhall", "courthouse") || office == "government") {
            return "administrative"
        }

        // 18. Entertainment
        if (amenity in listOf("cinema", "theatre", "nightclub", "arts_centre")) {
            return "entertainment"
        }

        // 19. Leisure
        if (leisure in listOf("park", "playground", "garden", "recreation_ground")) {
            return "leisure"
        }

        // 20. Sport
        if (sport != null || leisure in listOf("sports_centre", "stadium", "pitch", "swimming_pool")) {
            return "sport"
        }

        // 21. Highway / Parking
        if (amenity == "parking" || highway in listOf("services", "rest_area")) {
            return "highway"
        }

        // 22. Power
        if (power != null) return "power"

        // 23. Man-made
        if (manMade in listOf("tower", "water_tower", "lighthouse", "windmill")) {
            return "man_made"
        }

        // 24. Barrier
        if (barrier != null) return "barrier"

        val place = tagMap["place"]
        if (place != null) return "administrative"

        val craft = tagMap["craft"]
        if (craft != null) return "shop"

        if (office != null) return "administrative"

        // 25. Generic Amenity (toilets, bench, post office etc.)
        if (amenity != null) return "amenity"

        return "other"
    }

    private val MAPSFORGE_CATEGORY_IDS = mapOf(
        "charging" to listOf(28),
        "fuel" to listOf(24),
        "healthcare" to listOf(34, 35, 37, 38, 40, 41, 39),
        "food" to listOf(0, 1, 2, 5, 6, 7, 8, 10, 9),
        "water" to listOf(3, 206, 368, 383, 191),
        "shelter" to listOf(65, 366, 367, 376, 4),
        "forestry" to listOf(128, 94),
        "shop" to listOf(304),
        "tourism" to listOf(380, 364, 365, 373, 375, 378, 379),
        "historic" to listOf(158, 145, 146, 148, 150, 151, 152, 153),
        "public_transport" to listOf(221, 20, 141, 218, 219, 220),
        "finance" to listOf(30, 31, 32, 33),
        "accommodation" to listOf(363, 369, 370, 371, 372, 374),
        "amenity" to listOf(70, 45, 60, 61, 66, 67, 63),
        "emergency" to listOf(137, 54, 59, 129, 130, 133, 134),
        "administrative" to listOf(208, 209, 68, 53),
        "education" to listOf(11, 12, 13, 14, 15, 16),
        "entertainment" to listOf(49, 42, 43, 46, 48),
        "leisure" to listOf(177, 160, 162, 168, 170),
        "sport" to listOf(362, 169, 172, 173, 174),
        "man_made" to listOf(194, 181, 186, 189, 192),
        "power" to listOf(187, 190, 193),
        "waterway" to listOf(384, 381, 382),
        "landuse" to listOf(55),
        "barrier" to listOf(78),
        "highway" to listOf(144, 25, 26, 142),
        "natural" to listOf(207, 202, 205)
    )

    private fun buildCategoryFilter(catMgr: PoiCategoryManager): PoiCategoryFilter? {
        if (activeCategories.contains("other") || activeCategories.size >= DEFAULT_CATEGORIES.size) {
            return null
        }
        val filter = WhitelistPoiCategoryFilter()
        var addedCount = 0
        for (catId in activeCategories) {
            val ids = MAPSFORGE_CATEGORY_IDS[catId] ?: continue
            for (id in ids) {
                try {
                    val c = catMgr.getPoiCategoryByID(id)
                    if (c != null) {
                        filter.addCategory(c)
                        addedCount++
                    }
                } catch (_: Exception) {}
            }
        }
        return if (addedCount > 0) filter else null
    }

    fun findPoisInBbox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, maxResults: Int = 50000): List<PoiItem> {
        if (!isPoiEnabled || activeCategories.isEmpty()) return emptyList()
        refreshPoiFiles()
        val managers = openManagers
        if (managers.isEmpty()) return emptyList()

        val results = mutableListOf<PoiItem>()
        val bbox = BoundingBox(minLat, minLon, maxLat, maxLon)
        val isUk = appContext?.let { AppPrefs.isUk(it) } ?: true

        for (pm in managers) {
            try {
                val filter = try { buildCategoryFilter(pm.categoryManager) } catch (_: Throwable) { null }
                val pois: Collection<PointOfInterest> = pm.findInRect(bbox, filter, null, null, maxResults, false)
                for (p in pois) {
                    val tags = p.tags
                    val tagMap = HashMap<String, String>(tags.size)
                    for (t in tags) {
                        tagMap[t.key.lowercase()] = t.value.lowercase()
                    }

                    var matchedDef: PoiCategoryDef? = null
                    val resolvedCatId = resolveCategoryId(tagMap)
                    if (resolvedCatId != null && activeCategories.contains(resolvedCatId)) {
                        matchedDef = CATEGORY_MAP[resolvedCatId]
                    }

                    // Резервний пошук за ключовими словами для нестандартних тегів
                    if (matchedDef == null) {
                        for (catDef in DEFAULT_CATEGORIES) {
                            if (!activeCategories.contains(catDef.id)) continue
                            if (catDef.keywords.any { kw -> tagMap.values.any { it.contains(kw) } || tagMap.keys.any { it.contains(kw) } }) {
                                matchedDef = catDef
                                break
                            }
                        }
                    }

                    // Якщо категорію "Інші" увімкнено — показуємо точку з універсальною іконкою 📍
                    if (matchedDef == null && activeCategories.contains("other")) {
                        matchedDef = CATEGORY_MAP["other"]
                    }

                    if (matchedDef != null) {
                        val name = extractPoiName(tags, isUk, matchedDef)
                        val catDisplay = if (isUk) matchedDef.titleUk else matchedDef.titleEn
                        results.add(PoiItem(p.id, p.latitude, p.longitude, name, matchedDef.id, catDisplay, matchedDef.icon, matchedDef.color))
                        if (results.size >= maxResults) break
                    }
                }
            } catch (t: Throwable) {
                AppLogger.log(TAG, "findPoisInBbox", false, "Error querying POI: ${t.message}")
            }
            if (results.size >= maxResults) break
        }
        return results
    }

    fun findNearestPoi(lat: Double, lon: Double, maxDistMeters: Double = 50.0): PoiItem? {
        val deltaDeg = (maxDistMeters / 111320.0) * 1.5
        val candidates = findPoisInBbox(lat - deltaDeg, lat + deltaDeg, lon - deltaDeg, lon + deltaDeg, maxResults = 50)
        var nearest: PoiItem? = null
        var minDist = maxDistMeters

        for (item in candidates) {
            val d = com.olegskal.mushroom.math.GeoMath.calculateDistance(lat, lon, item.lat, item.lon).toDouble()
            if (d < minDist) {
                minDist = d
                nearest = item
            }
        }
        return nearest
    }
}
