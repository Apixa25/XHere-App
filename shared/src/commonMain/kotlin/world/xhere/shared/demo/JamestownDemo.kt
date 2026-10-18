package world.xhere.shared.demo

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.fake.StoredDrop
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Category
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.Profile
import world.xhere.shared.model.Taxonomy

/**
 * A few Jamestown, NY drops for the in-memory fake (PLAN §7 M4, §10 question 3).
 * Coordinates match the public spots in `supabase/seed.sql`. Nothing here is a secret,
 * and nothing is loaded into a hosted database.
 *
 * [buffalo] is outside the demo city on purpose, so a viewport test can prove a pan
 * does not keep the Jamestown drops.
 */
object JamestownDemo {
    val createdAt: Instant = Instant.parse("2026-10-18T16:00:00Z")

    /** West 3rd Street, the downtown cafe block. Default camera when location is off. */
    val center = LatLng(42.09636, -79.24202)
    const val STREET_ZOOM: Int = 15
    const val CLUSTER_ZOOM: Int = 10

    /** Tight downtown box. The Celoron lake view and Buffalo are outside it. */
    val downtown = BoundingBox(
        minLongitude = -79.250,
        minLatitude = 42.090,
        maxLongitude = -79.232,
        maxLatitude = 42.102,
    )

    /** Lucille Ball Memorial Park, Celoron. Inside a 5 km "near me", outside [downtown]. */
    val celoron = BoundingBox(
        minLongitude = -79.290,
        minLatitude = 42.105,
        maxLongitude = -79.270,
        maxLatitude = 42.118,
    )

    /** Niagara Square, Buffalo. Outside Jamestown and outside a 5 km radius. */
    val buffalo = BoundingBox(
        minLongitude = -78.900,
        minLatitude = 42.870,
        maxLongitude = -78.850,
        maxLatitude = 42.910,
    )

    const val PREVIEW_EMAIL: String = "preview@xhere.world"
    const val PREVIEW_CODE: String = "preview"

    /** Not the author of the demo drops, so teasers stay locked. */
    val viewer: Profile = Profile(
        id = id(99),
        handle = "preview",
        displayName = "Preview",
        homeArea = "Jamestown, NY",
        createdAt = createdAt,
    )

    fun store(): MemoryStore {
        val groups = groups()
        val categories = categories(groups)
        val taxonomy = Taxonomy(groups, categories, emptyList(), emptyList())
        val store = MemoryStore(taxonomy)
        fun put(
            shortId: String,
            categorySlug: String,
            title: String,
            body: String,
            latitude: Double,
            longitude: Double,
        ) {
            val category = checkNotNull(taxonomy.category(categorySlug))
            val groupSlug = groups.first { it.id == category.groupId }.slug
            val dropId = id(200 + store.drops.size)
            store.drops[dropId] = StoredDrop(
                id = dropId,
                shortId = shortId,
                authorId = id(1),
                authorHandle = "demo_guide",
                groupSlug = groupSlug,
                category = category,
                title = title,
                body = body,
                attributes = JsonObject(emptyMap()),
                tags = emptyList(),
                trueLocation = LatLng(latitude, longitude),
                displayLocation = LatLng(latitude, longitude),
                revealRadiusM = category.defaultRevealM,
                status = DropStatus.ACTIVE,
                displayAnonymous = false,
                expiresAt = null,
                createdAt = createdAt,
                media = emptyList(),
            )
        }
        put(
            "fenton",
            "historical_fact",
            "Fenton Mansion",
            "Reuben Fenton built this house in 1863. It is a public museum now.",
            42.09122,
            -79.23920,
        )
        put(
            "lunch",
            "eatery_tip",
            "West 3rd at lunch",
            "Cafes and outdoor tables between Main and Washington.",
            42.09636,
            -79.24202,
        )
        put(
            "truck",
            "food_truck",
            "Texas hot cart on Rosa Alley",
            "A lunch cart by the Comedy Center.",
            42.09520,
            -79.24390,
        )
        put(
            "concert",
            "event",
            "Tracy Plaza lunchtime concert",
            "Brass quintet on the plaza.",
            42.09642,
            -79.23843,
        )
        put(
            "hazard",
            "hazard",
            "Sidewalk closed on the Riverwalk",
            "Scaffolding blocks the walk by Brooklyn Square.",
            42.09395,
            -79.24370,
        )
        put(
            "benches",
            "note",
            "Benches in the shade",
            "The Riverwalk benches on the north bank get shade after 2 pm.",
            42.09368,
            -79.24289,
        )
        put(
            "lake",
            "viewpoint",
            "Lake view from Celoron",
            "Chautauqua Lake from the shore path.",
            42.11115,
            -79.28155,
        )
        put(
            "buffalo",
            "note",
            "Steps at Niagara Square",
            "A fixture outside Jamestown, used to prove the viewport does not return the whole world.",
            42.88640,
            -78.87840,
        )
        return store
    }

    private fun groups(): List<CategoryGroup> = listOf(
        group(1, "history_stories", "History & Stories", "📜", "#8D6E63", 1),
        group(2, "nature_outdoors", "Nature & Outdoors", "🌿", "#2E7D32", 2),
        group(3, "food_drink", "Food & Drink", "🍔", "#EF6C00", 3),
        group(4, "events", "Events", "🎪", "#8E24AA", 4),
        group(5, "sales_free", "Sales & Free Stuff", "🏷️", "#00897B", 5),
        group(6, "safety_alerts", "Safety & Alerts", "🚨", "#C62828", 6),
        group(7, "community_faith", "Community & Faith", "🏛️", "#3949AB", 7),
        group(8, "infrastructure", "Infrastructure & Hidden City", "🔧", "#546E7A", 8),
        group(9, "local_secrets", "Local Secrets", "💎", "#D81B60", 9),
        group(10, "art_culture", "Art & Culture", "🎨", "#F9A825", 10),
        group(11, "note", "Note", "📍", "#757575", 11),
    )

    private fun categories(groups: List<CategoryGroup>): List<Category> {
        fun inGroup(slug: String) = groups.first { it.slug == slug }
        return listOf(
            category(21, inGroup("history_stories"), "historical_fact", "Historical fact", "🏛️", 150, 1),
            category(22, inGroup("history_stories"), "story", "Story", "📖", 150, 2),
            category(23, inGroup("nature_outdoors"), "viewpoint", "Viewpoint", "🏞️", 300, 1),
            category(24, inGroup("food_drink"), "food_truck", "Food truck", "🚚", 500, 1),
            category(25, inGroup("food_drink"), "eatery_tip", "Eatery tip", "🍽️", 500, 2),
            category(26, inGroup("events"), "event", "Event", "🎟️", 1000, 1),
            category(27, inGroup("sales_free"), "yard_sale", "Yard sale", "🏠", 1000, 1),
            category(28, inGroup("safety_alerts"), "hazard", "Hazard", "⚠️", 2000, 1),
            category(29, inGroup("note"), "note", "Note", "📍", 100, 1),
        )
    }

    private fun group(n: Int, slug: String, label: String, icon: String, color: String, sort: Int) = CategoryGroup(
        id = id(n),
        slug = slug,
        label = label,
        icon = icon,
        color = color,
        sort = sort,
    )

    private fun category(
        n: Int,
        group: CategoryGroup,
        slug: String,
        label: String,
        icon: String,
        revealM: Int,
        sort: Int,
    ) = Category(
        id = id(n),
        groupId = group.id,
        slug = slug,
        label = label,
        icon = icon,
        color = group.color,
        attributesSchema = JsonObject(emptyMap()),
        defaultRevealM = revealM,
        maxRevealM = revealM,
        sort = sort,
    )

    private fun id(n: Int): Uuid = Uuid.parse("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")
}
