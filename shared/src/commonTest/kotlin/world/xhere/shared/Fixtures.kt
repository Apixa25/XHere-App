package world.xhere.shared

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import world.xhere.shared.model.Category
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.Profile
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.TierLimits
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.DropDraft
import world.xhere.shared.rules.PostingContext

/** Patterns copied from `supabase/migrations/20261018000600_taxonomy.sql`. */
internal object Fixtures {
    val now: Instant = Instant.parse("2026-10-18T04:00:00Z")

    val contactFilter = ContentFilter(
        slug = "contact_info",
        description = "Phone numbers and email addresses.",
        pattern = """(\+?[0-9][ .()-]*){10,}|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}""",
        message = "Keep contact in the app. Remove phone numbers and email addresses.",
    )

    val personFilter = ContentFilter(
        slug = "person_descriptors",
        description = "Words that describe people.",
        pattern = """\m(suspects?|perps?|perpetrators?|m[ae]n|wom[ae]n|guys?|gals?|males?|females?|boys?|girls?|teens?|teenagers?|kids?|juveniles?|thugs?|wearing|hoodies?|skin|skinned|complexion|ethnicity|race|racial|black|white|brown|asian|hispanic|latin[oax]|african|arab|middle[- ]eastern|mexican|caucasian|tall|heavyset|skinny|bearded|beard|tattoos?|dreads|dreadlocks|years? old|[0-9]{1,2} ?(yo|y/o))\M""",
        message = "Describe what happened, not who. Do not name or describe people.",
    )

    val newLimits = TierLimits("new".let { UserTier.NEW }, 3, 10, 20, 10, 4)
    val regularLimits = TierLimits(UserTier.REGULAR, 10, 40, 60, 25, 6)

    fun id(n: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-${n.toString().padStart(12, '0')}")

    fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    val noteGroup = CategoryGroup(id(10), "note", "Note", icon = "📍", color = "#757575", sort = 11)
    val safetyGroup = CategoryGroup(id(11), "safety_alerts", "Safety & Alerts", sort = 6)
    val eventsGroup = CategoryGroup(id(12), "events", "Events", sort = 4)
    val salesGroup = CategoryGroup(id(13), "sales_free", "Sales & Free Stuff", sort = 5)

    val note = Category(
        id = id(1),
        groupId = noteGroup.id,
        slug = "note",
        label = "Note",
        attributesSchema = schema("""{"type":"object","additionalProperties":false,"properties":{}}"""),
        defaultRevealM = 100,
        maxRevealM = 100,
        defaultTtl = 7.days,
        permanentWhenConfirmed = true,
        confirmationsToConfirm = 2,
        moderationMode = ModerationMode.PRE_FOR_NEW,
    )

    val event = Category(
        id = id(2),
        groupId = eventsGroup.id,
        slug = "event",
        label = "Event",
        attributesSchema = schema(
            """{"type":"object","additionalProperties":false,"required":["starts_at","ends_at"],"properties":{
            "starts_at":{"type":"string","format":"date-time"},
            "ends_at":{"type":"string","format":"date-time"},
            "cost":{"type":"string","maxLength":60},
            "link":{"type":"string","format":"uri","maxLength":500}}}""",
        ),
        defaultRevealM = 1000,
        maxRevealM = 1000,
        maxTtl = 365.days,
        maxTtlNew = 30.days,
        expiresAtAttribute = "ends_at",
        confirmationsToConfirm = 2,
    )

    val incident = Category(
        id = id(3),
        groupId = safetyGroup.id,
        slug = "incident_report",
        label = "Incident report",
        attributesSchema = schema(
            """{"type":"object","additionalProperties":false,"required":["occurred_at"],"properties":{
            "occurred_at":{"type":"string","format":"date-time"},
            "severity":{"enum":["low","med","high"]},
            "ongoing":{"type":"boolean"}}}""",
        ),
        minTierToPost = UserTier.REGULAR,
        moderationMode = ModerationMode.PRE,
        premodExemptTier = UserTier.TRUSTED,
        sensitive = true,
        postingNotice = "Describe what happened, not who.",
        contentFilters = listOf("contact_info", "person_descriptors"),
        defaultRevealM = 2000,
        maxRevealM = 2000,
        locationPrecisionM = 100,
        defaultTtl = 72.hours,
        maxTtl = 72.hours,
        confirmationsToConfirm = 2,
        denialsToHide = 2,
        reportsToHide = 2,
    )

    val yardSale = Category(
        id = id(4),
        groupId = salesGroup.id,
        slug = "yard_sale",
        label = "Yard sale",
        attributesSchema = schema(
            """{"type":"object","additionalProperties":false,"properties":{
            "price":{"type":"number","minimum":0,"maximum":1000000000},
            "currency":{"type":"string","pattern":"^[A-Z]{3}$"},
            "contact_method":{"enum":["in_app"]}}}""",
        ),
        defaultRevealM = 1000,
        maxRevealM = 1000,
        defaultTtl = 3.days,
        maxTtl = 7.days,
        contentFilters = listOf("contact_info"),
        allowAnonymousDisplay = false,
        confirmationsToConfirm = 2,
    )

    val photoNote = note.copy(id = id(5), slug = "photo_note", requiresPhoto = true, contentFilters = emptyList())

    val filters = listOf(contactFilter, personFilter)

    val taxonomy = Taxonomy(
        groups = listOf(noteGroup, safetyGroup, eventsGroup, salesGroup),
        categories = listOf(note, event, incident, yardSale, photoNote),
        filters = filters,
        tierLimits = listOf(newLimits, regularLimits),
    )

    fun profile(
        n: Int = 1,
        tier: UserTier = UserTier.REGULAR,
        role: world.xhere.shared.model.AppRole = world.xhere.shared.model.AppRole.USER,
    ) = Profile(
        id = id(n),
        handle = "user_$n",
        tier = tier,
        role = role,
        createdAt = now,
    )

    fun context(
        tier: UserTier = UserTier.REGULAR,
        user: Uuid? = id(1),
        limits: TierLimits? = null,
        dropsLastHour: Int? = null,
        dropsLastDay: Int? = null,
        suspended: Boolean = false,
    ) = PostingContext(
        userId = user,
        tier = tier,
        now = now,
        suspended = suspended,
        dropsLastHour = dropsLastHour,
        dropsLastDay = dropsLastDay,
        limits = limits,
    )

    fun draft(
        category: String = "note",
        title: String = "A note about here",
        body: String? = "Something worth knowing.",
        latitude: Double = 40.7128,
        longitude: Double = -74.0060,
        attributes: JsonObject = JsonObject(emptyMap()),
        tags: List<String> = listOf("Local"),
        revealRadiusM: Int? = null,
        expiresAt: Instant? = null,
        displayAnonymous: Boolean = false,
        media: List<world.xhere.shared.model.MediaUpload> = emptyList(),
        acknowledgeGuidelines: Boolean = false,
    ) = DropDraft(
        categorySlug = category,
        latitude = latitude,
        longitude = longitude,
        title = title,
        body = body,
        attributes = attributes,
        tags = tags,
        revealRadiusM = revealRadiusM,
        expiresAt = expiresAt,
        displayAnonymous = displayAnonymous,
        media = media,
        acknowledgeGuidelines = acknowledgeGuidelines,
    )
}
