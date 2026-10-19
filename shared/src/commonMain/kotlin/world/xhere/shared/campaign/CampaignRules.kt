package world.xhere.shared.campaign

import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.LeaderboardEntry

/**
 * The same rules as `private.drop_counts_in_region` and `private.refresh_founders`.
 * The database is the authority. This copy is what the offline app and the tests use.
 *
 * A qualifying drop is a user drop other people can see, inside the region box,
 * whose location check counted (`counts_for_credit`). `open_data` never counts.
 * Founding Local is the first [cap] people by their
 * earliest qualifying drop, for all time. A campaign counts drops inside its window.
 */
object CampaignRules {
    const val FOUNDING_SLUG: String = "founding_local"
    const val FOUNDING_NAME: String = "Founding Local"
    const val FOUNDING_ICON: String = "🌱"
    const val FOUNDING_DESCRIPTION: String = "One of the first people to add a drop in this region."
    const val DEFAULT_CAP: Int = 25

    fun contains(box: BoundingBox, point: LatLng): Boolean =
        point.longitude in box.minLongitude..box.maxLongitude &&
            point.latitude in box.minLatitude..box.maxLatitude

    fun overlaps(region: BoundingBox, view: BoundingBox): Boolean =
        region.maxLongitude >= view.minLongitude &&
            region.minLongitude <= view.maxLongitude &&
            region.maxLatitude >= view.minLatitude &&
            region.minLatitude <= view.maxLatitude

    fun counts(
        source: DropSource,
        status: DropStatus,
        authorId: Uuid?,
        authorDeleted: Boolean,
        expiresAt: Instant?,
        createdAt: Instant,
        point: LatLng,
        region: BoundingBox,
        windowStart: Instant?,
        windowEnd: Instant?,
        now: Instant,
        countsForCredit: Boolean = true,
    ): Boolean {
        if (!countsForCredit) return false
        if (source != DropSource.USER || authorId == null || authorDeleted) return false
        if (status != DropStatus.ACTIVE && status != DropStatus.CONFIRMED) return false
        if (expiresAt != null && expiresAt <= now) return false
        if (windowStart != null && createdAt < windowStart) return false
        if (windowEnd != null && createdAt >= windowEnd) return false
        return contains(region, point)
    }

    /**
     * First [cap] authors. Order is the earliest qualifying drop, then user id.
     * [drops] may include rows that do not qualify; those are skipped.
     */
    fun founders(drops: List<CountedDrop>, cap: Int): List<Founder> {
        if (cap <= 0) return emptyList()
        return drops
            .groupBy { it.authorId }
            .map { (authorId, rows) -> authorId to rows.minWith(compareBy({ it.createdAt }, { it.dropId.toString() })) }
            .sortedWith(compareBy({ it.second.createdAt }, { it.first.toString() }))
            .take(cap)
            .mapIndexed { index, (authorId, first) ->
                Founder(authorId, index + 1, first.createdAt, first.dropId)
            }
    }

    fun leaderboard(drops: List<CountedDrop>, limit: Int): List<LeaderboardEntry> {
        if (limit <= 0) return emptyList()
        return drops
            .filter { it.showOnLeaderboard && !it.displayName.isNullOrBlank() }
            .groupBy { it.authorId }
            .map { (authorId, rows) ->
                Triple(authorId, rows.first().displayName!!.trim(), rows.size)
            }
            .sortedWith(compareByDescending<Triple<Uuid, String, Int>> { it.third }.thenBy { it.second }.thenBy { it.first.toString() })
            .take(limit)
            .map { LeaderboardEntry(it.second, it.third) }
    }

    fun listed(drops: List<CountedDrop>, userId: Uuid, limit: Int, show: Boolean, displayName: String?): Boolean {
        if (!show || displayName.isNullOrBlank() || limit <= 0) return false
        val top = drops
            .filter { it.showOnLeaderboard && !it.displayName.isNullOrBlank() }
            .groupBy { it.authorId }
            .map { (authorId, rows) ->
                Triple(authorId, rows.first().displayName!!.trim(), rows.size)
            }
            .sortedWith(compareByDescending<Triple<Uuid, String, Int>> { it.third }.thenBy { it.second }.thenBy { it.first.toString() })
            .take(limit)
        return top.any { it.first == userId }
    }

    fun progress(
        campaign: CampaignConfig,
        drops: List<CountedDrop>,
        viewerId: Uuid?,
        viewerShows: Boolean,
        viewerName: String?,
        now: Instant,
    ): CampaignProgress? {
        if (!campaign.active) return null
        if (now < campaign.startsAt || now >= campaign.endsAt) return null
        val mine = if (viewerId == null) 0 else drops.count { it.authorId == viewerId }
        val show = if (viewerId == null) true else viewerShows
        return CampaignProgress(
            id = campaign.id,
            regionId = campaign.regionId,
            regionName = campaign.regionName,
            slug = campaign.slug,
            title = campaign.title,
            description = campaign.description,
            goalDrops = campaign.goalDrops,
            dropCount = drops.size,
            myDrops = mine,
            startsAt = campaign.startsAt,
            endsAt = campaign.endsAt,
            leaderboard = leaderboard(drops, campaign.leaderboardSize),
            showOnLeaderboard = show,
            myListed = if (viewerId == null) false else listed(drops, viewerId, campaign.leaderboardSize, show, viewerName),
        )
    }

    fun award(founder: Founder, regionId: String, regionName: String): BadgeAward = BadgeAward(
        slug = FOUNDING_SLUG,
        name = FOUNDING_NAME,
        icon = FOUNDING_ICON,
        description = FOUNDING_DESCRIPTION,
        regionId = regionId,
        regionName = regionName,
        rank = founder.rank,
        awardedAt = founder.awardedAt,
    )
}

data class CountedDrop(
    val dropId: Uuid,
    val authorId: Uuid,
    val createdAt: Instant,
    val displayName: String?,
    val showOnLeaderboard: Boolean,
)

data class Founder(
    val authorId: Uuid,
    val rank: Int,
    val awardedAt: Instant,
    val dropId: Uuid,
)

data class CampaignConfig(
    val id: Uuid,
    val regionId: String,
    val regionName: String,
    val slug: String,
    val title: String,
    val description: String,
    val goalDrops: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val leaderboardSize: Int,
    val active: Boolean = true,
    val box: BoundingBox,
)
