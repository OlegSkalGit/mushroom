package com.olegskal.mushroom.address

data class PoiTagDef(
    val tag: String,
    val descEn: String,
    val groupCategory: String
)

object PoiTagCatalog {
    val TAGS = listOf(
        // Охорона здоров'я
        PoiTagDef("pharmacy", "Pharmacy", "healthcare"),
        PoiTagDef("hospital", "Hospital", "healthcare"),
        PoiTagDef("clinic", "Clinic", "healthcare"),
        PoiTagDef("dentist", "Dentist", "healthcare"),
        PoiTagDef("doctors", "Doctor's Office", "healthcare"),
        PoiTagDef("veterinary", "Veterinary Clinic", "healthcare"),
        PoiTagDef("optician", "Optician", "healthcare"),
        PoiTagDef("laboratory", "Medical Laboratory", "healthcare"),

        // Авто, паливо, зарядки
        PoiTagDef("fuel", "Fuel Station", "fuel"),
        PoiTagDef("charging_station", "Charging Station", "charging"),
        PoiTagDef("parking", "Parking Lot", "highway"),
        PoiTagDef("bicycle_parking", "Bicycle Parking", "highway"),
        PoiTagDef("car_wash", "Car Wash", "amenity"),
        PoiTagDef("car_repair", "Auto Repair Shop", "shop"),
        PoiTagDef("car_parts", "Auto Parts Store", "shop"),

        // Їжа та напої
        PoiTagDef("cafe", "Cafe", "food"),
        PoiTagDef("restaurant", "Restaurant", "food"),
        PoiTagDef("fast_food", "Fast Food", "food"),
        PoiTagDef("bar", "Bar", "food"),
        PoiTagDef("pub", "Pub", "food"),
        PoiTagDef("biergarten", "Beer Garden", "food"),
        PoiTagDef("food_court", "Food Court", "food"),
        PoiTagDef("bakery", "Bakery", "shop"),

        // Магазини
        PoiTagDef("supermarket", "Supermarket", "shop"),
        PoiTagDef("convenience", "Grocery Store", "shop"),
        PoiTagDef("grocery", "Grocery Store", "shop"),
        PoiTagDef("butcher", "Butcher Shop", "shop"),
        PoiTagDef("clothes", "Clothing Store", "shop"),
        PoiTagDef("shoes", "Shoe Store", "shop"),
        PoiTagDef("hardware", "Hardware Store", "shop"),
        PoiTagDef("doityourself", "Building Materials Store", "shop"),
        PoiTagDef("furniture", "Furniture Store", "shop"),
        PoiTagDef("electronics", "Electronics Store", "shop"),
        PoiTagDef("mobile_phone", "Mobile Phone Store", "shop"),
        PoiTagDef("pet", "Pet Store", "shop"),
        PoiTagDef("florist", "Flower Shop", "shop"),
        PoiTagDef("chemist", "Household Goods & Cosmetics", "shop"),
        PoiTagDef("beauty", "Beauty Salon", "shop"),
        PoiTagDef("hairdresser", "Barbershop / Hairdresser", "shop"),
        PoiTagDef("kiosk", "Kiosk", "shop"),
        PoiTagDef("mall", "Shopping Mall", "shop"),
        PoiTagDef("pawnbroker", "Pawnshop", "shop"),
        PoiTagDef("alcohol", "Liquor Store", "shop"),
        PoiTagDef("confectionery", "Confectionery", "shop"),
        PoiTagDef("optometrist", "Optician", "shop"),

        // Природа та вода
        PoiTagDef("spring", "Water Spring", "water"),
        PoiTagDef("drinking_water", "Drinking Water", "water"),
        PoiTagDef("water_well", "Water Well", "water"),
        PoiTagDef("fountain", "Water Fountain", "water"),
        PoiTagDef("waterfall", "Waterfall", "waterway"),
        PoiTagDef("peak", "Mountain Peak", "natural"),
        PoiTagDef("saddle", "Mountain Saddle", "natural"),
        PoiTagDef("cave_entrance", "Cave Entrance", "natural"),
        PoiTagDef("rock", "Rock", "natural"),
        PoiTagDef("cliff", "Cliff", "natural"),
        PoiTagDef("viewpoint", "Scenic Viewpoint", "tourism"),
        PoiTagDef("beach", "Beach", "natural"),
        PoiTagDef("tree", "Notable Tree", "natural"),
        PoiTagDef("wood", "Woodland", "natural"),

        // Туризм та проживання
        PoiTagDef("hotel", "Hotel", "accommodation"),
        PoiTagDef("hostel", "Hostel", "accommodation"),
        PoiTagDef("motel", "Motel", "accommodation"),
        PoiTagDef("guest_house", "Guest House", "accommodation"),
        PoiTagDef("chalet", "Chalet", "accommodation"),
        PoiTagDef("alpine_hut", "Mountain Shelter", "accommodation"),
        PoiTagDef("camp_site", "Campsite", "shelter"),
        PoiTagDef("caravan_site", "Caravan Site", "shelter"),
        PoiTagDef("picnic_site", "Picnic Site", "shelter"),
        PoiTagDef("shelter", "Tourist Shelter", "shelter"),
        PoiTagDef("museum", "Museum", "tourism"),
        PoiTagDef("attraction", "Tourist Attraction", "tourism"),
        PoiTagDef("theme_park", "Theme Park", "tourism"),
        PoiTagDef("zoo", "Zoo", "tourism"),
        PoiTagDef("information", "Tourist Information", "tourism"),
        PoiTagDef("monument", "Monument", "historic"),
        PoiTagDef("memorial", "Memorial", "historic"),
        PoiTagDef("castle", "Castle", "historic"),
        PoiTagDef("ruins", "Historic Ruins", "historic"),
        PoiTagDef("archaeological_site", "Archaeological Site", "historic"),
        PoiTagDef("wayside_cross", "Wayside Cross", "historic"),
        PoiTagDef("place_of_worship", "Church / Temple", "historic"),

        // Транспорт
        PoiTagDef("bus_stop", "Bus Stop", "public_transport"),
        PoiTagDef("tram_stop", "Tram Stop", "public_transport"),
        PoiTagDef("platform", "Transit Platform", "public_transport"),
        PoiTagDef("station", "Train Station", "public_transport"),
        PoiTagDef("halt", "Train Halt", "public_transport"),
        PoiTagDef("subway_entrance", "Metro Entrance", "public_transport"),
        PoiTagDef("bus_station", "Bus Station", "public_transport"),

        // Фінанси та зв'язок
        PoiTagDef("bank", "Bank", "finance"),
        PoiTagDef("atm", "ATM", "finance"),
        PoiTagDef("bureau_de_change", "Currency Exchange", "finance"),
        PoiTagDef("post_office", "Post Office", "amenity"),
        PoiTagDef("post_box", "Post Box", "amenity"),

        // Освіта та адміністрація
        PoiTagDef("school", "School", "education"),
        PoiTagDef("kindergarten", "Kindergarten", "education"),
        PoiTagDef("university", "University", "education"),
        PoiTagDef("college", "College", "education"),
        PoiTagDef("library", "Library", "education"),
        PoiTagDef("townhall", "Town Hall", "administrative"),
        PoiTagDef("courthouse", "Courthouse", "administrative"),
        PoiTagDef("police", "Police Station", "emergency"),
        PoiTagDef("fire_station", "Fire Station", "emergency"),
        PoiTagDef("ambulance_station", "Ambulance Station", "emergency"),

        // Спорт та дозвілля
        PoiTagDef("park", "City Park", "leisure"),
        PoiTagDef("playground", "Playground", "leisure"),
        PoiTagDef("pitch", "Sports Pitch", "sport"),
        PoiTagDef("stadium", "Stadium", "sport"),
        PoiTagDef("sports_centre", "Fitness / Sports Centre", "sport"),
        PoiTagDef("swimming_pool", "Swimming Pool", "sport"),
        PoiTagDef("cinema", "Cinema", "entertainment"),
        PoiTagDef("theatre", "Theatre", "entertainment"),
        PoiTagDef("nightclub", "Nightclub", "entertainment"),

        // Зручності
        PoiTagDef("toilets", "Public Toilets", "amenity"),
        PoiTagDef("waste_basket", "Waste Basket", "amenity"),
        PoiTagDef("bench", "Bench", "amenity"),

        // Споруди
        PoiTagDef("tower", "Tower", "man_made"),
        PoiTagDef("water_tower", "Water Tower", "man_made"),
        PoiTagDef("lighthouse", "Lighthouse", "man_made"),
        PoiTagDef("windmill", "Windmill", "man_made")
    )

    val BY_TAG: Map<String, PoiTagDef> = TAGS.associateBy { it.tag }

    /**
     * Повертає найбільш значущий стандартизований тег із набору тегів OSM точки.
     */
    fun extractPrimaryTag(tagMap: Map<String, String>): String {
        val amenity = tagMap["amenity"]?.lowercase()?.trim()
        val shop = tagMap["shop"]?.lowercase()?.trim()
        val tourism = tagMap["tourism"]?.lowercase()?.trim()
        val natural = tagMap["natural"]?.lowercase()?.trim()
        val highway = tagMap["highway"]?.lowercase()?.trim()
        val historic = tagMap["historic"]?.lowercase()?.trim()
        val leisure = tagMap["leisure"]?.lowercase()?.trim()
        val healthcare = tagMap["healthcare"]?.lowercase()?.trim()
        val waterway = tagMap["waterway"]?.lowercase()?.trim()
        val manMade = tagMap["man_made"]?.lowercase()?.trim()
        val emergency = tagMap["emergency"]?.lowercase()?.trim()
        val railway = tagMap["railway"]?.lowercase()?.trim()
        val publicTransport = tagMap["public_transport"]?.lowercase()?.trim()

        // 1. Спеціальний випадок: електрозарядки
        if (amenity == "charging_station" || (tagMap["fuel:electricity"] == "yes" && amenity != "fuel")) {
            return "charging_station"
        }

        // 2. Healthcare
        if (healthcare != null && BY_TAG.containsKey(healthcare)) return healthcare
        if (amenity != null && (amenity == "pharmacy" || amenity == "hospital" || amenity == "clinic" || amenity == "dentist" || amenity == "doctors" || amenity == "veterinary")) {
            return amenity
        }

        // 3. Fuel & Transport
        if (amenity == "fuel") return "fuel"
        if (highway == "bus_stop" || publicTransport == "platform" || publicTransport == "stop_position") return "bus_stop"
        if (railway == "tram_stop") return "tram_stop"
        if (railway == "station" || railway == "halt") return "station"
        if (amenity == "bus_station") return "bus_station"

        // 4. Food & Drinks
        if (amenity != null && (amenity == "cafe" || amenity == "restaurant" || amenity == "fast_food" || amenity == "bar" || amenity == "pub" || amenity == "biergarten" || amenity == "food_court")) {
            return amenity
        }

        // 5. Shops
        if (shop != null) {
            if (BY_TAG.containsKey(shop)) return shop
            return "supermarket"
        }

        // 6. Tourism & Lodging
        if (tourism != null && BY_TAG.containsKey(tourism)) return tourism

        // 7. Nature & Water
        if (natural != null && BY_TAG.containsKey(natural)) return natural
        if (waterway == "waterfall") return "waterfall"
        if (amenity == "drinking_water" || amenity == "fountain") return amenity

        // 8. Finance & Amenities
        if (amenity == "bank" || amenity == "atm" || amenity == "bureau_de_change") return amenity
        if (amenity == "toilets" || amenity == "post_office" || amenity == "shelter" || amenity == "parking" || amenity == "car_wash") return amenity

        // 9. Historic
        if (historic != null && BY_TAG.containsKey(historic)) return historic

        // 10. Education, Emergency, Leisure
        if (amenity != null && BY_TAG.containsKey(amenity)) return amenity
        if (emergency != null && BY_TAG.containsKey(emergency)) return emergency
        if (leisure != null && BY_TAG.containsKey(leisure)) return leisure
        if (manMade != null && BY_TAG.containsKey(manMade)) return manMade

        return amenity ?: shop ?: tourism ?: natural ?: "other"
    }
}
