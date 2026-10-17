package com.olegskal.mushroom.address

data class PoiTagDef(
    val tag: String,
    val descEn: String,
    val groupCategory: String
)

object PoiTagCatalog {
    val TAGS = listOf(
        // 1. Охорона здоров'я (Healthcare)
        PoiTagDef("pharmacy", "Pharmacy", "healthcare"),
        PoiTagDef("hospital", "Hospital", "healthcare"),
        PoiTagDef("clinic", "Clinic", "healthcare"),
        PoiTagDef("doctors", "Doctor's Office", "healthcare"),
        PoiTagDef("dentist", "Dentist", "healthcare"),
        PoiTagDef("veterinary", "Veterinary Clinic", "healthcare"),
        PoiTagDef("optician", "Optician", "healthcare"),
        PoiTagDef("optometrist", "Optometrist", "healthcare"),
        PoiTagDef("laboratory", "Medical Laboratory", "healthcare"),
        PoiTagDef("blood_donation", "Blood Donation Center", "healthcare"),
        PoiTagDef("rehabilitation", "Rehabilitation Center", "healthcare"),

        // 2. Авто, транспорт, паливо та зарядки (Transport & Fuel)
        PoiTagDef("fuel", "Fuel Station", "fuel"),
        PoiTagDef("charging_station", "Charging Station", "charging"),
        PoiTagDef("parking", "Parking Lot", "highway"),
        PoiTagDef("bicycle_parking", "Bicycle Parking", "highway"),
        PoiTagDef("bicycle_rental", "Bicycle Rental", "highway"),
        PoiTagDef("car_wash", "Car Wash", "amenity"),
        PoiTagDef("car_repair", "Auto Repair Shop", "shop"),
        PoiTagDef("car_parts", "Auto Parts Store", "shop"),
        PoiTagDef("car_rental", "Car Rental", "amenity"),
        PoiTagDef("taxi", "Taxi Stand", "amenity"),
        PoiTagDef("tyres", "Tire Service and Shop", "shop"),

        // 3. Їжа та напої (Food & Drinks)
        PoiTagDef("cafe", "Cafe", "food"),
        PoiTagDef("restaurant", "Restaurant", "food"),
        PoiTagDef("fast_food", "Fast Food", "food"),
        PoiTagDef("bar", "Bar", "food"),
        PoiTagDef("pub", "Pub", "food"),
        PoiTagDef("biergarten", "Beer Garden", "food"),
        PoiTagDef("food_court", "Food Court", "food"),
        PoiTagDef("ice_cream", "Ice Cream Shop", "food"),
        PoiTagDef("canteen", "Canteen", "food"),
        PoiTagDef("pizzeria", "Pizzeria", "food"),

        // 4. Продуктові магазини (Grocery & Food Shops)
        PoiTagDef("supermarket", "Supermarket", "shop"),
        PoiTagDef("convenience", "Grocery Store", "shop"),
        PoiTagDef("grocery", "Grocery Store", "shop"),
        PoiTagDef("bakery", "Bakery", "shop"),
        PoiTagDef("butcher", "Butcher Shop", "shop"),
        PoiTagDef("confectionery", "Confectionery", "shop"),
        PoiTagDef("pastry", "Pastry Shop", "shop"),
        PoiTagDef("seafood", "Seafood Store", "shop"),
        PoiTagDef("alcohol", "Liquor Store", "shop"),
        PoiTagDef("beverages", "Beverage Store", "shop"),
        PoiTagDef("kiosk", "Kiosk", "shop"),
        PoiTagDef("deli", "Delicatessen", "shop"),

        // 5. Роздрібні та спеціалізовані магазини (Retail & Specialty)
        PoiTagDef("mall", "Shopping Mall", "shop"),
        PoiTagDef("department_store", "Department Store", "shop"),
        PoiTagDef("clothes", "Clothing Store", "shop"),
        PoiTagDef("shoes", "Shoe Store", "shop"),
        PoiTagDef("beauty", "Beauty Salon", "shop"),
        PoiTagDef("hairdresser", "Barbershop / Hairdresser", "shop"),
        PoiTagDef("chemist", "Household Goods and Cosmetics", "shop"),
        PoiTagDef("cosmetics", "Cosmetics Store", "shop"),
        PoiTagDef("jewelry", "Jewelry Store", "shop"),
        PoiTagDef("hardware", "Hardware Store", "shop"),
        PoiTagDef("doityourself", "Building Materials Store", "shop"),
        PoiTagDef("furniture", "Furniture Store", "shop"),
        PoiTagDef("electronics", "Electronics Store", "shop"),
        PoiTagDef("mobile_phone", "Mobile Phone Store", "shop"),
        PoiTagDef("computer", "Computer Store", "shop"),
        PoiTagDef("pet", "Pet Store", "shop"),
        PoiTagDef("florist", "Flower Shop", "shop"),
        PoiTagDef("pawnbroker", "Pawnshop", "shop"),
        PoiTagDef("books", "Bookstore", "shop"),
        PoiTagDef("stationery", "Stationery Store", "shop"),
        PoiTagDef("toys", "Toy Store", "shop"),
        PoiTagDef("sports", "Sports Goods Store", "shop"),
        PoiTagDef("outdoor", "Camping and Outdoor Store", "shop"),
        PoiTagDef("bicycle", "Bicycle Shop", "shop"),
        PoiTagDef("tobacco", "Tobacco Shop", "shop"),
        PoiTagDef("optics", "Eyewear Store", "shop"),
        PoiTagDef("second_hand", "Second Hand Store", "shop"),
        PoiTagDef("tailor", "Tailor Shop", "shop"),
        PoiTagDef("dry_cleaning", "Dry Cleaning", "shop"),
        PoiTagDef("laundry", "Laundromat", "shop"),
        PoiTagDef("shoe_repair", "Shoe Repair", "shop"),
        PoiTagDef("locksmith", "Locksmith Workshop", "shop"),

        // 6. Природа та вода (Nature & Water)
        PoiTagDef("spring", "Water Spring", "water"),
        PoiTagDef("drinking_water", "Drinking Water", "water"),
        PoiTagDef("water_well", "Water Well", "water"),
        PoiTagDef("fountain", "Water Fountain", "water"),
        PoiTagDef("waterfall", "Waterfall", "water"),
        PoiTagDef("peak", "Mountain Peak", "natural"),
        PoiTagDef("saddle", "Mountain Saddle", "natural"),
        PoiTagDef("cave_entrance", "Cave Entrance", "natural"),
        PoiTagDef("rock", "Natural Rock", "natural"),
        PoiTagDef("bare_rock", "Natural Rock", "natural"),
        PoiTagDef("cliff", "Cliff", "natural"),
        PoiTagDef("beach", "Beach", "natural"),
        PoiTagDef("tree", "Monumental Tree", "natural"),
        PoiTagDef("wood", "Forest Grove", "natural"),
        PoiTagDef("forest", "Forest Grove", "natural"),
        PoiTagDef("lake", "Lake", "water"),
        PoiTagDef("stone", "Large Boulder", "natural"),
        PoiTagDef("volcano", "Volcano", "natural"),

        // 7. Туризм, культура та проживання (Tourism & Lodging)
        PoiTagDef("hotel", "Hotel", "accommodation"),
        PoiTagDef("motel", "Motel", "accommodation"),
        PoiTagDef("hostel", "Hostel", "accommodation"),
        PoiTagDef("guest_house", "Guest House", "accommodation"),
        PoiTagDef("chalet", "Chalet", "accommodation"),
        PoiTagDef("alpine_hut", "Mountain Shelter", "accommodation"),
        PoiTagDef("camp_site", "Campsite", "shelter"),
        PoiTagDef("caravan_site", "RV Park / Caravan Site", "shelter"),
        PoiTagDef("picnic_site", "Picnic Site", "shelter"),
        PoiTagDef("shelter", "Tourist Shelter", "shelter"),
        PoiTagDef("museum", "Museum", "tourism"),
        PoiTagDef("attraction", "Tourist Attraction", "tourism"),
        PoiTagDef("viewpoint", "Scenic Viewpoint", "tourism"),
        PoiTagDef("theme_park", "Theme Park", "tourism"),
        PoiTagDef("zoo", "Zoo", "tourism"),
        PoiTagDef("aquarium", "Aquarium", "tourism"),
        PoiTagDef("gallery", "Art Gallery", "tourism"),
        PoiTagDef("artwork", "Artwork / Sculpture", "tourism"),
        PoiTagDef("information", "Tourist Information", "tourism"),

        // 8. Історія та релігія (Historic & Religion)
        PoiTagDef("monument", "Monument", "historic"),
        PoiTagDef("memorial", "Memorial", "historic"),
        PoiTagDef("castle", "Castle", "historic"),
        PoiTagDef("ruins", "Historic Ruins", "historic"),
        PoiTagDef("archaeological_site", "Archaeological Site", "historic"),
        PoiTagDef("wayside_cross", "Wayside Cross", "historic"),
        PoiTagDef("wayside_shrine", "Wayside Shrine", "historic"),
        PoiTagDef("fort", "Fortress", "historic"),
        PoiTagDef("battlefield", "Battlefield", "historic"),
        PoiTagDef("manor", "Historic Manor", "historic"),
        PoiTagDef("place_of_worship", "Church / Temple", "historic"),
        PoiTagDef("monastery", "Monastery", "historic"),

        // 9. Громадський транспорт (Public Transport)
        PoiTagDef("bus_stop", "Bus Stop", "public_transport"),
        PoiTagDef("tram_stop", "Tram Stop", "public_transport"),
        PoiTagDef("platform", "Transit Platform", "public_transport"),
        PoiTagDef("station", "Train Station", "public_transport"),
        PoiTagDef("halt", "Train Halt", "public_transport"),
        PoiTagDef("subway_entrance", "Metro Entrance", "public_transport"),
        PoiTagDef("bus_station", "Bus Station", "public_transport"),
        PoiTagDef("ferry_terminal", "Ferry Terminal", "public_transport"),

        // 10. Фінанси та зв'язок (Finance & Communication)
        PoiTagDef("bank", "Bank", "finance"),
        PoiTagDef("atm", "ATM", "finance"),
        PoiTagDef("bureau_de_change", "Currency Exchange", "finance"),
        PoiTagDef("post_office", "Post Office", "amenity"),
        PoiTagDef("post_box", "Post Box", "amenity"),

        // 11. Адміністрація, право та екстрені служби (Admin & Emergency)
        PoiTagDef("townhall", "Town Hall", "administrative"),
        PoiTagDef("courthouse", "Courthouse", "administrative"),
        PoiTagDef("government", "Government Office", "administrative"),
        PoiTagDef("police", "Police Station", "emergency"),
        PoiTagDef("fire_station", "Fire Station", "emergency"),
        PoiTagDef("ambulance_station", "Ambulance Station", "emergency"),
        PoiTagDef("community_centre", "Community Centre", "administrative"),
        PoiTagDef("social_facility", "Social Facility", "administrative"),
        PoiTagDef("prison", "Prison", "administrative"),

        // 12. Освіта (Education)
        PoiTagDef("school", "School", "education"),
        PoiTagDef("kindergarten", "Kindergarten", "education"),
        PoiTagDef("university", "University", "education"),
        PoiTagDef("college", "College", "education"),
        PoiTagDef("library", "Library", "education"),
        PoiTagDef("music_school", "Music School", "education"),
        PoiTagDef("driving_school", "Driving School", "education"),

        // 13. Дозвілля, спорт та розваги (Leisure, Sport & Entertainment)
        PoiTagDef("park", "City Park", "leisure"),
        PoiTagDef("playground", "Playground", "leisure"),
        PoiTagDef("pitch", "Sports Pitch", "sport"),
        PoiTagDef("stadium", "Stadium", "sport"),
        PoiTagDef("sports_centre", "Fitness / Sports Centre", "sport"),
        PoiTagDef("swimming_pool", "Swimming Pool", "sport"),
        PoiTagDef("fitness_centre", "Fitness Club", "sport"),
        PoiTagDef("sauna", "Sauna / Bathhouse", "amenity"),
        PoiTagDef("cinema", "Cinema", "entertainment"),
        PoiTagDef("theatre", "Theatre", "entertainment"),
        PoiTagDef("nightclub", "Nightclub", "entertainment"),
        PoiTagDef("water_park", "Water Park", "leisure"),
        PoiTagDef("bowling_alley", "Bowling Alley", "leisure"),

        // 14. Зручності та інфраструктура (Amenities & Man-Made)
        PoiTagDef("toilets", "Public Toilets", "amenity"),
        PoiTagDef("waste_basket", "Waste Basket", "amenity"),
        PoiTagDef("bench", "Bench", "amenity"),
        PoiTagDef("recycling", "Recycling Point", "amenity"),
        PoiTagDef("waste_disposal", "Waste Disposal", "amenity"),
        PoiTagDef("tower", "Tower", "man_made"),
        PoiTagDef("water_tower", "Water Tower", "man_made"),
        PoiTagDef("lighthouse", "Lighthouse", "man_made"),
        PoiTagDef("windmill", "Windmill", "man_made"),
        PoiTagDef("chimney", "Chimney", "man_made"),
        PoiTagDef("bridge", "Bridge", "man_made"),
        PoiTagDef("dock", "Pier / Dock", "man_made"),
        PoiTagDef("ranger_station", "Forestry / Ranger Station", "forestry"),
        PoiTagDef("garden", "Garden", "leisure"),
        PoiTagDef("fire_hydrant", "Fire Hydrant", "emergency"),
        PoiTagDef("track", "Track / Running Track", "leisure"),
        PoiTagDef("grave_yard", "Graveyard", "amenity"),
        PoiTagDef("greengrocer", "Greengrocer", "shop"),
        PoiTagDef("rest_area", "Rest Area", "highway"),
        PoiTagDef("water_point", "Water Point", "amenity"),
        PoiTagDef("speed_camera", "Speed Camera", "highway"),
        PoiTagDef("petroleum_well", "Petroleum Well", "man_made")
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
        val office = tagMap["office"]?.lowercase()?.trim()

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
        if (highway == "bus_stop" || highway == "platform" || publicTransport == "platform" || publicTransport == "stop_position" || publicTransport == "stop" || tagMap["bus"] == "yes" || tagMap["trolleybus"] == "yes" || tagMap["share_taxi"] == "yes") return "bus_stop"
        if (railway == "tram_stop") return "tram_stop"
        if (railway == "station" || railway == "halt") return "station"
        if (amenity == "bus_station") return "bus_station"

        // 4. Food & Drinks
        if (amenity != null && (amenity == "cafe" || amenity == "restaurant" || amenity == "fast_food" || amenity == "bar" || amenity == "pub" || amenity == "biergarten" || amenity == "food_court" || amenity == "ice_cream" || amenity == "canteen" || amenity == "pizzeria")) {
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
        if (waterway != null && BY_TAG.containsKey(waterway)) return waterway
        if (amenity == "drinking_water" || amenity == "fountain") return amenity

        // 8. Finance & Amenities
        if (amenity == "bank" || amenity == "atm" || amenity == "bureau_de_change") return amenity
        if (amenity == "toilets" || amenity == "post_office" || amenity == "post_box" || amenity == "shelter" || amenity == "parking" || amenity == "car_wash" || amenity == "waste_basket" || amenity == "bench" || amenity == "recycling" || amenity == "waste_disposal") return amenity

        // 9. Historic
        if (historic != null && BY_TAG.containsKey(historic)) return historic

        // 10. Education, Emergency, Leisure, Man Made, Office, Highway
        if (amenity != null && BY_TAG.containsKey(amenity)) return amenity
        if (emergency != null && BY_TAG.containsKey(emergency)) return emergency
        if (leisure != null && BY_TAG.containsKey(leisure)) return leisure
        if (manMade != null && BY_TAG.containsKey(manMade)) return manMade
        if (office != null && BY_TAG.containsKey(office)) return office
        if (highway != null && BY_TAG.containsKey(highway)) return highway

        return amenity ?: shop ?: tourism ?: natural ?: historic ?: leisure ?: "other"
    }
}
