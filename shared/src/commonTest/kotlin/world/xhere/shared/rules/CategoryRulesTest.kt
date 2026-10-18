package world.xhere.shared.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.xhere.shared.Fixtures
import world.xhere.shared.geo.Geo
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.UserTier

class CategoryRulesTest {
    @Test
    fun premoderationFollowsTheModeAndTheExemptTier() {
        assertTrue(!CategoryRules.needsPremoderation(Fixtures.note.copy(moderationMode = ModerationMode.POST), UserTier.NEW))
        assertTrue(CategoryRules.needsPremoderation(Fixtures.note, UserTier.NEW))
        assertTrue(!CategoryRules.needsPremoderation(Fixtures.note, UserTier.REGULAR))
        assertTrue(CategoryRules.needsPremoderation(Fixtures.incident, UserTier.REGULAR))
        assertTrue(!CategoryRules.needsPremoderation(Fixtures.incident, UserTier.TRUSTED))
        assertTrue(!CategoryRules.needsPremoderation(Fixtures.incident, UserTier.GUIDE))
        val everyone = Fixtures.incident.copy(premodExemptTier = null)
        assertTrue(CategoryRules.needsPremoderation(everyone, UserTier.GUIDE))
    }

    @Test
    fun aPlainNotePassesAndNormalizesTags() {
        val checked = CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(), Fixtures.filters)
        assertTrue(checked.isValid, checked.violations.toString())
        assertEquals(listOf("local"), checked.tags)
        assertEquals(DropStatus.ACTIVE, checked.status)
        assertEquals(Fixtures.now + 7.days, checked.expiresAt)
        assertNotNull(checked.displayLocation)
        assertEquals(checked.trueLocation, checked.displayLocation)
    }

    @Test
    fun newUsersPostingANoteWaitForReview() {
        val checked = CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(UserTier.NEW), emptyList())
        assertEquals(DropStatus.PENDING_REVIEW, checked.status)
    }

    @Test
    fun titleBodyLocationAndTagsAreChecked() {
        val checked = CategoryRules.check(
            Fixtures.draft(title = "  ", body = "x".repeat(2001), latitude = 120.0, tags = List(11) { "ok$it" }),
            Fixtures.note,
            Fixtures.context(),
            emptyList(),
        )
        assertEquals(
            setOf("invalid_title", "invalid_body", "invalid_location", "invalid_tags"),
            checked.violations.map { it.code }.toSet(),
        )
        assertNull(checked.trueLocation)
    }

    @Test
    fun emojiInATitleCountsAsOneCharacter() {
        val checked = CategoryRules.check(Fixtures.draft(title = "📍".repeat(120)), Fixtures.note, Fixtures.context(), emptyList())
        assertTrue(checked.violations.none { it.code == "invalid_title" })
        val tooLong = CategoryRules.check(Fixtures.draft(title = "📍".repeat(121)), Fixtures.note, Fixtures.context(), emptyList())
        assertTrue(tooLong.violations.any { it.code == "invalid_title" })
    }

    @Test
    fun blankBodyBecomesNullAndBlankTagsAreDropped() {
        val checked = CategoryRules.check(
            Fixtures.draft(body = "   ", tags = listOf("  ", "Hello", "hello")),
            Fixtures.note,
            Fixtures.context(),
            emptyList(),
        )
        assertTrue(checked.isValid, checked.violations.toString())
        assertNull(checked.body)
        assertEquals(listOf("hello"), checked.tags)
    }

    @Test
    fun incidentRulesBlurThePinAndRejectANewTier() {
        val attributes = buildJsonObject {
            put("occurred_at", "2026-10-18T03:00:00Z")
            put("severity", "low")
            put("ongoing", false)
        }
        val tooNew = CategoryRules.check(
            Fixtures.draft(category = "incident_report", attributes = attributes, acknowledgeGuidelines = true),
            Fixtures.incident,
            Fixtures.context(UserTier.NEW),
            Fixtures.filters,
        )
        assertTrue(tooNew.violations.any { it.code == "tier_too_low" })

        val checked = CategoryRules.check(
            Fixtures.draft(category = "incident_report", attributes = attributes, acknowledgeGuidelines = true),
            Fixtures.incident,
            Fixtures.context(UserTier.REGULAR),
            Fixtures.filters,
        )
        assertTrue(checked.isValid, checked.violations.toString())
        assertEquals(DropStatus.PENDING_REVIEW, checked.status)
        val display = assertNotNull(checked.displayLocation)
        val truth = assertNotNull(checked.trueLocation)
        assertTrue(Geo.distanceMeters(truth, display) > 1.0)
        assertEquals(Geo.fuzz(truth, 100), display)
    }

    @Test
    fun sensitivePostsNeedTheGuidelinesAndPeopleAreNotDescribed() {
        val attributes = buildJsonObject { put("occurred_at", "2026-10-18T03:00:00Z") }
        val skipped = CategoryRules.check(
            Fixtures.draft(category = "incident_report", attributes = attributes),
            Fixtures.incident,
            Fixtures.context(),
            Fixtures.filters,
        )
        assertTrue(skipped.violations.any { it.code == "guidelines_not_acknowledged" && it.hint == Fixtures.incident.postingNotice })

        val described = CategoryRules.check(
            Fixtures.draft(
                category = "incident_report",
                body = "a tall man broke the window",
                attributes = attributes,
                acknowledgeGuidelines = true,
            ),
            Fixtures.incident,
            Fixtures.context(),
            Fixtures.filters,
        )
        val hit = described.violations.first { it.code == "content_not_allowed" }
        assertEquals("person_descriptors", hit.detail)
    }

    @Test
    fun salesRejectAnonymousDisplayAndPhoneNumbers() {
        val checked = CategoryRules.check(
            Fixtures.draft(category = "yard_sale", body = "text me 212-555-1212", displayAnonymous = true),
            Fixtures.yardSale,
            Fixtures.context(),
            Fixtures.filters,
        )
        assertTrue(checked.violations.any { it.code == "anonymous_not_allowed" })
        assertTrue(checked.violations.any { it.code == "content_not_allowed" && it.detail == "contact_info" })
    }

    @Test
    fun revealRadiusCannotWidenPastTheCategory() {
        val checked = CategoryRules.check(
            Fixtures.draft(revealRadiusM = 500),
            Fixtures.note,
            Fixtures.context(),
            emptyList(),
        )
        val violation = checked.violations.first { it.code == "invalid_reveal_radius" }
        assertEquals("allowed 10..100 m", violation.detail)
    }

    @Test
    fun incidentRevealCannotBeTighterThanTheBlur() {
        val attributes = buildJsonObject { put("occurred_at", "2026-10-18T03:00:00Z") }
        val checked = CategoryRules.check(
            Fixtures.draft(
                category = "incident_report",
                revealRadiusM = 50,
                attributes = attributes,
                acknowledgeGuidelines = true,
            ),
            Fixtures.incident,
            Fixtures.context(),
            Fixtures.filters,
        )
        assertTrue(checked.violations.any { it.code == "invalid_reveal_radius" && it.detail == "allowed 100..2000 m" })
    }

    @Test
    fun eventsNeedOrderedTimesAndNewUsersCannotPostAYearOut() {
        val backwards = buildJsonObject {
            put("starts_at", "2026-11-02T18:00:00Z")
            put("ends_at", "2026-11-01T18:00:00Z")
        }
        val bad = CategoryRules.check(
            Fixtures.draft(category = "event", attributes = backwards),
            Fixtures.event,
            Fixtures.context(),
            emptyList(),
        )
        assertTrue(bad.violations.any { it.detail == "ends_at is before starts_at" })

        val far = buildJsonObject {
            put("starts_at", "2027-06-01T18:00:00Z")
            put("ends_at", "2027-06-02T18:00:00Z")
        }
        val newbie = CategoryRules.check(
            Fixtures.draft(category = "event", attributes = far),
            Fixtures.event,
            Fixtures.context(UserTier.NEW),
            emptyList(),
        )
        assertTrue(newbie.violations.any { it.code == "expires_too_far" })

        val trusted = CategoryRules.check(
            Fixtures.draft(category = "event", attributes = far),
            Fixtures.event,
            Fixtures.context(UserTier.TRUSTED),
            emptyList(),
        )
        assertTrue(trusted.isValid, trusted.violations.toString())
        assertEquals(Instant.parse("2027-06-02T18:00:00Z"), trusted.expiresAt)
    }

    @Test
    fun explicitExpiryInThePastOrPastTheCapIsRejectedAndADefaultIsClamped() {
        val past = CategoryRules.check(
            Fixtures.draft(expiresAt = Fixtures.now - 1.hours),
            Fixtures.note,
            Fixtures.context(),
            emptyList(),
        )
        assertTrue(past.violations.any { it.code == "expires_in_past" })

        val tooFar = CategoryRules.check(
            Fixtures.draft(category = "yard_sale", expiresAt = Fixtures.now + 30.days),
            Fixtures.yardSale,
            Fixtures.context(),
            Fixtures.filters,
        )
        assertTrue(tooFar.violations.any { it.code == "expires_too_far" })

        val hugeDefault = Fixtures.note.copy(defaultTtl = 30.days, maxTtl = 7.days)
        val clamped = CategoryRules.check(Fixtures.draft(), hugeDefault, Fixtures.context(), emptyList())
        assertTrue(clamped.isValid, clamped.violations.toString())
        assertEquals(Fixtures.now + 7.days, clamped.expiresAt)
    }

    @Test
    fun occurredAtCannotBeInTheFutureOrOlderThanTheLifetime() {
        val future = buildJsonObject { put("occurred_at", "2026-10-18T05:00:00Z") }
        val tooSoon = CategoryRules.check(
            Fixtures.draft(category = "incident_report", attributes = future, acknowledgeGuidelines = true),
            Fixtures.incident,
            Fixtures.context(),
            Fixtures.filters,
        )
        assertTrue(tooSoon.violations.any { it.detail == "occurred_at is in the future" })

        val old = buildJsonObject { put("occurred_at", "2026-10-01T00:00:00Z") }
        val tooOld = CategoryRules.check(
            Fixtures.draft(category = "incident_report", attributes = old, acknowledgeGuidelines = true),
            Fixtures.incident,
            Fixtures.context(),
            Fixtures.filters,
        )
        assertTrue(tooOld.violations.any { it.detail?.contains("older than") == true })
    }

    @Test
    fun photoAndMediaPathAreChecked() {
        val missing = CategoryRules.check(Fixtures.draft(category = "photo_note"), Fixtures.photoNote, Fixtures.context(user = Fixtures.id(7)), emptyList())
        assertTrue(missing.violations.any { it.code == "photo_required" })

        val badPath = CategoryRules.check(
            Fixtures.draft(
                category = "photo_note",
                media = listOf(MediaUpload(storagePath = "other/pic.jpg", mediaKind = MediaKind.IMAGE, mimeType = "image/jpeg")),
            ),
            Fixtures.photoNote,
            Fixtures.context(user = Fixtures.id(7)),
            emptyList(),
        )
        assertTrue(badPath.violations.any { it.code == "invalid_media" })

        val good = CategoryRules.check(
            Fixtures.draft(
                category = "photo_note",
                media = listOf(
                    MediaUpload(
                        storagePath = "${Fixtures.id(7)}/pic.jpg",
                        mediaKind = MediaKind.IMAGE,
                        mimeType = "image/jpeg",
                        byteSize = 10,
                        width = 20,
                        height = 20,
                    ),
                ),
            ),
            Fixtures.photoNote,
            Fixtures.context(user = Fixtures.id(7), limits = Fixtures.regularLimits),
            emptyList(),
        )
        assertTrue(good.isValid, good.violations.toString())
    }

    @Test
    fun rateLimitAndSuspensionAndSignedOut() {
        val limited = CategoryRules.check(
            Fixtures.draft(),
            Fixtures.note,
            Fixtures.context(limits = Fixtures.newLimits, dropsLastHour = 3, dropsLastDay = 3),
            emptyList(),
        )
        assertTrue(limited.violations.any { it.code == "rate_limited" })

        val suspended = CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(suspended = true), emptyList())
        assertTrue(suspended.violations.any { it.code == "account_suspended" })

        val signedOut = CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(user = null), emptyList())
        assertTrue(signedOut.violations.any { it.code == "not_authenticated" })
    }

    @Test
    fun confirmationBarsAndRange() {
        assertEquals(
            DropStatus.CONFIRMED,
            CategoryRules.statusAfterConfirmations(DropStatus.ACTIVE, 2, 0, Fixtures.note),
        )
        assertEquals(
            DropStatus.DISPUTED,
            CategoryRules.statusAfterConfirmations(DropStatus.ACTIVE, 1, 3, Fixtures.note),
        )
        assertEquals(
            DropStatus.REMOVED,
            CategoryRules.statusAfterConfirmations(DropStatus.REMOVED, 5, 0, Fixtures.note),
        )
        assertNull(
            CategoryRules.expiryAfterConfirmations(DropStatus.ACTIVE, Fixtures.now + 7.days, 2, 0, Fixtures.note),
        )
        val tooFar = CategoryRules.checkConfirmation(80.0, revealRadiusM = 40, note = null, isOwnDrop = false)
        assertTrue(tooFar.any { it.code == "too_far" })
        val own = CategoryRules.checkConfirmation(1.0, 100, note = "x".repeat(281), isOwnDrop = true)
        assertEquals(setOf("cannot_confirm_own_drop", "invalid_note"), own.map { it.code }.toSet())
    }

    @Test
    fun inactiveCategoryIsNotFound() {
        val checked = CategoryRules.check(Fixtures.draft(), Fixtures.note.copy(active = false), Fixtures.context(), emptyList())
        assertTrue(checked.violations.any { it.code == "category_not_found" })
    }

    @Test
    fun intervalsRoundTripTheSeedSpellings() {
        assertEquals(7.days, PgInterval.parse("7 days"))
        assertEquals(6.hours, PgInterval.parse("6 hours"))
        assertEquals(72.hours, PgInterval.parse("72 hours"))
        assertEquals(1095.days, PgInterval.parse("3 years"))
        assertEquals(6.hours, PgInterval.parse("06:00:00"))
        assertEquals(30.days, PgInterval.parse("1 mon"))
        assertEquals("7 days", PgInterval.format(7.days))
        assertEquals("6 hours", PgInterval.format(6.hours))
    }
}
