package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CategoryRules

/**
 * The M6 contract, on the in-memory stand-in for `drop_detail` / `confirm_drop`.
 * pgTAP covers the same rules against Postgres (`08_reveal_payload.test.sql`).
 */
class RevealPayloadTest {
    @Test
    fun outsideTheRevealRadiusTheBodyIsNeverInThePayload() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = client(store, 1)
        val created = author.drops.createDrop(noteRequest())
        val stranger = client(store, 2)
        val far = stranger.drops.dropDetail(created.id, LatLng(created.latitude + 0.01, created.longitude))
        assertTrue(!far.revealed)
        assertNull(far.body)
        assertNull(far.attributes)
        assertNull(far.media)
        assertNull(far.tags)
        val payload = XHereJson.encodeToString(DropCard.serializer(), far)
        assertTrue(!payload.contains("Something worth knowing"), payload)
        assertTrue(!payload.contains("local"), payload)

        val near = stranger.drops.dropDetail(created.id, created.displayLocation)
        assertTrue(near.revealed)
        assertEquals("Something worth knowing.", near.body)
        assertEquals(listOf("local"), near.tags)

        val teaser = author.drops.dropTeaser(created.shortId)
        assertTrue(!teaser.revealed)
        assertNull(teaser.body)
        assertNull(teaser.tags)
    }

    @Test
    fun confirmOutsideRangeIsRejectedAndCountersFollowTheCategory() = runTest {
        val history = Fixtures.note.copy(
            id = Fixtures.id(41),
            slug = "historical_fact",
            label = "Historical fact",
            defaultRevealM = 150,
            maxRevealM = 150,
            defaultTtl = null,
            permanentWhenConfirmed = false,
            confirmationsToConfirm = 3,
            moderationMode = ModerationMode.POST,
        )
        val truck = Fixtures.note.copy(
            id = Fixtures.id(42),
            slug = "food_truck",
            label = "Food truck",
            defaultRevealM = 500,
            maxRevealM = 500,
            defaultTtl = 6.hours,
            maxTtl = 24.hours,
            confirmExtendsTtl = 6.hours,
            permanentWhenConfirmed = false,
            confirmationsToConfirm = 2,
            denialsToHide = 2,
            moderationMode = ModerationMode.POST,
        )
        val taxonomy = Fixtures.taxonomy.copy(categories = Fixtures.taxonomy.categories + history + truck)
        val store = MemoryStore(taxonomy)
        val author = client(store, 1)
        val hist = author.drops.createDrop(noteRequest(category = "historical_fact", title = "Old mill"))
        val note = author.drops.createDrop(noteRequest(title = "Shady bench"))
        val denied = author.drops.createDrop(noteRequest(title = "Free wifi", latitude = 40.72))
        val food = author.drops.createDrop(noteRequest(category = "food_truck", title = "Taco truck", latitude = 40.73))

        val tooFar = assertFailsWith<XHereApiException> {
            client(store, 2).drops.confirmDrop(hist.id, ConfirmationVerdict.STILL_TRUE, LatLng(hist.latitude + 0.01, hist.longitude))
        }
        assertEquals("too_far", tooFar.code)

        assertEquals(DropStatus.ACTIVE, client(store, 2).drops.confirmDrop(hist.id, ConfirmationVerdict.STILL_TRUE, hist.displayLocation).status)
        assertEquals(DropStatus.ACTIVE, client(store, 3).drops.confirmDrop(hist.id, ConfirmationVerdict.STILL_TRUE, hist.displayLocation).status)
        val confirmed = client(store, 4).drops.confirmDrop(hist.id, ConfirmationVerdict.STILL_TRUE, hist.displayLocation)
        assertEquals(DropStatus.CONFIRMED, confirmed.status)
        assertEquals(3, confirmed.confirmCount)
        assertTrue(confirmed.isConfirmed)

        val one = client(store, 2).drops.confirmDrop(note.id, ConfirmationVerdict.STILL_TRUE, note.displayLocation)
        assertEquals(DropStatus.ACTIVE, one.status)
        assertEquals(Fixtures.now + 7.days, one.expiresAt)
        val permanent = client(store, 3).drops.confirmDrop(note.id, ConfirmationVerdict.STILL_TRUE, note.displayLocation)
        assertEquals(DropStatus.CONFIRMED, permanent.status)
        assertNull(permanent.expiresAt)

        assertEquals(DropStatus.ACTIVE, client(store, 2).drops.confirmDrop(denied.id, ConfirmationVerdict.NOT_TRUE, denied.displayLocation).status)
        assertEquals(DropStatus.ACTIVE, client(store, 3).drops.confirmDrop(denied.id, ConfirmationVerdict.NOT_TRUE, denied.displayLocation).status)
        val disputed = client(store, 4).drops.confirmDrop(denied.id, ConfirmationVerdict.NOT_TRUE, denied.displayLocation)
        assertEquals(DropStatus.DISPUTED, disputed.status)
        assertEquals(3, disputed.denyCount)
        val hidden = assertFailsWith<XHereApiException> {
            client(store, 5).drops.confirmDrop(denied.id, ConfirmationVerdict.STILL_TRUE, denied.displayLocation)
        }
        assertEquals("drop_not_found", hidden.code)

        store.drops[food.id]!!.expiresAt = Fixtures.now + 1.hours
        val extended = client(store, 2).drops.confirmDrop(food.id, ConfirmationVerdict.STILL_TRUE, food.displayLocation)
        assertEquals(Fixtures.now + 6.hours, extended.expiresAt)
        store.drops[food.id]!!.expiresAt = Fixtures.now + 1.hours
        val notExtended = client(store, 3).drops.confirmDrop(food.id, ConfirmationVerdict.NOT_TRUE, food.displayLocation)
        assertEquals(Fixtures.now + 1.hours, notExtended.expiresAt)
    }

    @Test
    fun changingYourMindDoesNotSpendAnotherConfirmationSlot() = runTest {
        val tight = Fixtures.taxonomy.copy(
            tierLimits = listOf(Fixtures.newLimits.copy(confirmationsPerHour = 1)),
        )
        val store = MemoryStore(tight)
        val author = client(store, 1, UserTier.REGULAR)
        val first = author.drops.createDrop(noteRequest())
        val second = author.drops.createDrop(noteRequest(title = "Second bench", latitude = 40.72))
        val newbie = client(store, 2, UserTier.NEW)
        newbie.drops.confirmDrop(first.id, ConfirmationVerdict.STILL_TRUE, first.displayLocation)
        val changed = newbie.drops.confirmDrop(first.id, ConfirmationVerdict.NOT_TRUE, first.displayLocation)
        assertEquals(ConfirmationVerdict.NOT_TRUE, changed.verdict)
        assertEquals(1, changed.denyCount)
        val blocked = assertFailsWith<XHereApiException> {
            newbie.drops.confirmDrop(second.id, ConfirmationVerdict.STILL_TRUE, second.displayLocation)
        }
        assertEquals("rate_limited", blocked.code)
    }

    private fun client(store: MemoryStore, n: Int, tier: UserTier = UserTier.REGULAR) =
        InMemoryXHere(store, Fixtures.profile(n, tier), clock = { Fixtures.now })

    private fun noteRequest(
        category: String = "note",
        title: String = "A note about here",
        latitude: Double = 40.7128,
    ) = CategoryRules.check(
        Fixtures.draft(category = category, title = title, latitude = latitude),
        taxonomyCategory(category),
        Fixtures.context(),
        emptyList(),
    ).toRequest(Fixtures.draft(category = category, title = title, latitude = latitude))

    private fun taxonomyCategory(slug: String) = when (slug) {
        "historical_fact" -> Fixtures.note.copy(
            id = Fixtures.id(41),
            slug = "historical_fact",
            defaultRevealM = 150,
            maxRevealM = 150,
            defaultTtl = null,
            permanentWhenConfirmed = false,
            confirmationsToConfirm = 3,
            moderationMode = ModerationMode.POST,
        )
        "food_truck" -> Fixtures.note.copy(
            id = Fixtures.id(42),
            slug = "food_truck",
            defaultRevealM = 500,
            maxRevealM = 500,
            defaultTtl = 6.hours,
            maxTtl = 24.hours,
            confirmExtendsTtl = 6.hours,
            permanentWhenConfirmed = false,
            confirmationsToConfirm = 2,
            denialsToHide = 2,
            moderationMode = ModerationMode.POST,
        )
        else -> Fixtures.note
    }
}
