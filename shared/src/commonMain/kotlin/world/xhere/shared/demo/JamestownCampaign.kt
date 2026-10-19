package world.xhere.shared.demo

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.campaign.CampaignConfig
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.fake.StoredProgram
import world.xhere.shared.geo.BoundingBox

/**
 * Offline copy of the Jamestown pilot program in `supabase/regions/us/jamestown-ny.yaml`
 * and `supabase/seed/campaigns/jamestown-ny.sql`.
 *
 * The hosted seed starts the clock when it is applied and runs [DURATION_DAYS].
 * This copy keeps that length, and if the original window has already closed it
 * stays open for another [DURATION_DAYS] from [now], so the map card can still
 * be tried. Drops from the demo day stay inside the count because the start
 * does not move. Jamestown is only this config. Another region is another call.
 */
object JamestownCampaign {
    val id: Uuid = Uuid.parse("c0000000-0000-4000-8000-000000000006")
    const val REGION_ID: String = "us-ny-jamestown"
    const val REGION_NAME: String = "Jamestown, New York, USA"
    const val SLUG: String = "map-jamestown"
    const val TITLE: String = "Map Jamestown"
    const val DESCRIPTION: String =
        "Add real drops in Jamestown over 14 days. Public records already on the map do not count."
    const val GOAL_DROPS: Int = 200
    const val DURATION_DAYS: Int = 14
    const val FOUNDING_CAP: Int = 25
    const val LEADERBOARD_SIZE: Int = 8

    /** West, south, east, north. The region box in the Jamestown YAML. */
    val box = BoundingBox(
        minLongitude = -79.32,
        minLatitude = 42.05,
        maxLongitude = -79.18,
        maxLatitude = 42.14,
    )

    /** Demo drops are stamped on this day. The window always includes them. */
    val anchor: Instant = Instant.parse("2026-10-18T00:00:00Z")

    fun startsAt(now: Instant): Instant = anchor

    fun endsAt(now: Instant): Instant {
        val planned = anchor + DURATION_DAYS.days
        val fresh = now + DURATION_DAYS.days
        return if (fresh > planned) fresh else planned
    }

    fun install(store: MemoryStore, now: Instant = Instant.parse("2026-10-19T00:00:00Z")) {
        if (store.programs.any { it.regionId == REGION_ID }) return
        val start = startsAt(now)
        val end = endsAt(now)
        store.programs += StoredProgram(
            regionId = REGION_ID,
            regionName = REGION_NAME,
            box = box,
            foundingCap = FOUNDING_CAP,
            campaign = CampaignConfig(
                id = id,
                regionId = REGION_ID,
                regionName = REGION_NAME,
                slug = SLUG,
                title = TITLE,
                description = DESCRIPTION,
                goalDrops = GOAL_DROPS,
                startsAt = start,
                endsAt = end,
                leaderboardSize = LEADERBOARD_SIZE,
                box = box,
            ),
        )
    }
}
