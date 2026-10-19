package world.xhere.shared.suggest

/**
 * Maps on-device image labels to a built-in category slug.
 *
 * ML Kit's bundled image labeler returns short English labels such as "Food"
 * or "Monument". Gemini Nano may also hand back a slug. Both paths go through
 * this map so a label can only become a category that already exists.
 * Labels that are not in the map (sky, person, vehicle) do not force a category.
 */
object LabelCategoryMap {
    private val exact: Map<String, String> = mapOf(
        "food truck" to "food_truck",
        "fast food" to "food_truck",
        "street food" to "food_truck",
        "food" to "eatery_tip",
        "meal" to "eatery_tip",
        "cuisine" to "eatery_tip",
        "dish" to "eatery_tip",
        "dessert" to "eatery_tip",
        "baked goods" to "eatery_tip",
        "restaurant" to "eatery_tip",
        "cafe" to "eatery_tip",
        "market" to "market",
        "farmers market" to "market",
        "bazaar" to "market",
        "flower" to "wildlife",
        "plant" to "wildlife",
        "tree" to "wildlife",
        "animal" to "wildlife",
        "bird" to "wildlife",
        "dog" to "wildlife",
        "cat" to "wildlife",
        "mammal" to "wildlife",
        "insect" to "wildlife",
        "wildlife" to "wildlife",
        "building" to "historical_fact",
        "architecture" to "historical_fact",
        "house" to "historical_fact",
        "landmark" to "historical_fact",
        "castle" to "historical_fact",
        "ruins" to "historical_fact",
        "monument" to "memorial",
        "memorial" to "memorial",
        "statue" to "public_art",
        "sculpture" to "public_art",
        "tombstone" to "memorial",
        "cemetery" to "memorial",
        "plaque" to "historical_marker",
        "historical marker" to "historical_marker",
        "historic marker" to "historical_marker",
        "marker" to "historical_marker",
        "information board" to "historical_marker",
        "graffiti" to "street_art",
        "mural" to "street_art",
        "street art" to "street_art",
        "public art" to "public_art",
        "church" to "place_of_worship",
        "chapel" to "place_of_worship",
        "cathedral" to "place_of_worship",
        "mosque" to "place_of_worship",
        "temple" to "place_of_worship",
        "synagogue" to "place_of_worship",
        "place of worship" to "place_of_worship",
        "library" to "community_space",
        "community center" to "community_space",
        "water" to "water_source",
        "fountain" to "water_source",
        "drinking fountain" to "water_source",
        "spring" to "water_source",
        "lake" to "viewpoint",
        "river" to "viewpoint",
        "mountain" to "viewpoint",
        "overlook" to "viewpoint",
        "vista" to "viewpoint",
        "landscape" to "viewpoint",
        "beach" to "viewpoint",
        "trail" to "trail_note",
        "hiking" to "trail_note",
        "path" to "trail_note",
        "road" to "hazard",
        "pothole" to "hazard",
        "flood" to "hazard",
        "construction" to "construction",
        "crane" to "construction",
        "scaffold" to "construction",
        "bridge" to "infrastructure",
        "tunnel" to "underground",
        "musical instrument" to "music_spot",
        "guitar" to "music_spot",
        "concert" to "event",
        "festival" to "event",
        "yard sale" to "yard_sale",
        "garage sale" to "yard_sale",
        "furniture" to "free_stuff",
        "couch" to "free_stuff",
    )

    /**
     * First label that maps to a slug in [allowed]. An empty [allowed] set
     * accepts any mapped slug (unit tests). Production passes the live taxonomy.
     */
    fun categorySlug(labels: List<String>, allowed: Set<String>): String? {
        for (label in labels) {
            val slug = exact[normalize(label)] ?: continue
            if (allowed.isEmpty() || slug in allowed) return slug
        }
        return null
    }

    /** Short alt text a screen reader can speak. Null when there are no labels. */
    fun altText(labels: List<String>): String? {
        val clean = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)
        if (clean.isEmpty()) return null
        return clip("Photo of ${clean.joinToString(", ")}.", ALT_MAX)
    }

    fun titleFromLabels(labels: List<String>): String? {
        val first = labels.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return clip(first, TITLE_MAX)
    }

    fun descriptionFromLabels(labels: List<String>): String {
        val clean = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
        if (clean.isEmpty()) return ""
        return clip("Looks like ${clean.joinToString(", ")}.", DESCRIPTION_MAX)
    }

    fun normalize(label: String): String =
        label.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    const val TITLE_MAX: Int = 80
    const val DESCRIPTION_MAX: Int = 280
    const val ALT_MAX: Int = 300

    fun clip(value: String, max: Int): String {
        val compact = value.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= max) return compact
        return compact.take(max).trimEnd()
    }
}
