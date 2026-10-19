package world.xhere.shared.explore

import world.xhere.shared.capsule.Capsules
import world.xhere.shared.civic.Civic
import world.xhere.shared.civic.CivicItem
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.ThingCard
import world.xhere.shared.model.Walk
import world.xhere.shared.walks.WalkGuide

/**
 * One discoverable experience. Walks, quests, campaigns, alerts, Time Machine,
 * and objects with a voice all become the same card. A new kind adds a value
 * here and a factory below. The phone does not special-case a city.
 *
 * [revision] is the authored content. Progress, a leaderboard, and a heard
 * count are not part of it, so a dismissal stays put while those move.
 * A new id, or a new revision, is a different item and can show once.
 */
enum class ExperienceKind {
    WALK,
    QUEST,
    CAMPAIGN,
    LIVE_ALERT,
    TIME_MACHINE,
    NEARBY_THING,
    TIME_CAPSULE,
    TOWN_HALL,
}

enum class ExperienceSort { DISTANCE, RELEVANCE }

/** What a tap should do. Placeholders do nothing until a feed exists. */
enum class ExperienceCta { START, FOCUS, TIME_MACHINE, NONE }

data class Experience(
    val id: String,
    val kind: ExperienceKind,
    val title: String,
    val summary: String,
    val distanceM: Double?,
    val durationMin: Int?,
    val progressCurrent: Int?,
    val progressTotal: Int?,
    val revision: String,
    val anchor: LatLng?,
    val cta: ExperienceCta,
    /** The map hint counts these. Placeholders and Time Machine do not. */
    val hints: Boolean,
    val placeholder: Boolean = false,
) {
    val progressFraction: Float?
        get() {
            val total = progressTotal
            val current = progressCurrent
            if (total == null || current == null || total <= 0) return null
            return (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/** A local dismissal. The same id with a different [revision] is not this row. */
data class ExperienceDismissal(val id: String, val revision: String)

/**
 * Builds the catalog from the reads the app already makes.
 * [things] come from `my_things` and from whatever the phone has heard.
 * An empty thing list leaves one placeholder. An empty capsule list leaves
 * one placeholder. Real capsules replace it. The summary is the unlock
 * condition, never the story.
 */
object Experiences {
    const val TIME_MACHINE_ID: String = "experience:time-machine"
    const val THING_PLACEHOLDER_ID: String = "experience:nearby-thing"
    const val CAPSULE_PLACEHOLDER_ID: String = "experience:time-capsule"

    fun isDismissed(item: Experience, dismissals: Set<ExperienceDismissal>): Boolean {
        if (item.placeholder || item.kind == ExperienceKind.LIVE_ALERT) return false
        return dismissals.any { it.id == item.id && it.revision == item.revision }
    }

    fun hintCount(
        walks: List<Walk>,
        quests: List<QuestProgress>,
        campaigns: List<CampaignProgress>,
        alerts: List<WeatherAlert>,
        here: LatLng?,
        dismissals: Set<ExperienceDismissal>,
    ): Int {
        val items = walks.map { walk(it, here) } +
            quests.map(::quest) +
            campaigns.map(::campaign) +
            alerts.map(::alert)
        return items.count { it.hints && !isDismissed(it, dismissals) }
    }

    fun assemble(
        walks: List<Walk>,
        quests: List<QuestProgress>,
        campaigns: List<CampaignProgress>,
        alerts: List<WeatherAlert>,
        things: List<ThingCard>,
        here: LatLng?,
        heardMeters: Map<String, Double> = emptyMap(),
        capsules: List<DropCard> = emptyList(),
        meetings: List<CivicItem> = emptyList(),
    ): List<Experience> = buildList {
        walks.forEach { add(walk(it, here)) }
        quests.forEach { add(quest(it)) }
        campaigns.forEach { add(campaign(it)) }
        alerts.forEach { add(alert(it)) }
        add(timeMachine(here))
        addAll(thingCards(things, here, heardMeters))
        addAll(capsuleCards(capsules, here))
        meetings.forEach { add(meeting(it, here)) }
    }

    fun sort(items: List<Experience>, mode: ExperienceSort): List<Experience> = when (mode) {
        ExperienceSort.DISTANCE -> items.sortedWith(distanceOrder)
        ExperienceSort.RELEVANCE -> items.sortedWith(relevanceOrder)
    }

    fun walk(walk: Walk, here: LatLng?): Experience {
        val stops = WalkGuide.ordered(walk)
        val next = if (walk.started) WalkGuide.nextStop(walk) else stops.firstOrNull()
        val anchor = next?.location
        val distance = when {
            here == null || anchor == null -> null
            else -> Geo.distanceMeters(here, anchor)
        }
        val done = stops.count { it.reached }
        return Experience(
            id = walk.id.toString(),
            kind = ExperienceKind.WALK,
            title = walk.title,
            summary = walk.description,
            distanceM = distance,
            durationMin = walk.durationMin,
            progressCurrent = done,
            progressTotal = stops.size,
            revision = walkRevision(walk),
            anchor = anchor,
            cta = if (!walk.started && !walk.completed) ExperienceCta.START else ExperienceCta.FOCUS,
            hints = true,
        )
    }

    fun quest(quest: QuestProgress): Experience {
        val steps = quest.steps.sortedBy { it.position }
        val current = steps.sumOf { it.progress.coerceAtMost(it.targetCount) }
        val total = steps.sumOf { it.targetCount }
        return Experience(
            id = quest.id.toString(),
            kind = ExperienceKind.QUEST,
            title = quest.title,
            summary = quest.description,
            distanceM = null,
            durationMin = null,
            progressCurrent = current,
            progressTotal = total,
            revision = questRevision(quest),
            anchor = null,
            cta = ExperienceCta.FOCUS,
            hints = true,
        )
    }

    fun campaign(campaign: CampaignProgress): Experience = Experience(
        id = campaign.id.toString(),
        kind = ExperienceKind.CAMPAIGN,
        title = campaign.title,
        summary = campaign.description,
        distanceM = null,
        durationMin = null,
        progressCurrent = campaign.dropCount,
        progressTotal = campaign.goalDrops,
        revision = campaignRevision(campaign),
        anchor = null,
        cta = ExperienceCta.FOCUS,
        hints = true,
    )

    fun alert(alert: WeatherAlert): Experience = Experience(
        id = "alert:${alert.id}",
        kind = ExperienceKind.LIVE_ALERT,
        title = alert.event,
        summary = alert.headline?.takeIf { it.isNotBlank() } ?: alert.attribution,
        distanceM = 0.0,
        durationMin = null,
        progressCurrent = null,
        progressTotal = null,
        revision = listOf(alert.id, alert.event, alert.severity.name, alert.headline.orEmpty()).joinToString("|"),
        anchor = null,
        cta = ExperienceCta.FOCUS,
        hints = true,
    )

    fun thing(card: ThingCard, here: LatLng?, heardMeters: Double? = null): Experience {
        val anchor = if (card.lat != null && card.lon != null) LatLng(card.lat, card.lon) else null
        val distance = heardMeters ?: if (here != null && anchor != null) Geo.distanceMeters(here, anchor) else null
        return Experience(
            id = "thing:${card.id}",
            kind = ExperienceKind.NEARBY_THING,
            title = card.title,
            summary = card.story,
            distanceM = distance,
            durationMin = null,
            progressCurrent = null,
            progressTotal = null,
            revision = thingRevision(card),
            anchor = anchor,
            cta = ExperienceCta.FOCUS,
            hints = false,
        )
    }

    fun timeMachine(here: LatLng?): Experience = Experience(
        id = TIME_MACHINE_ID,
        kind = ExperienceKind.TIME_MACHINE,
        title = "Time Machine",
        summary = "Older maps and photos for this place, when a collection has them.",
        distanceM = null,
        durationMin = null,
        progressCurrent = null,
        progressTotal = null,
        revision = "time-machine",
        anchor = here,
        cta = ExperienceCta.TIME_MACHINE,
        hints = false,
    )

    fun thingCards(
        cards: List<ThingCard>,
        here: LatLng?,
        heardMeters: Map<String, Double> = emptyMap(),
    ): List<Experience> {
        if (cards.isEmpty()) return listOf(thingPlaceholder())
        return cards.distinctBy { it.id }.map { card ->
            thing(card, here, heardMeters[card.id.toString()])
        }
    }

    fun thingPlaceholder(): Experience = Experience(
        id = THING_PLACEHOLDER_ID,
        kind = ExperienceKind.NEARBY_THING,
        title = "Things nearby",
        summary = "Objects with a voice show up here once this phone hears one, or you give one a voice.",
        distanceM = null,
        durationMin = null,
        progressCurrent = null,
        progressTotal = null,
        revision = "placeholder:thing",
        anchor = null,
        cta = ExperienceCta.NONE,
        hints = false,
        placeholder = true,
    )

    fun capsule(card: DropCard, here: LatLng?): Experience {
        val anchor = card.displayLocation
        val distance = if (here != null) Geo.distanceMeters(here, anchor) else card.distanceM
        return Experience(
            id = "capsule:${card.id}",
            kind = ExperienceKind.TIME_CAPSULE,
            title = card.title,
            summary = Capsules.condition(card.capsuleMode, card.capsuleUnlockAt, card.capsuleOpenedAt),
            distanceM = distance,
            durationMin = null,
            progressCurrent = null,
            progressTotal = null,
            revision = listOf(
                card.title,
                card.capsuleMode.orEmpty(),
                card.capsuleUnlockAt?.toString().orEmpty(),
                card.capsuleLocked.toString(),
            ).joinToString("|"),
            anchor = anchor,
            cta = ExperienceCta.FOCUS,
            hints = false,
        )
    }

    fun capsuleCards(cards: List<DropCard>, here: LatLng?): List<Experience> {
        val real = cards.filter { it.categorySlug == "time_capsule" || it.capsuleMode != null }.distinctBy { it.id }
        if (real.isEmpty()) return listOf(timeCapsulePlaceholder())
        return real.map { capsule(it, here) }
    }

    /** A published meeting or notice. It does not count toward the map hint. */
    fun meeting(item: CivicItem, here: LatLng?): Experience {
        val anchor = item.location
        val distance = when {
            here != null && anchor != null -> Geo.distanceMeters(here, anchor)
            else -> item.distanceM?.toDouble()
        }
        val summary = listOfNotNull(
            item.occursOn,
            item.bodyName,
            item.addressText,
            item.attribution.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        return Experience(
            id = "civic:${item.id}",
            kind = ExperienceKind.TOWN_HALL,
            title = "${Civic.icon(item.itemType)} ${item.title}",
            summary = summary.ifBlank { item.summary ?: item.attribution },
            distanceM = distance,
            durationMin = null,
            progressCurrent = null,
            progressTotal = null,
            revision = listOf(item.id.toString(), item.title, item.occursOn.orEmpty(), item.sourceUrl).joinToString("|"),
            anchor = anchor,
            cta = if (anchor != null) ExperienceCta.FOCUS else ExperienceCta.NONE,
            hints = false,
        )
    }

    fun timeCapsulePlaceholder(): Experience = Experience(
        id = CAPSULE_PLACEHOLDER_ID,
        kind = ExperienceKind.TIME_CAPSULE,
        title = "Time capsules",
        summary = "A note left for later at a place will show up here.",
        distanceM = null,
        durationMin = null,
        progressCurrent = null,
        progressTotal = null,
        revision = "placeholder:capsule",
        anchor = null,
        cta = ExperienceCta.NONE,
        hints = false,
        placeholder = true,
    )

    private val distanceOrder = compareBy<Experience> { it.distanceM == null }
        .thenBy { it.distanceM ?: Double.MAX_VALUE }
        .thenBy { it.title }

    private val relevanceOrder = compareBy<Experience> { relevance(it) }
        .thenBy { it.distanceM == null }
        .thenBy { it.distanceM ?: Double.MAX_VALUE }
        .thenBy { it.title }

    private fun relevance(item: Experience): Int = when (item.kind) {
        ExperienceKind.LIVE_ALERT -> 0
        ExperienceKind.WALK -> if ((item.progressCurrent ?: 0) > 0 && item.cta == ExperienceCta.FOCUS) 1 else 3
        ExperienceKind.QUEST -> 2
        ExperienceKind.CAMPAIGN -> 4
        ExperienceKind.TIME_MACHINE -> 5
        ExperienceKind.NEARBY_THING -> 6
        ExperienceKind.TIME_CAPSULE -> 7
        ExperienceKind.TOWN_HALL -> 4
    }

    private fun walkRevision(walk: Walk): String = listOf(
        walk.slug,
        walk.title,
        walk.description,
        walk.distanceM.toString(),
        walk.durationMin.toString(),
        walk.stops.sortedBy { it.position }.joinToString(";") { stop ->
            "${stop.position}:${stop.title}:${stop.summary.orEmpty()}"
        },
    ).joinToString("|")

    private fun questRevision(quest: QuestProgress): String = listOf(
        quest.slug,
        quest.title,
        quest.description,
        quest.steps.sortedBy { it.position }.joinToString(";") { step ->
            "${step.position}:${step.kind}:${step.title}:${step.targetCount}"
        },
    ).joinToString("|")

    private fun campaignRevision(campaign: CampaignProgress): String = listOf(
        campaign.slug,
        campaign.title,
        campaign.description,
        campaign.goalDrops.toString(),
        campaign.endsAt.toString(),
    ).joinToString("|")

    private fun thingRevision(card: ThingCard): String = listOf(
        card.title,
        card.story,
        card.placeName.orEmpty(),
        card.visibility,
    ).joinToString("|")
}
