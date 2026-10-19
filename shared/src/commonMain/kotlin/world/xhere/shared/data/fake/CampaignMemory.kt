package world.xhere.shared.data.fake

import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.campaign.CampaignConfig
import world.xhere.shared.campaign.CampaignRules
import world.xhere.shared.campaign.CountedDrop
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.Profile

/** One region's founding cap and optional live campaign. Config, not a city hardcoded in the rules. */
data class StoredProgram(
    val regionId: String,
    val regionName: String,
    val box: BoundingBox,
    val foundingCap: Int?,
    val campaign: CampaignConfig?,
)

fun MemoryStore.displayNameOf(userId: Uuid): String? = profiles[userId]?.displayName

fun MemoryStore.showsOnLeaderboard(userId: Uuid): Boolean =
    userId !in leaderboardHidden && userId !in deleted

fun MemoryStore.countedDrops(
    program: StoredProgram,
    windowStart: Instant?,
    windowEnd: Instant?,
    now: Instant,
): List<CountedDrop> = drops.values.mapNotNull { drop ->
    val authorId = drop.authorId
    val counts = CampaignRules.counts(
        source = drop.source,
        status = drop.status,
        authorId = authorId,
        authorDeleted = authorId in deleted,
        expiresAt = drop.expiresAt,
        createdAt = drop.createdAt,
        point = drop.trueLocation,
        region = program.box,
        windowStart = windowStart,
        windowEnd = windowEnd,
        now = now,
    )
    if (!counts) return@mapNotNull null
    CountedDrop(
        dropId = drop.id,
        authorId = authorId,
        createdAt = drop.createdAt,
        displayName = displayNameOf(authorId),
        showOnLeaderboard = showsOnLeaderboard(authorId),
    )
}

/** Founding awards for one author, limited to regions that contain [point]. Empty when the drop is anonymous. */
fun MemoryStore.awardsFor(authorId: Uuid?, point: LatLng, anonymous: Boolean, now: Instant): List<BadgeAward> {
    if (authorId == null || anonymous) return emptyList()
    return programs.mapNotNull { program ->
        val cap = program.foundingCap ?: return@mapNotNull null
        if (!CampaignRules.contains(program.box, point)) return@mapNotNull null
        val founder = CampaignRules.founders(countedDrops(program, null, null, now), cap)
            .firstOrNull { it.authorId == authorId }
            ?: return@mapNotNull null
        CampaignRules.award(founder, program.regionId, program.regionName)
    }
}

fun MemoryStore.badgesOf(userId: Uuid, now: Instant): List<BadgeAward> =
    programs.flatMap { program ->
        val cap = program.foundingCap ?: return@flatMap emptyList()
        CampaignRules.founders(countedDrops(program, null, null, now), cap)
            .filter { it.authorId == userId }
            .map { CampaignRules.award(it, program.regionId, program.regionName) }
    }

fun MemoryStore.campaignProgress(campaign: CampaignConfig, viewer: Profile?, now: Instant): CampaignProgress? {
    val program = programs.firstOrNull { it.regionId == campaign.regionId } ?: return null
    val rows = countedDrops(program, campaign.startsAt, campaign.endsAt, now)
    return CampaignRules.progress(
        campaign = campaign,
        drops = rows,
        viewerId = viewer?.id,
        viewerShows = viewer?.let { showsOnLeaderboard(it.id) } ?: true,
        viewerName = viewer?.displayName,
        now = now,
    )
}

fun MemoryStore.campaignsFor(box: BoundingBox, viewer: Profile?, now: Instant): List<CampaignProgress> =
    programs.mapNotNull { program ->
        val campaign = program.campaign ?: return@mapNotNull null
        if (!CampaignRules.overlaps(program.box, box)) return@mapNotNull null
        campaignProgress(campaign, viewer, now)
    }.sortedWith(compareBy({ it.endsAt }, { it.slug }))

fun MemoryStore.liveCampaigns(viewer: Profile?, now: Instant): List<CampaignProgress> =
    programs.mapNotNull { program ->
        val campaign = program.campaign ?: return@mapNotNull null
        campaignProgress(campaign, viewer, now)
    }.sortedWith(compareBy({ it.endsAt }, { it.slug }))
