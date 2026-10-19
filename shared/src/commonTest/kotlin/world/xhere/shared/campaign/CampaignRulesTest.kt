package world.xhere.shared.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.fake.StoredDrop
import world.xhere.shared.data.fake.StoredProgram
import world.xhere.shared.data.fake.countedDrops
import world.xhere.shared.demo.JamestownCampaign
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.rules.CategoryRules

/**
 * The offline copy of founding and campaign rules. The database tests are the authority.
 * Jamestown is one config on the store, not a branch in the rules.
 */
class CampaignRulesTest {
    private val now = Fixtures.now
    private val box = BoundingBox(minLongitude = 9.0, minLatitude = 9.0, maxLongitude = 11.0, maxLatitude = 11.0)
    private val inside = LatLng(10.0, 10.0)
    private val alice = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val bob = Uuid.parse("00000000-0000-0000-0000-0000000000b2")
    private val eve = Uuid.parse("00000000-0000-0000-0000-0000000000e3")

    @Test
    fun theFirstCapPeopleAreOrderedByTheirEarliestDrop() {
        val first = row(alice, now, "Bench")
        val again = row(alice, now + 5.seconds, "Second bench")
        val second = row(bob, now + 1.seconds, "Fountain")
        val third = row(eve, now + 2.seconds, "Mural")
        val founders = CampaignRules.founders(listOf(third, again, first, second), cap = 2)
        assertEquals(listOf(alice, bob), founders.map { it.authorId })
        assertEquals(listOf(1, 2), founders.map { it.rank })
        assertEquals(first.dropId, founders.first().dropId)

        val afterAliceLeaves = CampaignRules.founders(listOf(second, third), cap = 2)
        assertEquals(listOf(bob, eve), afterAliceLeaves.map { it.authorId })
    }

    @Test
    fun openDataPendingExpiredAndOutsideTheBoxDoNotCount() {
        assertTrue(counts(DropSource.USER, DropStatus.ACTIVE, inside))
        assertTrue(!counts(DropSource.OPEN_DATA, DropStatus.ACTIVE, inside))
        assertTrue(!counts(DropSource.USER, DropStatus.PENDING_REVIEW, inside))
        assertTrue(!counts(DropSource.USER, DropStatus.REMOVED, inside))
        assertTrue(!counts(DropSource.USER, DropStatus.ACTIVE, inside, expiresAt = now))
        assertTrue(!counts(DropSource.USER, DropStatus.ACTIVE, LatLng(10.0, 20.0)))
        assertTrue(!counts(DropSource.USER, DropStatus.ACTIVE, inside, createdAt = now - 2.days, windowStart = now - 1.hours))
        assertTrue(!counts(DropSource.USER, DropStatus.ACTIVE, inside, createdAt = now + 14.days, windowEnd = now + 14.days))
        assertTrue(!counts(DropSource.USER, DropStatus.ACTIVE, inside, countsForCredit = false))
    }

    @Test
    fun theBoardIsDisplayNamesAndAHiddenNameStaysInTheTotal() {
        val rows = listOf(
            row(alice, now, "Alice"),
            row(alice, now + 1.seconds, "Alice"),
            row(bob, now, "Bob", show = false),
            row(eve, now, "   "),
        )
        assertEquals(listOf("Alice"), CampaignRules.leaderboard(rows, limit = 8).map { it.displayName })
        val campaign = campaign()
        val progress = CampaignRules.progress(campaign, rows, bob, viewerShows = false, viewerName = "Bob", now = now)
        assertEquals(4, progress!!.dropCount)
        assertEquals(1, progress.myDrops)
        assertTrue(!progress.myListed)
        assertTrue(!progress.showOnLeaderboard)
        assertNull(CampaignRules.progress(campaign, rows, bob, false, "Bob", now = campaign.endsAt))
    }

    private fun counts(
        source: DropSource,
        status: DropStatus,
        point: LatLng,
        expiresAt: kotlin.time.Instant? = null,
        createdAt: kotlin.time.Instant = now,
        windowStart: kotlin.time.Instant? = null,
        windowEnd: kotlin.time.Instant? = null,
        countsForCredit: Boolean = true,
    ) = CampaignRules.counts(
        source = source,
        status = status,
        authorId = alice,
        authorDeleted = false,
        expiresAt = expiresAt,
        createdAt = createdAt,
        point = point,
        region = box,
        windowStart = windowStart,
        windowEnd = windowEnd,
        now = now,
        countsForCredit = countsForCredit,
    )

    private fun row(
        author: Uuid,
        at: kotlin.time.Instant,
        name: String?,
        show: Boolean = true,
    ): CountedDrop = CountedDrop(Uuid.random(), author, at, name, show)

    private fun campaign() = CampaignConfig(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000c1"),
        regionId = "zz-testbox",
        regionName = "Test box",
        slug = "map-testbox",
        title = "Map the box",
        description = "User drops only.",
        goalDrops = 5,
        startsAt = now - 1.hours,
        endsAt = now + 14.days,
        leaderboardSize = 8,
        box = box,
    )
}

class CampaignMemoryTest {
    @Test
    fun aRealDropAwardsFoundingAndAnAnonymousDropKeepsItOffThePin() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        var tick = Fixtures.now
        val clock = { tick }
        store.programs += StoredProgram(
            regionId = "zz-testbox",
            regionName = "Test box",
            box = BoundingBox(9.0, 9.0, 11.0, 11.0),
            foundingCap = 2,
            campaign = CampaignConfig(
                id = Uuid.parse("00000000-0000-0000-0000-0000000000c1"),
                regionId = "zz-testbox",
                regionName = "Test box",
                slug = "map-testbox",
                title = "Map the box",
                description = "User drops only.",
                goalDrops = 5,
                startsAt = Fixtures.now - 1.hours,
                endsAt = Fixtures.now + 14.days,
                leaderboardSize = 2,
                box = BoundingBox(9.0, 9.0, 11.0, 11.0),
            ),
        )
        val alice = InMemoryXHere(store, Fixtures.profile(1).copy(displayName = "Alice"), clock)
        val bob = InMemoryXHere(store, Fixtures.profile(2).copy(displayName = "Bob"), clock)
        val eve = InMemoryXHere(store, Fixtures.profile(3).copy(displayName = "Eve"), clock)
        val blank = InMemoryXHere(store, Fixtures.profile(4).copy(displayName = "  "), clock)

        alice.drops.createDrop(note(10.0, 10.0, "Alice bench"))
        tick += 1.seconds
        bob.drops.createDrop(note(10.1, 10.1, "Bob fountain"))
        tick += 1.seconds
        eve.drops.createDrop(note(10.2, 10.2, "Eve mural"))
        tick += 1.seconds
        assertEquals(listOf("Alice", "Bob"), names(store, clock))
        assertTrue(eve.campaigns.myBadges().badges.isEmpty())

        val quiet = alice.drops.createDrop(note(10.3, 10.3, "Quiet bench", anonymous = true))
        val hiddenPin = alice.drops.dropTeaser(quiet.shortId)
        assertTrue(hiddenPin.authorBadges.isEmpty())
        assertEquals("founding_local", alice.campaigns.myBadges().badges.single().slug)
        val named = alice.drops.dropTeaser(store.drops.values.first { it.title == "Alice bench" }.shortId)
        assertEquals("founding_local", named.authorBadges.single().slug)

        val outside = store.drops.values.first { it.title == "Eve mural" }
        // A pending drop stops counting, and the next person takes the open slot.
        store.drops.values.first { it.title == "Bob fountain" }.status = DropStatus.PENDING_REVIEW
        assertEquals(listOf("Alice", "Eve"), names(store, clock))

        store.drops[Uuid.parse("00000000-0000-0000-0000-0000000000d1")] = StoredDrop(
            id = Uuid.parse("00000000-0000-0000-0000-0000000000d1"),
            shortId = "opendata0001",
            authorId = Uuid.parse("00000000-0000-0000-0000-0000000000d2"),
            authorHandle = "",
            groupSlug = "note",
            category = Fixtures.note,
            title = "Imported hall",
            body = "A public record.",
            attributes = JsonObject(emptyMap()),
            tags = emptyList(),
            trueLocation = LatLng(10.0, 10.0),
            displayLocation = LatLng(10.0, 10.0),
            revealRadiusM = 100,
            status = DropStatus.ACTIVE,
            displayAnonymous = false,
            expiresAt = null,
            createdAt = Fixtures.now,
            media = emptyList(),
            source = DropSource.OPEN_DATA,
        )
        val progress = alice.campaigns.myCampaigns().single()
        assertEquals(3, progress.dropCount)
        assertEquals("Alice", progress.leaderboard.first().displayName)
        assertTrue(progress.leaderboard.none { it.displayName.isBlank() })

        blank.drops.createDrop(note(10.4, 10.4, "No name"))
        assertTrue(alice.campaigns.myCampaigns().single().leaderboard.none { it.displayName.isBlank() })
        alice.campaigns.setShowOnLeaderboard(false)
        val hidden = alice.campaigns.myCampaigns().single()
        assertTrue(hidden.myDrops >= 1)
        assertTrue(!hidden.showOnLeaderboard)
        assertTrue(hidden.leaderboard.none { it.displayName == "Alice" })
        assertEquals(outside.title, "Eve mural")
    }

    @Test
    fun jamestownIsConfigAndTheSevenUserDropsCount() = runTest {
        val store = JamestownDemo.store()
        val clock = { kotlin.time.Instant.parse("2026-10-19T12:00:00Z") }
        val guide = InMemoryXHere(store, JamestownDemo.guide, clock)
        val visitor = InMemoryXHere(store, Fixtures.profile(9).copy(displayName = "Steven"), clock)
        val card = guide.campaigns.campaignsFor(JamestownCampaign.box).single()
        assertEquals("Map Jamestown", card.title)
        assertEquals(200, card.goalDrops)
        assertEquals(7, card.dropCount)
        assertEquals(7, card.myDrops)
        assertEquals(JamestownCampaign.FOUNDING_CAP, 25)
        assertEquals("founding_local", guide.campaigns.myBadges().badges.single().slug)
        assertEquals("Demo guide", card.leaderboard.single().displayName)
        assertEquals(0, visitor.campaigns.myCampaigns().single().myDrops)
        assertTrue(
            visitor.campaigns.campaignsFor(
                BoundingBox(minLongitude = -78.90, minLatitude = 42.86, maxLongitude = -78.85, maxLatitude = 42.90),
            ).isEmpty(),
        )
        assertTrue(store.drops.values.none { it.source == DropSource.OPEN_DATA && it.shortId == "records" && it.authorId == JamestownDemo.guide.id })
    }

    private fun names(store: MemoryStore, clock: () -> kotlin.time.Instant): List<String> {
        val program = store.programs.single()
        return CampaignRules.founders(
            store.countedDrops(program, null, null, clock()),
            program.foundingCap ?: 0,
        ).map { founder -> store.profiles[founder.authorId]?.displayName.orEmpty() }
    }

    private fun note(latitude: Double, longitude: Double, title: String, anonymous: Boolean = false) = CategoryRules.check(
        Fixtures.draft(title = title, latitude = latitude, longitude = longitude, displayAnonymous = anonymous, tags = emptyList()),
        Fixtures.note,
        Fixtures.context(),
        emptyList(),
    ).toRequest(Fixtures.draft(title = title, latitude = latitude, longitude = longitude, displayAnonymous = anonymous, tags = emptyList()))
}
