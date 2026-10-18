package world.xhere.shared.data.fake

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import world.xhere.shared.data.AccountRepository
import world.xhere.shared.data.AuthRepository
import world.xhere.shared.data.BlockRepository
import world.xhere.shared.data.DropRepository
import world.xhere.shared.data.IdentityProvider
import world.xhere.shared.data.ModerationRepository
import world.xhere.shared.data.ProfileRepository
import world.xhere.shared.data.ReportRepository
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.TaxonomyRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.Confirmation
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.CreateDropResult
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropMedia
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.Profile
import world.xhere.shared.model.Report
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.rules.CategoryRules
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.rules.PostingContext
import kotlin.math.floor

/**
 * In-memory stand-in for the RPCs, for unit tests and for UI work before a Supabase
 * project exists. It applies [CategoryRules] and the reveal/block rules. It does not
 * persist anything and it is not the v1.2 offline cache.
 *
 * Duplicate hints compare titles case-insensitively within 50 m. The server uses
 * `pg_trgm` similarity `>= 0.3`, which this fake does not reproduce.
 *
 * Email OTP accepts any non-blank code. Nothing is sent.
 */
class InMemoryXHere(
    val store: MemoryStore,
    val viewer: Profile?,
    private val clock: () -> Instant = { kotlin.time.Clock.System.now() },
) : XHereServices {
    private val session = MutableStateFlow<SessionInfo?>(null)

    override val auth: AuthRepository = AuthRepo()
    override val profiles: ProfileRepository = ProfileRepo()
    override val taxonomy: TaxonomyRepository = object : TaxonomyRepository {
        override suspend fun load(force: Boolean): Taxonomy = store.taxonomy
    }
    override val drops: DropRepository = DropRepo()
    override val reports: ReportRepository = ReportRepo()
    override val blocks: BlockRepository = BlockRepo()
    override val account: AccountRepository = AccountRepo()
    override val moderation: ModerationRepository = ModerationRepo()

    private fun requireViewer(): Profile {
        val current = viewer ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)
        if (current.deletedAt != null) {
            throw XHereApiException("account_unavailable", sqlState = XHereApiException.SQL_PERMISSION)
        }
        return current
    }

    private inner class AuthRepo : AuthRepository {
        override suspend fun currentSession(): SessionInfo? = session.value

        override fun sessionChanges(): Flow<SessionInfo?> = session.map { it }

        override suspend fun signInWithEmailOtp(email: String) {
            if (email.isBlank() || '@' !in email) {
                throw XHereApiException("invalid_email", sqlState = XHereApiException.SQL_VALIDATION)
            }
            store.pendingEmail = email.trim()
        }

        override suspend fun verifyEmailOtp(email: String, token: String): SessionInfo {
            val profile = requireViewer()
            if (token.isBlank() || store.pendingEmail != email.trim()) {
                throw XHereApiException("otp_expired", sqlState = XHereApiException.SQL_VALIDATION)
            }
            return SessionInfo(profile.id, "fake-access-token", email.trim()).also { session.value = it }
        }

        override suspend fun signInWithIdToken(provider: IdentityProvider, idToken: String, nonce: String?): SessionInfo {
            val profile = requireViewer()
            if (idToken.isBlank()) throw XHereApiException("invalid_grant", sqlState = XHereApiException.SQL_VALIDATION)
            return SessionInfo(profile.id, "fake-$provider", profile.displayName).also { session.value = it }
        }

        override suspend fun signOut() {
            session.value = null
        }
    }

    private inner class ProfileRepo : ProfileRepository {
        override suspend fun currentProfile(): Profile = requireViewer()
    }

    private inner class DropRepo : DropRepository {
        override suspend fun dropsInView(box: BoundingBox, categories: List<String>?, zoom: Int): List<MapFeature> {
            if (!Geo.isValidBoundingBox(box)) {
                throw XHereApiException.fromViolation(world.xhere.shared.rules.RuleViolation("invalid_bbox", "min must be below max on both axes"))
            }
            if (!Geo.isValidZoom(zoom)) {
                throw XHereApiException.fromViolation(world.xhere.shared.rules.RuleViolation("invalid_zoom", "zoom must be 0..22"))
            }
            val visible = store.visible(viewer?.id, clock()).filter { it.passes(categories) && it.displayLocation.inBox(box) }
            if (zoom >= Geo.TEASER_ZOOM) {
                return visible.sortedByDescending { it.createdAt }.take(500).map { it.toFeature() }
            }
            val cell = Geo.viewCellSizeDegrees(zoom)
            return visible.groupBy {
                floor(it.displayLocation.longitude / cell) to floor(it.displayLocation.latitude / cell)
            }.map { (_, group) ->
                if (group.size == 1) {
                    group.first().toFeature()
                } else {
                    val counts = group.groupingBy { it.category.slug }.eachCount()
                    MapFeature(
                        kind = "cluster",
                        latitude = group.map { it.displayLocation.latitude }.average(),
                        longitude = group.map { it.displayLocation.longitude }.average(),
                        pointCount = group.size,
                        categoryCounts = counts,
                    )
                }
            }.sortedByDescending { it.pointCount }.take(1000)
        }

        override suspend fun dropsNearby(location: LatLng, radiusM: Int, categories: List<String>?, limit: Int): List<DropCard> {
            if (!Geo.isValid(location)) throw XHereApiException.fromViolation(world.xhere.shared.rules.RuleViolation("invalid_location"))
            if (!Geo.isValidRadius(radiusM)) throw XHereApiException.fromViolation(world.xhere.shared.rules.RuleViolation("invalid_radius", "radius must be 1..50000 m"))
            val capped = limit.coerceIn(1, 500)
            return store.visible(viewer?.id, clock())
                .filter { it.passes(categories) }
                .map { it to Geo.distanceMeters(location, it.displayLocation) }
                .filter { it.second <= radiusM }
                .sortedWith(compareBy({ it.second }, { -it.first.createdAt.toEpochMilliseconds() }))
                .take(capped)
                .map { (drop, distance) -> drop.toCard(viewer?.id, distance) }
        }

        override suspend fun dropDetail(dropId: Uuid, location: LatLng?): DropCard {
            if (location != null && !Geo.isValid(location)) {
                throw XHereApiException.fromViolation(world.xhere.shared.rules.RuleViolation("invalid_location"))
            }
            val drop = store.drops[dropId] ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (!store.canView(drop, viewer?.id, clock())) {
                throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            }
            val distance = location?.let { Geo.distanceMeters(it, drop.displayLocation) }
            return drop.toCard(viewer?.id, distance)
        }

        override suspend fun dropTeaser(shortId: String): DropCard {
            val drop = store.drops.values.firstOrNull { it.shortId == shortId }
                ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (!store.canView(drop, viewer?.id, clock())) {
                throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            }
            val card = drop.toCard(viewer?.id, distance = null)
            return if (card.isMine) card else card.copy(revealed = false, body = null, attributes = null, media = null)
        }

        override suspend fun createDrop(request: CreateDropRequest): CreateDropResult {
            val profile = requireViewer()
            val category = store.taxonomy.category(request.categorySlug)
                ?: throw XHereApiException("category_not_found", sqlState = XHereApiException.SQL_VALIDATION, detail = request.categorySlug)
            val draft = world.xhere.shared.rules.DropDraft(
                categorySlug = request.categorySlug,
                latitude = request.latitude,
                longitude = request.longitude,
                title = request.title,
                body = request.body,
                attributes = request.attributes,
                tags = request.tags,
                revealRadiusM = request.revealRadiusM,
                expiresAt = request.expiresAt,
                displayAnonymous = request.displayAnonymous,
                media = request.media,
                acknowledgeGuidelines = request.acknowledgeGuidelines,
            )
            val checked = CategoryRules.check(
                draft,
                category,
                PostingContext(
                    userId = profile.id,
                    tier = profile.tier,
                    now = clock(),
                    limits = store.taxonomy.limits(profile.tier),
                ),
                store.taxonomy.filters,
            )
            if (!checked.isValid) throw XHereApiException.fromViolation(checked.violations.first(), checked.violations)
            val display = checkNotNull(checked.displayLocation)
            val duplicates = store.drops.values.filter { existing ->
                existing.category.slug == category.slug &&
                    existing.title.equals(checked.title, ignoreCase = true) &&
                    Geo.distanceMeters(display, existing.displayLocation) <= 50.0 &&
                    store.canView(existing, profile.id, clock())
            }.take(5).map { it.shortId }
            val id = Uuid.random()
            val shortId = store.nextShortId()
            val groupSlug = store.taxonomy.groups.firstOrNull { it.id == category.groupId }?.slug ?: ""
            val stored = StoredDrop(
                id = id,
                shortId = shortId,
                authorId = profile.id,
                authorHandle = profile.handle,
                groupSlug = groupSlug,
                category = category,
                title = checked.title,
                body = checked.body,
                attributes = checked.attributes,
                tags = checked.tags,
                trueLocation = checkNotNull(checked.trueLocation),
                displayLocation = display,
                revealRadiusM = checked.revealRadiusM,
                status = checked.status,
                displayAnonymous = request.displayAnonymous,
                expiresAt = checked.expiresAt,
                createdAt = clock(),
                media = request.media.mapIndexed { index, item ->
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
                    )
                },
            )
            store.drops[id] = stored
            return CreateDropResult(
                id = id,
                shortId = shortId,
                status = stored.status,
                category = category.slug,
                categoryVersion = category.version,
                revealRadiusM = stored.revealRadiusM,
                expiresAt = stored.expiresAt,
                latitude = display.latitude,
                longitude = display.longitude,
                locationPrecisionM = category.locationPrecisionM,
                postingNotice = category.postingNotice,
                possibleDuplicates = duplicates,
            )
        }

        override suspend fun confirmDrop(
            dropId: Uuid,
            verdict: ConfirmationVerdict,
            location: LatLng,
            note: String?,
        ): ConfirmDropResult {
            val profile = requireViewer()
            if (!Geo.isValid(location)) throw XHereApiException.fromViolation(world.xhere.shared.rules.RuleViolation("invalid_location"))
            val drop = store.drops[dropId] ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (!store.canView(drop, profile.id, clock()) || drop.status !in setOf(DropStatus.ACTIVE, DropStatus.CONFIRMED)) {
                throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            }
            val distance = Geo.distanceMeters(location, drop.displayLocation)
            val problems = CategoryRules.checkConfirmation(distance, drop.revealRadiusM, note, drop.authorId == profile.id)
            if (problems.isNotEmpty()) throw XHereApiException.fromViolation(problems.first(), problems)
            store.confirmations[dropId to profile.id] = Confirmation(
                dropId = dropId,
                userId = profile.id,
                verdict = verdict,
                note = note?.trim()?.ifEmpty { null },
                distanceM = distance.toInt(),
                createdAt = clock(),
            )
            val rows = store.confirmations.filterKeys { it.first == dropId }.values
            val confirms = rows.count { it.verdict == ConfirmationVerdict.STILL_TRUE }
            val denies = rows.count { it.verdict == ConfirmationVerdict.NOT_TRUE }
            drop.confirmCount = confirms
            drop.denyCount = denies
            drop.status = CategoryRules.statusAfterConfirmations(drop.status, confirms, denies, drop.category)
            drop.expiresAt = CategoryRules.expiryAfterConfirmations(drop.status, drop.expiresAt, confirms, denies, drop.category)
            if (verdict == ConfirmationVerdict.STILL_TRUE) {
                drop.lastConfirmedAt = clock()
                val extend = drop.category.confirmExtendsTtl
                val currentExpiry = drop.expiresAt
                if (extend != null && currentExpiry != null) {
                    val extended = maxOf(currentExpiry, clock() + extend)
                    val cap = drop.category.maxTtl?.let { clock() + it }
                    drop.expiresAt = if (cap != null) minOf(extended, cap) else extended
                }
            }
            return ConfirmDropResult(
                dropId = dropId,
                verdict = verdict,
                status = drop.status,
                isConfirmed = drop.status == DropStatus.CONFIRMED,
                confirmCount = drop.confirmCount,
                denyCount = drop.denyCount,
                expiresAt = drop.expiresAt,
                distanceM = distance.toInt(),
            )
        }
    }

    private inner class ReportRepo : ReportRepository {
        override suspend fun report(targetType: ReportTarget, targetId: Uuid, reason: ReportReason, details: String?): Uuid {
            val profile = requireViewer()
            if (details != null && details.length > 1000) {
                throw XHereApiException("invalid_report", sqlState = XHereApiException.SQL_VALIDATION, detail = "details must be at most 1000 characters")
            }
            val existing = store.reports.values.firstOrNull {
                it.reporterId == profile.id && it.targetType == targetType && it.targetId == targetId && it.status == ReportStatus.OPEN
            }
            if (existing != null) return existing.id
            if (targetType == ReportTarget.DROP) {
                val drop = store.drops[targetId] ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
                if (drop.authorId == profile.id) throw XHereApiException("cannot_report_self", sqlState = XHereApiException.SQL_VALIDATION)
            } else if (targetId == profile.id) {
                throw XHereApiException("cannot_report_self", sqlState = XHereApiException.SQL_VALIDATION)
            }
            val id = Uuid.random()
            store.reports[id] = Report(id, targetType, targetId, profile.id, reason, details?.trim()?.ifEmpty { null }, ReportStatus.OPEN, clock())
            if (targetType == ReportTarget.DROP) {
                val drop = store.drops[targetId]
                if (drop != null && drop.status in setOf(DropStatus.ACTIVE, DropStatus.CONFIRMED)) {
                    val open = store.reports.values.count {
                        it.targetType == ReportTarget.DROP && it.targetId == targetId && it.status == ReportStatus.OPEN
                    }
                    if (open >= drop.category.reportsToHide) drop.status = DropStatus.PENDING_REVIEW
                }
            }
            return id
        }
    }

    private inner class BlockRepo : BlockRepository {
        override suspend fun blockUser(userId: Uuid) {
            val profile = requireViewer()
            if (userId == profile.id) throw XHereApiException("cannot_block_self", sqlState = XHereApiException.SQL_VALIDATION)
            store.blocks.add(profile.id to userId)
        }

        override suspend fun unblockUser(userId: Uuid) {
            val profile = requireViewer()
            store.blocks.remove(profile.id to userId)
        }
    }

    private inner class AccountRepo : AccountRepository {
        override suspend fun deleteMyAccount() {
            val profile = requireViewer()
            store.deleted.add(profile.id)
            store.blocks.removeAll { it.first == profile.id }
            session.value = null
        }
    }

    private inner class ModerationRepo : ModerationRepository {
        override suspend fun moderateDrop(dropId: Uuid, action: ModerateDropAction, reason: String?): DropStatus {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            val drop = store.drops[dropId] ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (drop.authorId == profile.id) throw XHereApiException("cannot_moderate_own_drop", sqlState = XHereApiException.SQL_PERMISSION)
            when (action) {
                ModerateDropAction.APPROVE -> {
                    if (drop.status != DropStatus.PENDING_REVIEW) {
                        throw XHereApiException("invalid_transition", sqlState = XHereApiException.SQL_VALIDATION)
                    }
                    drop.status = DropStatus.ACTIVE
                }
                ModerateDropAction.RESTORE -> {
                    if (drop.status != DropStatus.REMOVED && drop.status != DropStatus.DISPUTED) {
                        throw XHereApiException("invalid_transition", sqlState = XHereApiException.SQL_VALIDATION)
                    }
                    drop.status = DropStatus.ACTIVE
                }
                ModerateDropAction.REMOVE -> {
                    if (drop.status == DropStatus.REMOVED) {
                        throw XHereApiException("invalid_transition", sqlState = XHereApiException.SQL_VALIDATION)
                    }
                    drop.status = DropStatus.REMOVED
                    drop.removedReason = reason ?: "removed by moderator"
                }
            }
            return drop.status
        }

        override suspend fun resolveReport(reportId: Uuid, status: ReportStatus, note: String?) {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            if (status == ReportStatus.OPEN) throw XHereApiException("invalid_status", sqlState = XHereApiException.SQL_VALIDATION)
            val report = store.reports[reportId] ?: throw XHereApiException("report_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (report.status != ReportStatus.OPEN) throw XHereApiException("report_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            store.reports[reportId] = report.copy(status = status)
        }

        override suspend fun suspendUser(userId: Uuid, until: Instant?, reason: String?) {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            if (userId == profile.id) throw XHereApiException("cannot_moderate_self", sqlState = XHereApiException.SQL_PERMISSION)
            if (until != null && until > clock()) store.suspendedUntil[userId] = until else store.suspendedUntil.remove(userId)
        }
    }
}

class MemoryStore(
    val taxonomy: Taxonomy,
) {
    val drops: MutableMap<Uuid, StoredDrop> = linkedMapOf()
    val confirmations: MutableMap<Pair<Uuid, Uuid>, Confirmation> = linkedMapOf()
    val reports: MutableMap<Uuid, Report> = linkedMapOf()
    val blocks: MutableSet<Pair<Uuid, Uuid>> = linkedSetOf()
    val deleted: MutableSet<Uuid> = linkedSetOf()
    val suspendedUntil: MutableMap<Uuid, Instant> = linkedMapOf()
    var pendingEmail: String? = null
    private var shortIds: Int = 0

    fun nextShortId(): String {
        shortIds += 1
        return "d" + shortIds.toString().padStart(11, '0')
    }

    fun visible(viewerId: Uuid?, now: Instant): List<StoredDrop> =
        drops.values.filter { canView(it, viewerId, now) }

    fun canView(drop: StoredDrop, viewerId: Uuid?, now: Instant): Boolean {
        val expired = drop.expiresAt != null && drop.expiresAt!! <= now
        if (expired) return false
        if (viewerId != null && drop.authorId == viewerId) {
            return drop.status in setOf(DropStatus.ACTIVE, DropStatus.CONFIRMED, DropStatus.PENDING_REVIEW, DropStatus.DISPUTED)
        }
        if (drop.status != DropStatus.ACTIVE && drop.status != DropStatus.CONFIRMED) return false
        if (!drop.category.active) return false
        if (drop.authorId in deleted) return false
        if (viewerId != null && blocked(viewerId, drop.authorId)) return false
        return true
    }

    fun blocked(a: Uuid, b: Uuid): Boolean = (a to b) in blocks || (b to a) in blocks
}

class StoredDrop(
    val id: Uuid,
    val shortId: String,
    val authorId: Uuid,
    val authorHandle: String,
    val groupSlug: String,
    val category: world.xhere.shared.model.Category,
    val title: String,
    val body: String?,
    val attributes: kotlinx.serialization.json.JsonObject,
    val tags: List<String>,
    val trueLocation: LatLng,
    val displayLocation: LatLng,
    val revealRadiusM: Int,
    var status: DropStatus,
    val displayAnonymous: Boolean,
    var expiresAt: Instant?,
    var confirmCount: Int = 0,
    var denyCount: Int = 0,
    var lastConfirmedAt: Instant? = null,
    val createdAt: Instant,
    val media: List<DropMedia>,
    var removedReason: String? = null,
)

/**
 * `drops_in_view` / `drops_nearby` accept category slugs and group slugs in the same array
 * (`c.slug = any(...) or g.slug = any(...)`). An empty list matches nothing; null means no filter.
 */
private fun StoredDrop.passes(categories: List<String>?): Boolean {
    if (categories == null) return true
    return category.slug in categories || groupSlug in categories
}

private fun StoredDrop.toCard(viewerId: Uuid?, distance: Double?): DropCard {
    val mine = viewerId != null && authorId == viewerId
    val revealed = Geo.isRevealed(distance ?: Double.POSITIVE_INFINITY, revealRadiusM, mine)
    return DropCard(
        id = id,
        shortId = shortId,
        categorySlug = category.slug,
        groupSlug = groupSlug,
        title = title,
        latitude = displayLocation.latitude,
        longitude = displayLocation.longitude,
        locationPrecisionM = category.locationPrecisionM,
        distanceM = distance,
        revealRadiusM = revealRadiusM,
        revealed = revealed,
        body = if (revealed) body else null,
        attributes = if (revealed) attributes else null,
        media = if (revealed) media else null,
        tags = tags,
        status = status,
        isConfirmed = status == DropStatus.CONFIRMED,
        confirmCount = confirmCount,
        denyCount = denyCount,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        createdAt = createdAt,
        authorHandle = if (displayAnonymous && category.allowAnonymousDisplay && !mine) null else authorHandle,
        isMine = mine,
    )
}

private fun StoredDrop.toFeature(): MapFeature = MapFeature(
    kind = "drop",
    dropId = id,
    shortId = shortId,
    categorySlug = category.slug,
    groupSlug = groupSlug,
    title = title,
    latitude = displayLocation.latitude,
    longitude = displayLocation.longitude,
    pointCount = 1,
)

private fun LatLng.inBox(box: BoundingBox): Boolean =
    latitude in box.minLatitude..box.maxLatitude && longitude in box.minLongitude..box.maxLongitude
