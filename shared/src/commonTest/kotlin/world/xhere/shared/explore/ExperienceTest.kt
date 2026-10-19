package world.xhere.shared.explore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.geo.LatLng
import world.xhere.shared.live.AlertSeverity
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.QuestStepProgress
import world.xhere.shared.model.ThingCard
import world.xhere.shared.model.Walk
import world.xhere.shared.model.WalkStop

class ExperienceTest {
    private val here = LatLng(42.095, -79.240)

    @Test
    fun aDismissalHidesThatRevisionOnly() {
        val walk = sampleWalk(title = "River walk")
        val item = Experiences.walk(walk, here)
        val hidden = setOf(ExperienceDismissal(item.id, item.revision))
        assertTrue(Experiences.isDismissed(item, hidden))
        val edited = Experiences.walk(walk.copy(title = "River walk, revised"), here)
        assertEquals(item.id, edited.id)
        assertFalse(Experiences.isDismissed(edited, hidden))
    }

    @Test
    fun progressDoesNotChangeAWalkRevision() {
        val walk = sampleWalk(title = "River walk")
        val before = Experiences.walk(walk, here)
        val moved = walk.copy(
            started = true,
            stops = walk.stops.mapIndexed { index, stop -> stop.copy(reached = index == 0) },
        )
        val after = Experiences.walk(moved, here)
        assertEquals(before.revision, after.revision)
        assertEquals(1, after.progressCurrent)
        assertTrue(Experiences.isDismissed(after, setOf(ExperienceDismissal(before.id, before.revision))))
    }

    @Test
    fun theHintCountsUndismissedWalksQuestsCampaignsAndAlerts() {
        val walk = Experiences.walk(sampleWalk(title = "River walk"), here)
        val quest = Experiences.quest(sampleQuest())
        val campaign = Experiences.campaign(sampleCampaign())
        val alert = Experiences.alert(sampleAlert())
        val count = Experiences.hintCount(
            walks = listOf(sampleWalk(title = "River walk")),
            quests = listOf(sampleQuest()),
            campaigns = listOf(sampleCampaign()),
            alerts = listOf(sampleAlert()),
            here = here,
            dismissals = setOf(ExperienceDismissal(walk.id, walk.revision)),
        )
        assertEquals(3, count)
        assertTrue(walk.hints && quest.hints && campaign.hints && alert.hints)
        assertFalse(Experiences.timeMachine(here).hints)
    }

    @Test
    fun anEmptyThingListLeavesAPlaceholderAndARealThingReplacesIt() {
        val empty = Experiences.assemble(
            walks = emptyList(),
            quests = emptyList(),
            campaigns = emptyList(),
            alerts = emptyList(),
            things = emptyList(),
            here = here,
        )
        assertEquals(1, empty.count { it.kind == ExperienceKind.NEARBY_THING && it.placeholder })
        assertEquals(1, empty.count { it.kind == ExperienceKind.TIME_CAPSULE && it.placeholder })
        val card = ThingCard(
            id = Uuid.parse("00000000-0000-4000-8000-000000000021"),
            voiceId = "AABBCCDDEEFF",
            title = "Library lion",
            story = "A stone lion by the door.",
            lat = 42.096,
            lon = -79.241,
            url = "https://xhere.world/t/lion",
        )
        val filled = Experiences.assemble(
            walks = emptyList(),
            quests = emptyList(),
            campaigns = emptyList(),
            alerts = emptyList(),
            things = listOf(card),
            here = here,
            heardMeters = mapOf(card.id.toString() to 12.0),
        )
        val thing = filled.single { it.kind == ExperienceKind.NEARBY_THING }
        assertFalse(thing.placeholder)
        assertEquals(12.0, thing.distanceM)
        assertEquals(card.title, thing.title)
        assertNull(thing.durationMin)
    }

    @Test
    fun distanceSortPutsTheNearerWalkFirstAndLeavesUnknownDistancesLast() {
        val near = sampleWalk(title = "Near", latitude = 42.0951, longitude = -79.2401)
        val far = sampleWalk(title = "Far", latitude = 42.2, longitude = -79.4, id = "00000000-0000-4000-8000-000000000032")
        val items = Experiences.sort(
            listOf(Experiences.walk(far, here), Experiences.quest(sampleQuest()), Experiences.walk(near, here)),
            ExperienceSort.DISTANCE,
        )
        assertEquals("Near", items.first().title)
        assertEquals(ExperienceKind.QUEST, items.last().kind)
    }

    private fun sampleWalk(
        title: String,
        latitude: Double = 42.096,
        longitude: Double = -79.241,
        id: String = "00000000-0000-4000-8000-000000000031",
    ) = Walk(
        id = Uuid.parse(id),
        regionId = "region",
        slug = title.lowercase(),
        title = title,
        description = "A short walk.",
        distanceM = 800,
        durationMin = 12,
        stops = listOf(
            WalkStop(
                position = 1,
                title = "First stop",
                summary = "A place.",
                lat = latitude,
                lng = longitude,
                revealRadiusM = 150,
            ),
        ),
    )

    private fun sampleQuest() = QuestProgress(
        id = Uuid.parse("00000000-0000-4000-8000-000000000041"),
        slug = "starter",
        title = "First outing",
        description = "Visit, confirm, and drop.",
        steps = listOf(
            QuestStepProgress(position = 1, kind = "visit", title = "Visit 3 places", targetCount = 3, progress = 1),
        ),
    )

    private fun sampleCampaign() = CampaignProgress(
        id = Uuid.parse("00000000-0000-4000-8000-000000000051"),
        regionId = "region",
        regionName = "A town",
        slug = "map-town",
        title = "Map the town",
        description = "Add places.",
        goalDrops = 200,
        dropCount = 7,
        startsAt = Instant.parse("2026-01-01T00:00:00Z"),
        endsAt = Instant.parse("2026-12-31T00:00:00Z"),
    )

    private fun sampleAlert() = WeatherAlert(
        id = "storm",
        event = "Severe Thunderstorm Warning",
        severity = AlertSeverity.SEVERE,
        expiresAt = null,
        headline = null,
        attribution = "National Weather Service",
        senderName = "NWS",
        sourceUrl = null,
    )
}
