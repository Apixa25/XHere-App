package world.xhere.shared.demo

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.fake.StoredDrop
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Category
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.Profile
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.TierLimits
import world.xhere.shared.model.UserTier

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

    /**
     * Not a profile. Open-data pins use this id so they never match a person's
     * account, including the demo guide who posts the other Jamestown drops.
     */
    val PUBLIC_RECORDS: Uuid = id(90)

    /** Author of the demo user drops. Public records are not theirs. */
    val guide: Profile = Profile(
        id = id(1),
        handle = "demo_guide",
        displayName = "Demo guide",
        tier = UserTier.TRUSTED,
        createdAt = createdAt,
    )

    fun store(): MemoryStore {
        val groups = groups()
        val categories = categories(groups)
        val taxonomy = Taxonomy(groups, categories, filters(), limits())
        val store = MemoryStore(taxonomy)
        store.profiles[guide.id] = guide
        fun put(
            shortId: String,
            categorySlug: String,
            title: String,
            body: String,
            latitude: Double,
            longitude: Double,
            source: DropSource = DropSource.USER,
            sourceName: String? = null,
            sourceUrl: String? = null,
            license: String? = null,
            attribution: String? = null,
        ) {
            val category = checkNotNull(taxonomy.category(categorySlug))
            val groupSlug = groups.first { it.id == category.groupId }.slug
            val dropId = id(200 + store.drops.size)
            val publicRecord = source == DropSource.OPEN_DATA
            store.drops[dropId] = StoredDrop(
                id = dropId,
                shortId = shortId,
                authorId = if (publicRecord) PUBLIC_RECORDS else guide.id,
                authorHandle = if (publicRecord) "" else guide.handle,
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
                source = source,
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                license = license,
                attribution = attribution,
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
        // A public record inside downtown, so the map is not only user drops.
        // It is not authored by demo_guide and does not count as their contribution.
        put(
            "records",
            "historical_fact",
            "Prendergast Library",
            "Public library building. Open the source for the register entry.",
            42.09824,
            -79.24382,
            source = DropSource.OPEN_DATA,
            sourceName = "National Register of Historic Places",
            sourceUrl = "https://npgallery.nps.gov/AssetDetail/NRIS/85000628",
            license = "Public domain",
            attribution = "National Park Service, National Register of Historic Places. Public domain.",
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
        val history = inGroup("history_stories")
        val nature = inGroup("nature_outdoors")
        val food = inGroup("food_drink")
        val events = inGroup("events")
        val sales = inGroup("sales_free")
        val safety = inGroup("safety_alerts")
        val community = inGroup("community_faith")
        val works = inGroup("infrastructure")
        val secrets = inGroup("local_secrets")
        val art = inGroup("art_culture")
        val notes = inGroup("note")
        return listOf(
            category(21, history, "historical_fact", "Historical fact", "🏛️", 150, 1, attributes = HISTORICAL_SCHEMA),
            category(22, history, "story", "Story", "📖", 150, 2, attributes = HISTORICAL_SCHEMA),
            category(30, history, "memorial", "Memorial", "🕯️", 150, 3, attributes = HISTORICAL_SCHEMA),
            category(23, nature, "viewpoint", "Viewpoint", "🏞️", 300, 1),
            category(31, nature, "trail_note", "Trail note", "🥾", 300, 2),
            category(32, nature, "wildlife", "Wildlife sighting", "🦉", 300, 3),
            category(33, nature, "water_source", "Water source", "💧", 300, 4),
            category(
                24, food, "food_truck", "Food truck", "🚚", 500, 1,
                attributes = FOOD_SCHEMA,
                confirmations = 2,
                denials = 2,
                confirmExtendsTtl = 6.hours,
            ),
            category(25, food, "eatery_tip", "Eatery tip", "🍽️", 500, 2, attributes = FOOD_SCHEMA),
            category(34, food, "market", "Market", "🧺", 500, 3, attributes = FOOD_SCHEMA),
            category(
                26, events, "event", "Event", "🎟️", 1000, 1,
                attributes = EVENT_SCHEMA,
                maxTtl = 365.days,
                maxTtlNew = 30.days,
                expiresAtAttribute = "ends_at",
                confirmations = 2,
            ),
            category(
                35, events, "popup", "Pop-up", "⛺", 1000, 2,
                attributes = EVENT_SCHEMA,
                maxTtl = 365.days,
                maxTtlNew = 30.days,
                expiresAtAttribute = "ends_at",
                confirmations = 2,
            ),
            category(27, sales, "yard_sale", "Yard sale", "🏠", 1000, 1, confirmations = 2),
            category(36, sales, "for_sale", "For sale", "💵", 1000, 2, confirmations = 2),
            category(37, sales, "free_stuff", "Free stuff", "🎁", 1000, 3, confirmations = 2),
            category(
                28, safety, "hazard", "Hazard", "⚠️", 2000, 1,
                attributes = HAZARD_SCHEMA,
                defaultTtl = 24.hours,
                maxTtl = 72.hours,
                filters = listOf("contact_info"),
                confirmations = 2,
                denials = 2,
                reports = 2,
                confirmExtendsTtl = 24.hours,
            ),
            category(
                38, safety, "safety_alert", "Safety alert", "📢", 2000, 2,
                attributes = INCIDENT_SCHEMA,
                sensitive = true,
                postingNotice = INCIDENT_NOTICE,
                filters = listOf("contact_info", "person_descriptors"),
                moderation = ModerationMode.PRE_FOR_NEW,
                defaultTtl = 72.hours,
                maxTtl = 72.hours,
                confirmations = 2,
                denials = 2,
                reports = 2,
            ),
            category(
                39, safety, "incident_report", "Incident report", "🚓", 2000, 3,
                attributes = INCIDENT_SCHEMA,
                minTier = UserTier.REGULAR,
                moderation = ModerationMode.PRE,
                premodExempt = UserTier.TRUSTED,
                sensitive = true,
                postingNotice = INCIDENT_NOTICE,
                filters = listOf("contact_info", "person_descriptors", "street_address"),
                precisionM = 100,
                defaultTtl = 72.hours,
                maxTtl = 72.hours,
                confirmations = 2,
                denials = 2,
                reports = 2,
            ),
            category(40, community, "place_of_worship", "Place of worship", "🛐", 300, 1),
            category(41, community, "community_space", "Community space", "🏘️", 300, 2),
            category(42, community, "notice_board", "Notice", "📌", 300, 3, minTier = UserTier.REGULAR),
            category(43, works, "infrastructure", "Infrastructure", "🌉", 200, 1),
            category(44, works, "construction", "Construction", "🚧", 200, 2),
            category(45, works, "underground", "Underground", "🚇", 200, 3),
            category(46, secrets, "hidden_gem", "Hidden gem", "💎", 50, 1, confirmations = 2),
            category(47, secrets, "tip", "Local tip", "💡", 50, 2, confirmations = 2),
            category(48, art, "street_art", "Street art", "🎨", 150, 1),
            category(49, art, "public_art", "Public art", "🗿", 150, 2),
            category(50, art, "music_spot", "Music spot", "🎸", 150, 3),
            category(29, notes, "note", "Note", "📍", 100, 1, confirmations = 2, permanentWhenConfirmed = true),
        )
    }

    private fun filters(): List<ContentFilter> = listOf(
        ContentFilter(
            slug = "contact_info",
            description = "Phone numbers and email addresses.",
            pattern = """(\+?[0-9][ .()-]*){10,}|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}""",
            message = "Keep contact in the app. Remove phone numbers and email addresses.",
        ),
        ContentFilter(
            slug = "person_descriptors",
            description = "Words that describe people.",
            pattern = """\m(suspects?|perps?|perpetrators?|m[ae]n|wom[ae]n|guys?|gals?|males?|females?|boys?|girls?|teens?|teenagers?|kids?|juveniles?|thugs?|wearing|hoodies?|skin|skinned|complexion|ethnicity|race|racial|black|white|brown|asian|hispanic|latin[oax]|african|arab|middle[- ]eastern|mexican|caucasian|tall|heavyset|skinny|bearded|beard|tattoos?|dreads|dreadlocks|years? old|[0-9]{1,2} ?(yo|y/o))\M""",
            message = "Describe what happened, not who. Do not name or describe people.",
        ),
        ContentFilter(
            slug = "street_address",
            description = "A house number followed by a street name. Incident reports describe an area, not a home.",
            pattern = """\m[0-9]{1,6}[a-z]? +([a-z0-9.'-]+ +){0,2}(street|st|avenue|ave|road|rd|boulevard|blvd|parkway|pkwy|highway|hwy)\M""",
            message = "Leave out street addresses. The map already shows the approximate area.",
        ),
    )

    private fun limits(): List<TierLimits> = listOf(
        TierLimits(UserTier.NEW, 3, 10, 20, 10, 4),
        TierLimits(UserTier.REGULAR, 10, 40, 60, 25, 6),
        TierLimits(UserTier.TRUSTED, 30, 120, 120, 50, 10),
        TierLimits(UserTier.GUIDE, 60, 240, 240, 100, 10),
    )

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
        attributes: JsonObject = EMPTY_SCHEMA,
        minTier: UserTier = UserTier.NEW,
        moderation: ModerationMode = ModerationMode.POST,
        premodExempt: UserTier? = null,
        sensitive: Boolean = false,
        postingNotice: String? = null,
        filters: List<String> = emptyList(),
        precisionM: Int = 0,
        defaultTtl: Duration? = null,
        maxTtl: Duration? = null,
        maxTtlNew: Duration? = null,
        expiresAtAttribute: String? = null,
        confirmations: Int = 3,
        denials: Int = 3,
        reports: Int = 3,
        permanentWhenConfirmed: Boolean = false,
        confirmExtendsTtl: Duration? = null,
    ) = Category(
        id = id(n),
        groupId = group.id,
        slug = slug,
        label = label,
        icon = icon,
        color = group.color,
        attributesSchema = attributes,
        minTierToPost = minTier,
        moderationMode = moderation,
        premodExemptTier = premodExempt,
        sensitive = sensitive,
        postingNotice = postingNotice,
        contentFilters = filters,
        defaultRevealM = revealM,
        maxRevealM = revealM,
        locationPrecisionM = precisionM,
        defaultTtl = defaultTtl,
        maxTtl = maxTtl,
        maxTtlNew = maxTtlNew,
        expiresAtAttribute = expiresAtAttribute,
        confirmationsToConfirm = confirmations,
        denialsToHide = denials,
        reportsToHide = reports,
        permanentWhenConfirmed = permanentWhenConfirmed,
        confirmExtendsTtl = confirmExtendsTtl,
        sort = sort,
    )

    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun id(n: Int): Uuid = Uuid.parse("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")

    private val EMPTY_SCHEMA = schema("""{"type":"object","additionalProperties":false,"properties":{}}""")
    private val HISTORICAL_SCHEMA = schema(
        """{"type":"object","additionalProperties":false,"properties":{
        "era":{"type":"string","maxLength":60},
        "year":{"type":"integer","minimum":-5000,"maximum":2100},
        "source_url":{"type":"string","format":"uri","maxLength":500}}}""",
    )
    private val FOOD_SCHEMA = schema(
        """{"type":"object","additionalProperties":false,"properties":{
        "cuisine":{"type":"string","maxLength":60},
        "price_level":{"type":"integer","minimum":1,"maximum":4}}}""",
    )
    private val EVENT_SCHEMA = schema(
        """{"type":"object","additionalProperties":false,"required":["starts_at","ends_at"],"properties":{
        "starts_at":{"type":"string","format":"date-time"},
        "ends_at":{"type":"string","format":"date-time"},
        "cost":{"type":"string","maxLength":60}}}""",
    )
    private val HAZARD_SCHEMA = schema(
        """{"type":"object","additionalProperties":false,"required":["occurred_at"],"properties":{
        "occurred_at":{"type":"string","format":"date-time"},
        "severity":{"enum":["low","med","high"]},
        "ongoing":{"type":"boolean"}}}""",
    )
    private val INCIDENT_SCHEMA = schema(
        """{"type":"object","additionalProperties":false,"required":["occurred_at"],"properties":{
        "occurred_at":{"type":"string","format":"date-time"},
        "severity":{"enum":["low","med","high"]},
        "ongoing":{"type":"boolean"}}}""",
    )

}

private const val INCIDENT_NOTICE: String =
    "Describe what happened, not who. Do not name or describe people. In an emergency call 911. XHere is not a reporting channel to police."
