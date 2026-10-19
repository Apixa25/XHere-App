package world.xhere.shared.data.fake

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.capsule.Capsules
import world.xhere.shared.data.PlaceDeskRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationAction
import world.xhere.shared.integrity.LocationDecision
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.AnnouncementPosted
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.CapsuleCreated
import world.xhere.shared.model.CapsuleRequest
import world.xhere.shared.model.ClaimQueueItem
import world.xhere.shared.model.ClaimTicket
import world.xhere.shared.model.DropMedia
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.OfficialPlace
import world.xhere.shared.model.OwnerReply
import world.xhere.shared.model.PlaceCreated
import world.xhere.shared.model.PlaceHours
import world.xhere.shared.model.PlaceMembership
import world.xhere.shared.model.PostcardStub
import world.xhere.shared.model.Profile
import world.xhere.shared.place.PlaceClaims
import world.xhere.shared.rules.CategoryRules
import world.xhere.shared.rules.DropDraft
import world.xhere.shared.rules.PostingContext
import world.xhere.shared.voice.VoiceClips

/**
 * In-memory claimed places and time capsules. The story stays off the drop
 * until [release] copies it. Same distances as the SQL: 50 m to claim, 30 m
 * to open, 80 m for the official block.
 */
class MemoryPlaceDesk(
    private val store: MemoryStore,
    private val viewer: () -> Profile?,
    private val clock: () -> Instant,
    private val claimEmail: () -> String?,
    private val decide: (Uuid, LocationAction, LatLng, LocationSignal?) -> LocationDecision,
    private val flag: (Uuid, LocationAction, Uuid?, List<String>) -> Unit,
    private val onOpened: (StoredDrop) -> Unit,
) : PlaceDeskRepository {
    override suspend fun createUserPlace(name: String, point: LatLng, website: String?, signal: LocationSignal?): PlaceCreated {
        val profile = requireProfile()
        val trimmed = name.trim()
        if (trimmed.length !in 1..120) fail("invalid_title")
        if (!Geo.isValid(point)) fail("invalid_location")
        val host = websiteHostOrNull(website)
        val category = store.taxonomy.category("place") ?: fail("category_not_found")
        val decision = decide(profile.id, LocationAction.CREATE_DROP, point, signal)
        if (!decision.countsForCredit) flag(profile.id, LocationAction.CREATE_DROP, null, decision.reasons)
        val drop = storeDrop(
            profile = profile,
            categorySlug = category.slug,
            title = trimmed,
            body = null,
            point = point,
            source = DropSource.USER,
            expiresAt = null,
        )
        if (!decision.countsForCredit) {
            drop.countsForCredit = false
            drop.locationTrust = "reduced"
        }
        val place = StoredPlace(
            id = Uuid.random(),
            anchorDropId = drop.id,
            name = trimmed,
            websiteUrl = if (host == null) null else website?.trim(),
            websiteDomain = host,
            geog = point,
            kind = "user",
            createdBy = profile.id,
        )
        store.claimedPlaces += place
        drop.placeId = place.id
        return PlaceCreated(place.id, drop.id, drop.shortId)
    }

    override suspend fun startClaim(anchorDropId: Uuid, website: String?, point: LatLng, signal: LocationSignal?): ClaimTicket {
        val profile = requireProfile()
        val drop = store.drops[anchorDropId] ?: fail("drop_not_found", XHereApiException.SQL_NOT_FOUND)
        val placeCategory = drop.category.slug == "place"
        if (drop.source != DropSource.OPEN_DATA && !placeCategory) fail("not_a_place")
        if (!Geo.isValid(point)) fail("invalid_location")
        val distance = Geo.distanceMeters(point, drop.trueLocation)
        if (distance > PlaceClaims.ONSITE_M) fail("too_far")
        val decision = decide(profile.id, LocationAction.CLAIM_PLACE, point, signal)
        if (!decision.countsForCredit) {
            flag(profile.id, LocationAction.CLAIM_PLACE, drop.id, decision.reasons)
            fail("location_not_trusted")
        }
        val host = websiteHostOrNull(website)
        var place = store.claimedPlaces.firstOrNull { it.anchorDropId == drop.id }
        if (place == null) {
            place = StoredPlace(
                id = Uuid.random(),
                anchorDropId = drop.id,
                name = drop.title,
                websiteUrl = if (host == null) null else website?.trim(),
                websiteDomain = host,
                geog = drop.trueLocation,
                kind = if (drop.source == DropSource.OPEN_DATA) "open_data" else "user",
                createdBy = profile.id,
            )
            store.claimedPlaces += place
            drop.placeId = place.id
        } else if (host != null && place.websiteDomain == null) {
            place.websiteUrl = website?.trim()
            place.websiteDomain = host
        }
        if (openClaim(place.id) != null) fail("place_already_claimed")
        val team = StoredTeam(Uuid.random(), place.id, PlaceClaims.FREE_PLAN, "forming")
        store.placeTeams += team
        store.placeMembers += StoredMember(team.id, profile.id, "owner", "pending")
        val claim = StoredClaim(
            id = Uuid.random(),
            placeId = place.id,
            teamId = team.id,
            claimantId = profile.id,
            status = "pending_proof",
            emailDomain = place.websiteDomain,
            createdAt = clock(),
        )
        store.placeClaims += claim
        return ticket(claim, "pending_proof")
    }

    override suspend fun proveEmail(claimId: Uuid): ClaimTicket {
        val profile = requireProfile()
        val claim = claimFor(claimId, profile.id)
        val place = place(claim.placeId)
        val domain = PlaceClaims.emailDomain(claimEmail())
        if (place.websiteDomain == null || domain == null || PlaceClaims.freeMail(domain) || domain != place.websiteDomain) {
            fail("email_domain_mismatch")
        }
        claim.emailDomain = domain
        activate(claim, "email_domain")
        return ticket(claim, "active", "email_domain")
    }

    override suspend fun requestPostcard(claimId: Uuid): PostcardStub {
        val profile = requireProfile()
        val claim = claimFor(claimId, profile.id)
        claim.proofMethod = "postcard"
        // TODO: no paid mailer. The code is not returned and nothing is sent.
        claim.postcardHash = "stub"
        return PostcardStub(requested = true, mailed = false, status = "pending_proof")
    }

    override suspend fun submitForReview(claimId: Uuid, note: String?): ClaimTicket {
        val profile = requireProfile()
        val claim = claimFor(claimId, profile.id)
        if (note != null && note.trim().length > 500) fail("invalid_note")
        claim.status = "pending_review"
        claim.proofMethod = "admin_review"
        claim.reviewNote = note?.trim()?.ifEmpty { null }
        return ticket(claim, "pending_review", "admin_review")
    }

    override suspend fun review(claimId: Uuid, approve: Boolean, note: String?): ClaimTicket {
        requireAdmin()
        val claim = store.placeClaims.firstOrNull { it.id == claimId && it.status == "pending_review" }
            ?: fail("invalid_claim")
        if (approve) {
            activate(claim, "admin_review")
            if (!note.isNullOrBlank()) claim.reviewNote = note.trim()
            return ticket(claim, "active", "admin_review")
        }
        claim.status = "rejected"
        claim.reviewNote = note?.trim()?.ifEmpty { null } ?: claim.reviewNote
        dissolve(claim.teamId)
        return ticket(claim, "rejected")
    }

    override suspend fun revoke(claimId: Uuid, reason: String): ClaimTicket {
        requireAdmin()
        if (reason.trim().length !in 1..500) fail("invalid_note")
        val claim = store.placeClaims.firstOrNull { it.id == claimId && it.status == "active" } ?: fail("invalid_claim")
        claim.status = "revoked"
        claim.reviewNote = reason.trim()
        dissolve(claim.teamId)
        clearOfficial(claim.placeId)
        return ticket(claim, "revoked")
    }

    override suspend fun invite(placeId: Uuid, handle: String) {
        val profile = requireProfile()
        if (!isOwner(placeId, profile.id)) fail("not_owner", XHereApiException.SQL_PERMISSION)
        val invitee = store.profiles.values.firstOrNull { it.handle == handle.trim().lowercase() }
            ?: fail("user_not_found", XHereApiException.SQL_NOT_FOUND)
        if (invitee.id == profile.id) fail("invalid_invite")
        val team = activeTeam(placeId) ?: fail("not_owner", XHereApiException.SQL_PERMISSION)
        val existing = store.placeMembers.firstOrNull { it.teamId == team.id && it.userId == invitee.id }
        if (existing == null) {
            store.placeMembers += StoredMember(team.id, invitee.id, "co_owner", "invited")
        } else if (existing.status == "removed") {
            existing.status = "invited"
            existing.role = "co_owner"
        }
    }

    override suspend fun acceptInvite(placeId: Uuid) {
        val profile = requireProfile()
        val team = activeTeam(placeId) ?: fail("invalid_invite")
        val member = store.placeMembers.firstOrNull { it.teamId == team.id && it.userId == profile.id && it.status == "invited" }
            ?: fail("invalid_invite")
        member.status = "active"
    }

    override suspend fun setHours(placeId: Uuid, hours: List<PlaceHours>, summary: String?): OfficialPlace {
        val profile = requireProfile()
        if (!isMember(placeId, profile.id)) fail("not_owner", XHereApiException.SQL_PERMISSION)
        if (!PlaceClaims.hoursOk(hours)) fail("invalid_hours")
        val line = summary?.trim()?.ifEmpty { null }
        if (line != null && line.length > 500) fail("invalid_body")
        val official = store.placeOfficial.firstOrNull { it.placeId == placeId }
            ?: StoredOfficial(placeId).also { store.placeOfficial += it }
        official.hours = hours
        official.summary = line
        return officialJson(placeId, profile.id) ?: fail("invalid_claim")
    }

    override suspend fun postAnnouncement(
        placeId: Uuid,
        title: String,
        body: String,
        expiresAt: Instant,
        point: LatLng,
        signal: LocationSignal?,
    ): AnnouncementPosted {
        val profile = requireProfile()
        if (!isMember(placeId, profile.id)) fail("not_owner", XHereApiException.SQL_PERMISSION)
        val place = place(placeId)
        val trimmedTitle = title.trim()
        val trimmedBody = body.trim()
        if (trimmedTitle.length !in 1..120) fail("invalid_title")
        if (trimmedBody.length !in 1..2000) fail("invalid_body")
        val now = clock()
        if (expiresAt <= now || expiresAt > now + 30.days) fail("expires_too_far")
        if (!Geo.isValid(point) || Geo.distanceMeters(point, place.geog) > PlaceClaims.PLACE_RADIUS_M) fail("too_far")
        val decision = decide(profile.id, LocationAction.CREATE_DROP, point, signal)
        val drop = storeDrop(
            profile = profile,
            categorySlug = "special",
            title = trimmedTitle,
            body = trimmedBody,
            point = point,
            source = DropSource.OFFICIAL,
            expiresAt = expiresAt,
            placeId = placeId,
        )
        if (!decision.countsForCredit) {
            drop.countsForCredit = false
            drop.locationTrust = "reduced"
            flag(profile.id, LocationAction.CREATE_DROP, drop.id, decision.reasons)
        }
        return AnnouncementPosted(drop.id, drop.shortId, placeId)
    }

    override suspend fun reply(dropId: Uuid, body: String): OwnerReply {
        val profile = requireProfile()
        val text = body.trim()
        if (text.length !in 1..500) fail("invalid_body")
        val drop = store.drops[dropId] ?: fail("drop_not_found", XHereApiException.SQL_NOT_FOUND)
        val place = store.claimedPlaces
            .filter { isMember(it.id, profile.id) && Geo.distanceMeters(it.geog, drop.trueLocation) <= PlaceClaims.PLACE_RADIUS_M }
            .minByOrNull { Geo.distanceMeters(it.geog, drop.trueLocation) }
            ?: fail("not_owner", XHereApiException.SQL_PERMISSION)
        val reply = OwnerReply(Uuid.random(), place.id, drop.id, profile.handle, text, clock())
        store.ownerReplies += reply
        return reply
    }

    override suspend fun repliesFor(dropId: Uuid): List<OwnerReply> {
        requireProfile()
        return store.ownerReplies.filter { it.dropId == dropId }.sortedBy { it.createdAt }
    }

    override suspend fun dispute(placeId: Uuid, reason: String, details: String?) {
        val profile = requireProfile()
        val text = reason.trim()
        if (text.length !in 1..80) fail("invalid_report")
        if (isMember(placeId, profile.id)) fail("not_allowed", XHereApiException.SQL_PERMISSION)
        val claim = openClaim(placeId)?.takeIf { it.status == "active" } ?: fail("invalid_claim")
        if (store.claimDisputes.any { it.placeId == placeId && it.reporterId == profile.id && it.status == "open" }) {
            fail("invalid_report")
        }
        store.claimDisputes += StoredDispute(Uuid.random(), claim.id, placeId, profile.id, text, details?.trim()?.ifEmpty { null }, "open", clock())
    }

    override suspend fun reviewQueue(limit: Int): List<ClaimQueueItem> {
        requireAdmin()
        val claims = store.placeClaims.filter { it.status == "pending_review" }.map { claim ->
            val place = place(claim.placeId)
            ClaimQueueItem(
                kind = "claim",
                id = claim.id,
                placeId = claim.placeId,
                placeName = place.name,
                status = claim.status,
                claimantHandle = store.profiles[claim.claimantId]?.handle,
                proofMethod = claim.proofMethod,
                websiteDomain = place.websiteDomain,
                note = claim.reviewNote,
                createdAt = claim.createdAt,
            )
        }
        val disputes = store.claimDisputes.filter { it.status == "open" }.map { row ->
            val place = place(row.placeId)
            ClaimQueueItem(
                kind = "dispute",
                id = row.id,
                placeId = row.placeId,
                placeName = place.name,
                status = row.status,
                claimantHandle = store.profiles[row.reporterId]?.handle,
                websiteDomain = place.websiteDomain,
                note = row.reason,
                createdAt = row.createdAt,
            )
        }
        return (claims + disputes).sortedBy { it.createdAt }.take(limit.coerceIn(1, 100))
    }

    override suspend fun resolveDispute(disputeId: Uuid, dismiss: Boolean) {
        requireAdmin()
        val row = store.claimDisputes.firstOrNull { it.id == disputeId && it.status == "open" }
            ?: fail("report_not_found", XHereApiException.SQL_NOT_FOUND)
        row.status = if (dismiss) "dismissed" else "actioned"
    }

    override suspend fun officialAt(point: LatLng): OfficialPlace? {
        val profile = viewer() ?: fail("not_authenticated", XHereApiException.SQL_PERMISSION)
        if (!Geo.isValid(point)) fail("invalid_location")
        val place = store.claimedPlaces
            .filter { activeClaim(it.id) != null && Geo.distanceMeters(point, it.geog) <= PlaceClaims.PLACE_RADIUS_M }
            .minByOrNull { Geo.distanceMeters(point, it.geog) }
            ?: return null
        return officialJson(place.id, profile.id)
    }

    override suspend fun officialForDrop(dropId: Uuid): OfficialPlace? {
        val profile = viewer() ?: fail("not_authenticated", XHereApiException.SQL_PERMISSION)
        val drop = store.drops[dropId] ?: return null
        val direct = drop.placeId?.takeIf { activeClaim(it) != null }
        val placeId = direct ?: store.claimedPlaces
            .filter { activeClaim(it.id) != null && Geo.distanceMeters(it.geog, drop.trueLocation) <= PlaceClaims.PLACE_RADIUS_M }
            .minByOrNull { Geo.distanceMeters(it.geog, drop.trueLocation) }
            ?.id
        return placeId?.let { officialJson(it, profile.id) }
    }

    override suspend fun myPlaces(): List<PlaceMembership> {
        val profile = requireProfile()
        return store.placeMembers.filter { it.userId == profile.id && it.status in setOf("invited", "pending", "active") }
            .mapNotNull { member ->
                val team = store.placeTeams.firstOrNull { it.id == member.teamId && it.status in setOf("forming", "active") } ?: return@mapNotNull null
                val place = store.claimedPlaces.firstOrNull { it.id == team.placeId } ?: return@mapNotNull null
                val claim = store.placeClaims.firstOrNull { it.teamId == team.id }
                PlaceMembership(place.id, place.name, member.role, member.status, team.planSlug, claim?.status)
            }
    }

    override suspend fun createCapsule(request: CapsuleRequest): CapsuleCreated {
        val profile = requireProfile()
        val mode = request.mode.trim().lowercase()
        if (mode !in setOf("date", "visit", "both")) fail("invalid_capsule")
        if (mode == "visit" && request.unlockAt != null) fail("invalid_capsule")
        if (mode in setOf("date", "both") && request.unlockAt == null) fail("invalid_capsule")
        val body = request.body?.trim()?.ifEmpty { null }
        if ((body == null || body.length > 2000) && body != null) fail("invalid_body")
        if (body == null && request.media.isEmpty()) fail("invalid_body")
        request.media.forEach { item ->
            if (item.storagePath.substringBefore('/') != profile.id.toString()) fail("invalid_media")
            VoiceClips.problem(item)?.let { fail(it) }
            if (item.mediaKind == MediaKind.AUDIO && item.storageBucket != VoiceClips.BUCKET) fail("invalid_media")
        }
        val category = store.taxonomy.category("time_capsule") ?: fail("category_not_found")
        val now = clock()
        val limits = store.taxonomy.limits(profile.tier)
        val mine = store.drops.values.filter { it.authorId == profile.id }
        val checked = CategoryRules.check(
            DropDraft(
                categorySlug = "time_capsule",
                latitude = request.latitude,
                longitude = request.longitude,
                title = request.title,
                body = body,
                media = request.media,
            ),
            category,
            PostingContext(
                userId = profile.id,
                tier = profile.tier,
                now = now,
                limits = limits,
                dropsLastHour = if (limits == null) null else mine.count { it.createdAt >= now - 1.days / 24 },
                dropsLastDay = if (limits == null) null else mine.count { it.createdAt >= now - 1.days },
            ),
            store.taxonomy.filters,
        )
        if (!checked.isValid) throw XHereApiException.fromViolation(checked.violations.first(), checked.violations)
        val point = LatLng(request.latitude, request.longitude)
        val decision = decide(profile.id, LocationAction.CREATE_DROP, point, request.signal)
        val drop = storeDrop(
            profile = profile,
            categorySlug = "time_capsule",
            title = checked.title,
            body = null,
            point = point,
            source = DropSource.USER,
            expiresAt = null,
        )
        drop.capsuleMode = mode
        drop.capsuleUnlockAt = if (mode == "visit") null else request.unlockAt
        drop.oralHistory = request.oralHistory
        drop.capsuleSecret = body
        drop.capsuleSecretMedia = request.media
        if (!decision.countsForCredit) {
            drop.countsForCredit = false
            drop.locationTrust = "reduced"
            flag(profile.id, LocationAction.CREATE_DROP, drop.id, decision.reasons)
        }
        return CapsuleCreated(drop.id, drop.shortId, mode, true, drop.capsuleUnlockAt, request.oralHistory)
    }

    override suspend fun openCapsulesHere(point: LatLng, signal: LocationSignal?): Int {
        val profile = requireProfile()
        if (!Geo.isValid(point)) fail("invalid_location")
        val decision = decide(profile.id, LocationAction.OPEN_CAPSULE, point, signal)
        var opened = 0
        store.drops.values.filter { Capsules.isLocked(it.capsuleMode, it.capsuleOpenedAt) }.forEach { drop ->
            if (Geo.distanceMeters(point, drop.trueLocation) <= Capsules.VISIT_M &&
                release(drop, profile.id, point, decision.countsForCredit, decision.reasons)
            ) {
                opened += 1
            }
        }
        return opened
    }

    /**
     * Copies a locked story onto the drop when the rule says it is open.
     * A stable map read must not call this.
     */
    fun release(drop: StoredDrop, viewerId: Uuid?, point: LatLng?, credit: Boolean, reasons: List<String>): Boolean {
        if (!Capsules.isLocked(drop.capsuleMode, drop.capsuleOpenedAt)) return false
        val now = clock()
        val visit = viewerId != null && viewerId != drop.authorId && point != null &&
            Geo.distanceMeters(point, drop.trueLocation) <= Capsules.VISIT_M
        val dated = drop.capsuleUnlockAt != null && drop.capsuleUnlockAt!! <= now
        val open = when (drop.capsuleMode) {
            "date" -> dated
            "visit" -> visit && credit
            "both" -> dated && visit && credit
            else -> false
        }
        if (!open) {
            if (visit && !credit && viewerId != null) flag(viewerId, LocationAction.OPEN_CAPSULE, drop.id, reasons)
            return false
        }
        drop.body = drop.capsuleSecret
        drop.media = drop.capsuleSecretMedia.mapIndexed { index, item ->
            DropMedia(
                id = Uuid.random(),
                storageBucket = item.storageBucket,
                storagePath = item.storagePath,
                mediaKind = item.mediaKind,
                mimeType = item.mimeType,
                byteSize = item.byteSize,
                width = item.width,
                height = item.height,
                durationS = item.durationS,
                altText = item.altText,
            )
        }
        drop.capsuleSecret = null
        drop.capsuleSecretMedia = emptyList()
        drop.capsuleOpenedAt = now
        onOpened(drop)
        return true
    }

    fun decorate(drop: StoredDrop, card: world.xhere.shared.model.DropCard, viewerId: Uuid?): world.xhere.shared.model.DropCard {
        val locked = Capsules.isLocked(drop.capsuleMode, drop.capsuleOpenedAt)
        val official = officialPlaceFor(drop)
        var next = card.copy(
            official = official != null,
            placeName = official?.name,
            officialSummary = official?.summary,
            officialHours = official?.hours ?: emptyList(),
            capsuleMode = drop.capsuleMode,
            capsuleUnlockAt = drop.capsuleUnlockAt,
            capsuleOpenedAt = drop.capsuleOpenedAt,
            oralHistory = drop.oralHistory,
            capsuleLocked = locked,
        )
        if (locked) {
            next = next.copy(revealed = false, body = null, attributes = null, media = null, tags = null)
        } else if (drop.source == DropSource.OFFICIAL && official != null) {
            next = next.copy(revealed = true, body = drop.body, media = drop.media, tags = drop.tags, attributes = drop.attributes)
        }
        return next
    }

    private fun officialPlaceFor(drop: StoredDrop): StoredPlace? {
        val direct = drop.placeId?.let { id -> store.claimedPlaces.firstOrNull { it.id == id && activeClaim(id) != null } }
        if (direct != null) return direct
        return store.claimedPlaces.firstOrNull { it.anchorDropId == drop.id && activeClaim(it.id) != null }
    }

    private fun officialJson(placeId: Uuid, viewerId: Uuid): OfficialPlace? {
        if (activeClaim(placeId) == null) return null
        val place = place(placeId)
        val official = store.placeOfficial.firstOrNull { it.placeId == placeId }
        val team = activeTeam(placeId)
        return OfficialPlace(
            placeId = place.id,
            name = place.name,
            badge = PlaceClaims.BADGE,
            summary = official?.summary,
            hours = official?.hours ?: emptyList(),
            websiteDomain = place.websiteDomain,
            anchorDropId = place.anchorDropId,
            planSlug = team?.planSlug ?: PlaceClaims.FREE_PLAN,
            openDisputes = store.claimDisputes.count { it.placeId == placeId && it.status == "open" },
            canReply = isMember(placeId, viewerId),
        )
    }

    private fun activate(claim: StoredClaim, method: String) {
        val team = store.placeTeams.firstOrNull { it.id == claim.teamId } ?: fail("invalid_claim")
        if (team.planSlug != PlaceClaims.FREE_PLAN) fail("plan_not_available")
        claim.status = "active"
        claim.proofMethod = method
        team.status = "active"
        store.placeMembers.filter { it.teamId == team.id && it.userId == claim.claimantId && it.role == "owner" }
            .forEach { it.status = "active" }
        val anchor = store.drops.values.firstOrNull { it.id == place(claim.placeId).anchorDropId }
        if (anchor != null && anchor.source == DropSource.USER) anchor.source = DropSource.OFFICIAL
        if (store.placeOfficial.none { it.placeId == claim.placeId }) {
            store.placeOfficial += StoredOfficial(claim.placeId)
        }
    }

    private fun dissolve(teamId: Uuid) {
        store.placeTeams.filter { it.id == teamId }.forEach { it.status = "dissolved" }
        store.placeMembers.filter { it.teamId == teamId }.forEach { it.status = "removed" }
    }

    private fun clearOfficial(placeId: Uuid) {
        val anchorId = store.claimedPlaces.firstOrNull { it.id == placeId }?.anchorDropId
        store.drops.values.filter { it.source == DropSource.OFFICIAL && (it.id == anchorId || it.placeId == placeId) }
            .forEach { it.source = DropSource.USER }
    }

    private fun storeDrop(
        profile: Profile,
        categorySlug: String,
        title: String,
        body: String?,
        point: LatLng,
        source: DropSource,
        expiresAt: Instant?,
        placeId: Uuid? = null,
    ): StoredDrop {
        val category = store.taxonomy.category(categorySlug) ?: fail("category_not_found")
        val group = store.taxonomy.groups.firstOrNull { it.id == category.groupId }?.slug ?: ""
        val drop = StoredDrop(
            id = Uuid.random(),
            shortId = store.nextShortId(),
            authorId = profile.id,
            authorHandle = profile.handle,
            groupSlug = group,
            category = category,
            title = title,
            body = body,
            attributes = kotlinx.serialization.json.JsonObject(emptyMap()),
            tags = emptyList(),
            trueLocation = point,
            displayLocation = point,
            revealRadiusM = category.defaultRevealM,
            status = DropStatus.ACTIVE,
            displayAnonymous = false,
            expiresAt = expiresAt,
            createdAt = clock(),
            media = emptyList(),
            source = source,
        )
        drop.placeId = placeId
        store.drops[drop.id] = drop
        return drop
    }

    private fun claimFor(claimId: Uuid, userId: Uuid): StoredClaim {
        val claim = store.placeClaims.firstOrNull { it.id == claimId && it.claimantId == userId && it.status == "pending_proof" }
            ?: fail("invalid_claim")
        return claim
    }

    private fun openClaim(placeId: Uuid): StoredClaim? =
        store.placeClaims.firstOrNull { it.placeId == placeId && it.status in OPEN }

    private fun activeClaim(placeId: Uuid): StoredClaim? =
        store.placeClaims.firstOrNull { it.placeId == placeId && it.status == "active" }

    private fun activeTeam(placeId: Uuid): StoredTeam? =
        store.placeTeams.firstOrNull { it.placeId == placeId && it.status == "active" }

    private fun isMember(placeId: Uuid, userId: Uuid): Boolean {
        val team = activeTeam(placeId) ?: return false
        return store.placeMembers.any { it.teamId == team.id && it.userId == userId && it.status == "active" }
    }

    private fun isOwner(placeId: Uuid, userId: Uuid): Boolean {
        val team = activeTeam(placeId) ?: return false
        return store.placeMembers.any { it.teamId == team.id && it.userId == userId && it.role == "owner" && it.status == "active" }
    }

    private fun place(id: Uuid): StoredPlace = store.claimedPlaces.firstOrNull { it.id == id } ?: fail("invalid_claim")

    private fun ticket(claim: StoredClaim, status: String, method: String? = claim.proofMethod) = ClaimTicket(
        claimId = claim.id,
        placeId = claim.placeId,
        teamId = claim.teamId,
        status = status,
        proofMethod = method,
        planSlug = PlaceClaims.FREE_PLAN,
    )

    private fun requireProfile(): Profile = viewer() ?: fail("not_authenticated", XHereApiException.SQL_PERMISSION)

    private fun requireAdmin() {
        val profile = requireProfile()
        if (profile.role != AppRole.ADMIN) fail("not_admin", XHereApiException.SQL_PERMISSION)
    }

    private fun websiteHostOrNull(website: String?): String? {
        if (website.isNullOrBlank()) return null
        return PlaceClaims.websiteHost(website) ?: fail("invalid_website")
    }

    private fun fail(code: String, state: String = XHereApiException.SQL_VALIDATION): Nothing =
        throw XHereApiException(code, sqlState = state)

    private companion object {
        val OPEN = setOf("pending_proof", "pending_review", "active")
    }
}

class StoredPlace(
    val id: Uuid,
    val anchorDropId: Uuid,
    var name: String,
    var websiteUrl: String?,
    var websiteDomain: String?,
    val geog: LatLng,
    val kind: String,
    val createdBy: Uuid,
)

class StoredTeam(
    val id: Uuid,
    val placeId: Uuid,
    var planSlug: String,
    var status: String,
)

class StoredMember(
    val teamId: Uuid,
    val userId: Uuid,
    var role: String,
    var status: String,
)

class StoredClaim(
    val id: Uuid,
    val placeId: Uuid,
    val teamId: Uuid,
    val claimantId: Uuid,
    var status: String,
    var proofMethod: String? = null,
    var emailDomain: String? = null,
    var reviewNote: String? = null,
    var postcardHash: String? = null,
    val createdAt: Instant,
)

class StoredOfficial(
    val placeId: Uuid,
    var hours: List<PlaceHours> = emptyList(),
    var summary: String? = null,
)

class StoredDispute(
    val id: Uuid,
    val claimId: Uuid,
    val placeId: Uuid,
    val reporterId: Uuid,
    val reason: String,
    val details: String?,
    var status: String,
    val createdAt: Instant,
)
