package world.xhere.shared.data.fake

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import world.xhere.shared.data.AccountRepository
import world.xhere.shared.data.AuthRepository
import world.xhere.shared.data.BlockRepository
import world.xhere.shared.data.DropRepository
import world.xhere.shared.data.HiddenDropRepository
import world.xhere.shared.data.ImpactRepository
import world.xhere.shared.data.IdentityProvider
import world.xhere.shared.data.MediaRepository
import world.xhere.shared.data.ModerationRepository
import world.xhere.shared.data.ProfileRepository
import world.xhere.shared.data.ReportRepository
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.SuggestionRepository
import world.xhere.shared.data.TaxonomyRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.ExportJson
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.BlockedUser
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.HiddenDrop
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.model.ModerationAction
import world.xhere.shared.model.ModerationActionType
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.PublicProfile
import world.xhere.shared.model.QueueItemKind
import world.xhere.shared.model.Confirmation
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.CreateDropResult
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropMedia
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MapFeature
import world.xhere.shared.media.ExifStrip
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
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
import world.xhere.shared.safety.SafetyDirectory
import world.xhere.shared.safety.SafetyFlow
import world.xhere.shared.suggest.CategoryRanker
import world.xhere.shared.suggest.CategorySuggestion
import world.xhere.shared.suggest.SuggestionQuery
import kotlin.math.floor
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    viewer: Profile?,
    private val clock: () -> Instant = { kotlin.time.Clock.System.now() },
) : XHereServices {
    private val session = MutableStateFlow<SessionInfo?>(null)

    override val auth: AuthRepository = AuthRepo()
    override val profiles: ProfileRepository = ProfileRepo()
    override val taxonomy: TaxonomyRepository = object : TaxonomyRepository {
        override suspend fun load(force: Boolean): Taxonomy = store.taxonomy
    }
    override val drops: DropRepository = DropRepo()
    override val suggestions: SuggestionRepository = SuggestionRepo()
    override val media: MediaRepository = MediaRepo()
    override val reports: ReportRepository = ReportRepo()
    override val blocks: BlockRepository = BlockRepo()
    override val hidden: HiddenDropRepository = HiddenRepo()
    override val impact: ImpactRepository = ImpactRepo()
    override val account: AccountRepository = AccountRepo()
    override val moderation: ModerationRepository = ModerationRepo()

    /** Who is signed in. Tests may swap it, for example to act as a moderator. */
    var viewer: Profile? = viewer
        set(value) {
            field = value
            if (value != null) store.profiles[value.id] = value
        }

    init {
        if (viewer != null && viewer.id !in store.profiles) store.profiles[viewer.id] = viewer
    }

    /**
     * One signed-in viewer per drop. The author and a signed-out caller do not count.
     * A repeat open does not count again. Matches `private.record_drop_view`.
     */
    private fun recordView(drop: StoredDrop) {
        val viewerId = viewer?.id ?: return
        if (drop.authorId == viewerId) return
        if (!store.views.add(drop.id to viewerId)) return
        drop.viewCount += 1
        store.noteHelped(drop, "view", clock())
    }

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

        override suspend fun publicProfile(handle: String): PublicProfile? {
            requireViewer()
            val wanted = handle.trim().lowercase()
            val known = store.profiles.values.firstOrNull { it.handle == wanted && it.id !in store.deleted && it.deletedAt == null }
            if (known != null) return PublicProfile(known.id, known.handle, known.displayName, known.createdAt)
            val author = store.drops.values.firstOrNull { it.authorHandle == wanted && it.authorId !in store.deleted } ?: return null
            return PublicProfile(author.authorId, author.authorHandle, null, author.createdAt)
        }
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
                .map { (drop, distance) -> drop.toCard(viewer?.id, distance, store.verdict(drop.id, viewer?.id)) }
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
            recordView(drop)
            return drop.toCard(viewer?.id, distance, store.verdict(drop.id, viewer?.id))
        }

        override suspend fun dropTeaser(shortId: String): DropCard {
            val drop = store.drops.values.firstOrNull { it.shortId == shortId }
                ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (!store.canView(drop, viewer?.id, clock())) {
                throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            }
            // Share teasers do not count a view. Anonymous refreshes must not inflate "seen by".
            val card = drop.toCard(viewer?.id, distance = null, store.verdict(drop.id, viewer?.id))
            // Share teasers never include the body, even for the author.
            // drop_detail is what reveals it.
            return card.copy(revealed = false, body = null, attributes = null, media = null, tags = null)
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
            val now = clock()
            val limits = store.taxonomy.limits(profile.tier)
            val mine = store.drops.values.filter { it.authorId == profile.id }
            val checked = CategoryRules.check(
                draft,
                category,
                PostingContext(
                    userId = profile.id,
                    tier = profile.tier,
                    now = now,
                    limits = limits,
                    dropsLastHour = if (limits == null) null else mine.count { it.createdAt >= now - 1.hours },
                    dropsLastDay = if (limits == null) null else mine.count { it.createdAt >= now - 24.hours },
                ),
                store.taxonomy.filters,
            )
            if (!checked.isValid) throw XHereApiException.fromViolation(checked.violations.first(), checked.violations)
            if (!SafetyFlow.availableAt(category, SafetyDirectory.forLocation(LatLng(request.latitude, request.longitude)))) {
                throw XHereApiException(
                    "incident_reports_unavailable",
                    sqlState = XHereApiException.SQL_PERMISSION,
                    hint = "Incident reports are only available where a local police tip line is set up. In an emergency call 911.",
                )
            }
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
            val previous = store.confirmations[dropId to profile.id]?.verdict
            if (problems.isNotEmpty()) throw XHereApiException.fromViolation(problems.first(), problems)
            val cap = store.taxonomy.limits(profile.tier)?.confirmationsPerHour
            if (cap != null) {
                val since = clock() - 1.hours
                val recentOthers = store.confirmations.values.count { row ->
                    row.userId == profile.id && row.dropId != drop.id && row.createdAt > since
                }
                if (recentOthers >= cap) {
                    throw XHereApiException(
                        "rate_limited",
                        sqlState = XHereApiException.SQL_RATE_LIMITED,
                        detail = "tier ${profile.tier.name.lowercase()} allows $cap confirmations per hour",
                    )
                }
            }
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
            if (verdict == ConfirmationVerdict.STILL_TRUE && previous != ConfirmationVerdict.STILL_TRUE) {
                store.noteHelped(drop, "confirm", clock())
            }
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

    private inner class SuggestionRepo : SuggestionRepository {
        override suspend fun suggest(query: SuggestionQuery): List<CategorySuggestion> =
            CategoryRanker.suggest(store.taxonomy.categories, query, nearbyCounts(query))

        override suspend fun log(query: SuggestionQuery, suggested: List<String>, chosenSlug: String?): Uuid {
            val profile = requireViewer()
            if (suggested.isEmpty() || suggested.size > 10) {
                throw XHereApiException("invalid_suggestion", sqlState = XHereApiException.SQL_VALIDATION, detail = "suggested must be 1..10 slugs")
            }
            if (chosenSlug != null && store.taxonomy.category(chosenSlug) == null) {
                throw XHereApiException("category_not_found", sqlState = XHereApiException.SQL_VALIDATION, detail = chosenSlug)
            }
            val id = Uuid.random()
            store.suggestionLogs += StoredSuggestion(
                id = id,
                userId = profile.id,
                inputs = buildJsonObject {
                    put("text", query.text)
                    put("labels", JsonArray(query.labels.map { JsonPrimitive(it) }))
                    query.latitude?.let { put("lat", it) }
                    query.longitude?.let { put("lng", it) }
                    query.hour?.let { put("hour", it) }
                },
                suggested = suggested,
                chosenSlug = chosenSlug,
                createdAt = clock(),
            )
            return id
        }

        private fun nearbyCounts(query: SuggestionQuery): Map<String, Int> {
            val latitude = query.latitude ?: return emptyMap()
            val longitude = query.longitude ?: return emptyMap()
            val origin = LatLng(latitude, longitude)
            if (!Geo.isValid(origin)) return emptyMap()
            return store.visible(viewer?.id, clock())
                .filter { Geo.distanceMeters(origin, it.displayLocation) <= 500.0 }
                .groupingBy { it.category.slug }
                .eachCount()
        }
    }

    private inner class MediaRepo : MediaRepository {
        override suspend fun uploadPhoto(bytes: ByteArray, mimeType: String): MediaUpload {
            val profile = requireViewer()
            val mime = mimeType.substringBefore(';').trim().lowercase()
            if (mime != "image/jpeg" && mime != "image/png" && mime != "image/webp") {
                throw XHereApiException("unsupported_type", sqlState = XHereApiException.SQL_VALIDATION, detail = mime)
            }
            if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) {
                throw XHereApiException("invalid_image", sqlState = XHereApiException.SQL_VALIDATION, detail = "photo must be 1 byte..10 MB")
            }
            val stripped = ExifStrip.strip(bytes, mime)
            val extension = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val path = "${profile.id}/${Uuid.random()}.$extension"
            store.mediaBytes[path] = stripped
            return MediaUpload(
                storagePath = path,
                mediaKind = MediaKind.IMAGE,
                mimeType = mime,
                byteSize = stripped.size.toLong(),
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
                    if (open >= drop.category.reportsToHide) {
                        drop.status = DropStatus.PENDING_REVIEW
                        store.log(null, "drop", drop.id, ModerationActionType.AUTO_HIDE, "report threshold reached", clock())
                    }
                }
            }
            return id
        }
    }

    private inner class BlockRepo : BlockRepository {
        override suspend fun blockUser(userId: Uuid) {
            val profile = requireViewer()
            if (userId == profile.id) throw XHereApiException("cannot_block_self", sqlState = XHereApiException.SQL_VALIDATION)
            if (store.blocks.add(profile.id to userId)) store.blockedAt[profile.id to userId] = clock()
        }

        override suspend fun unblockUser(userId: Uuid) {
            val profile = requireViewer()
            store.blocks.remove(profile.id to userId)
            store.blockedAt.remove(profile.id to userId)
        }

        override suspend fun myBlocks(): List<BlockedUser> {
            val profile = requireViewer()
            return store.blocks.filter { it.first == profile.id }.map { pair ->
                val other = store.profiles[pair.second]
                val handle = other?.handle ?: store.drops.values.firstOrNull { it.authorId == pair.second }?.authorHandle ?: "unknown"
                BlockedUser(pair.second, handle, other?.displayName, store.blockedAt[pair] ?: clock())
            }.sortedByDescending { it.blockedAt }
        }
    }

    private inner class HiddenRepo : HiddenDropRepository {
        override suspend fun hideDrop(dropId: Uuid) {
            val profile = requireViewer()
            if ((profile.id to dropId) in store.hidden) return
            val drop = store.drops[dropId]
            if (drop == null || !store.canView(drop, profile.id, clock())) {
                throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            }
            if (drop.authorId == profile.id) throw XHereApiException("cannot_hide_own_drop", sqlState = XHereApiException.SQL_VALIDATION)
            store.hidden[profile.id to dropId] = clock()
        }

        override suspend fun unhideDrop(dropId: Uuid) {
            val profile = requireViewer()
            store.hidden.remove(profile.id to dropId)
        }

        override suspend fun myHiddenDrops(): List<HiddenDrop> {
            val profile = requireViewer()
            val now = clock()
            return store.hidden.filterKeys { it.first == profile.id }.mapNotNull { (key, at) ->
                val drop = store.drops[key.second] ?: return@mapNotNull null
                val live = drop.status == DropStatus.ACTIVE || drop.status == DropStatus.CONFIRMED
                if (!live || (drop.expiresAt != null && drop.expiresAt!! <= now)) return@mapNotNull null
                HiddenDrop(drop.id, drop.shortId, drop.title, drop.category.slug, at)
            }.sortedByDescending { it.hiddenAt }
        }
    }

    private inner class ImpactRepo : ImpactRepository {
        override suspend fun myNotices(limit: Int): List<ImpactNotice> {
            val profile = requireViewer()
            return store.impactNotices
                .filter { it.recipientId == profile.id }
                .sortedWith(
                    compareByDescending<ImpactNotice> { it.readAt == null }
                        .thenByDescending { it.updatedAt ?: it.createdAt },
                )
                .take(limit.coerceIn(1, 50))
        }

        override suspend fun markRead(noticeId: Uuid?) {
            val profile = requireViewer()
            val now = clock()
            store.impactNotices.replaceAll { notice ->
                if (notice.recipientId == profile.id && notice.readAt == null && (noticeId == null || notice.id == noticeId)) {
                    notice.copy(readAt = now, updatedAt = now)
                } else {
                    notice
                }
            }
        }
    }

    private inner class AccountRepo : AccountRepository {
        override suspend fun deleteMyAccount() {
            val profile = requireViewer()
            store.deleted.add(profile.id)
            store.blocks.removeAll { it.first == profile.id }
            session.value = null
        }

        override suspend fun exportMyData(): String {
            val profile = requireViewer()
            return ExportJson.encodeToString(JsonObject.serializer(), exportFor(profile))
        }
    }

    private fun exportFor(profile: Profile): JsonObject = buildJsonObject {
        put("format", "xhere-export-v1")
        put("exported_at", clock().toString())
        put("user_id", profile.id.toString())
        put("email", session.value?.email)
        put("profile", buildJsonObject {
            put("handle", profile.handle)
            put("display_name", profile.displayName)
            put("tier", profile.tier.name.lowercase())
            put("role", profile.role.name.lowercase())
            put("points", profile.points)
            put("home_area", profile.homeArea)
            put("created_at", profile.createdAt.toString())
        })
        put("suspension", store.suspendedUntil[profile.id]?.let { until ->
            buildJsonObject { put("suspended_until", until.toString()) }
        } ?: JsonNull)
        put("drops", JsonArray(store.drops.values.filter { it.authorId == profile.id }.map { drop ->
            buildJsonObject {
                put("id", drop.id.toString())
                put("short_id", drop.shortId)
                put("category", drop.category.slug)
                put("title", drop.title)
                put("body", drop.body)
                put("attributes", drop.attributes)
                put("tags", JsonArray(drop.tags.map { JsonPrimitive(it) }))
                put("lat", drop.trueLocation.latitude)
                put("lng", drop.trueLocation.longitude)
                put("display_lat", drop.displayLocation.latitude)
                put("display_lng", drop.displayLocation.longitude)
                put("reveal_radius_m", drop.revealRadiusM)
                put("status", drop.status.name.lowercase())
                put("expires_at", drop.expiresAt?.toString())
                put("created_at", drop.createdAt.toString())
                put("media", JsonArray(drop.media.map { JsonPrimitive(it.storagePath) }))
            }
        }))
        put("confirmations", JsonArray(store.confirmations.values.filter { it.userId == profile.id }.map { row ->
            buildJsonObject {
                put("drop_id", row.dropId.toString())
                put("verdict", row.verdict.name.lowercase())
                put("note", row.note)
                put("distance_m", row.distanceM)
                put("created_at", row.createdAt.toString())
            }
        }))
        put("reports_filed", JsonArray(store.reports.values.filter { it.reporterId == profile.id }.map { report ->
            buildJsonObject {
                put("id", report.id.toString())
                put("target_type", report.targetType.name.lowercase())
                put("target_id", report.targetId.toString())
                put("reason", report.reason.name.lowercase())
                put("details", report.details)
                put("status", report.status.name.lowercase())
                put("created_at", report.createdAt.toString())
            }
        }))
        put("blocks", JsonArray(store.blocks.filter { it.first == profile.id }.map { pair ->
            buildJsonObject {
                put("user_id", pair.second.toString())
                put("created_at", store.blockedAt[pair]?.toString())
            }
        }))
        put("hidden_drops", JsonArray(store.hidden.filterKeys { it.first == profile.id }.map { (key, at) ->
            buildJsonObject {
                put("drop_id", key.second.toString())
                put("created_at", at.toString())
            }
        }))
        put("category_suggestions", JsonArray(store.suggestionLogs.filter { it.userId == profile.id }.map { row ->
            buildJsonObject {
                put("inputs", row.inputs)
                put("suggested", JsonArray(row.suggested.map { JsonPrimitive(it) }))
                put("chosen", row.chosenSlug)
                put("created_at", row.createdAt.toString())
            }
        }))
        val mine = store.drops.values.filter { it.authorId == profile.id }.map { it.id }.toSet()
        put("moderation_of_my_content", JsonArray(store.auditLog.filter {
            (it.targetType == "profile" && it.targetId == profile.id) || (it.targetType == "drop" && it.targetId in mine)
        }.map { action ->
            buildJsonObject {
                put("action", action.action.name.lowercase())
                put("target_type", action.targetType)
                put("target_id", action.targetId.toString())
                put("reason", action.reason)
                put("created_at", action.createdAt.toString())
            }
        }))
    }

    private inner class ModerationRepo : ModerationRepository {
        override suspend fun moderateDrop(dropId: Uuid, action: ModerateDropAction, reason: String?): DropStatus {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            val drop = store.drops[dropId] ?: throw XHereApiException("drop_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (drop.authorId == profile.id) throw XHereApiException("cannot_moderate_own_drop", sqlState = XHereApiException.SQL_PERMISSION)
            val logged: ModerationActionType
            val reportStatus: ReportStatus
            when (action) {
                ModerateDropAction.APPROVE -> {
                    if (drop.status != DropStatus.PENDING_REVIEW) {
                        throw XHereApiException("invalid_transition", sqlState = XHereApiException.SQL_VALIDATION)
                    }
                    drop.status = DropStatus.ACTIVE
                    logged = ModerationActionType.APPROVE
                    reportStatus = ReportStatus.DISMISSED
                }
                ModerateDropAction.RESTORE -> {
                    if (drop.status != DropStatus.REMOVED && drop.status != DropStatus.DISPUTED) {
                        throw XHereApiException("invalid_transition", sqlState = XHereApiException.SQL_VALIDATION)
                    }
                    drop.status = DropStatus.ACTIVE
                    drop.removedReason = null
                    logged = ModerationActionType.RESTORE
                    reportStatus = ReportStatus.DISMISSED
                }
                ModerateDropAction.REMOVE -> {
                    if (drop.status == DropStatus.REMOVED) {
                        throw XHereApiException("invalid_transition", sqlState = XHereApiException.SQL_VALIDATION)
                    }
                    logged = if (drop.status == DropStatus.PENDING_REVIEW) ModerationActionType.REJECT else ModerationActionType.REMOVE
                    drop.status = DropStatus.REMOVED
                    drop.removedReason = reason ?: "removed by moderator"
                    reportStatus = ReportStatus.ACTIONED
                }
            }
            store.reports.values.filter {
                it.targetType == ReportTarget.DROP && it.targetId == drop.id && it.status == ReportStatus.OPEN
            }.forEach { store.reports[it.id] = it.copy(status = reportStatus) }
            store.log(profile.id, "drop", drop.id, logged, reason, clock())
            return drop.status
        }

        override suspend fun resolveReport(reportId: Uuid, status: ReportStatus, note: String?) {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            if (status == ReportStatus.OPEN) throw XHereApiException("invalid_status", sqlState = XHereApiException.SQL_VALIDATION)
            val report = store.reports[reportId] ?: throw XHereApiException("report_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            if (report.status != ReportStatus.OPEN) throw XHereApiException("report_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            store.reports[reportId] = report.copy(status = status)
            val logged = if (status == ReportStatus.ACTIONED) ModerationActionType.ACTION_REPORT else ModerationActionType.DISMISS_REPORT
            store.log(profile.id, "report", reportId, logged, note, clock())
        }

        override suspend fun suspendUser(userId: Uuid, until: Instant?, reason: String?) {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            if (userId == profile.id) throw XHereApiException("cannot_moderate_self", sqlState = XHereApiException.SQL_PERMISSION)
            if (until != null && until > clock()) store.suspendedUntil[userId] = until else store.suspendedUntil.remove(userId)
            val logged = if (until == null) ModerationActionType.UNSUSPEND_USER else ModerationActionType.SUSPEND_USER
            store.log(profile.id, "profile", userId, logged, reason, clock())
        }

        override suspend fun queue(limit: Int): List<ModerationQueueItem> {
            val profile = requireViewer()
            if (!profile.isModerator) throw XHereApiException("not_moderator", sqlState = XHereApiException.SQL_PERMISSION)
            val open = store.reports.values.filter { it.status == ReportStatus.OPEN }
            val drops = store.drops.values.filter { drop ->
                val reports = open.filter { it.targetType == ReportTarget.DROP && it.targetId == drop.id }
                drop.status in setOf(DropStatus.ACTIVE, DropStatus.CONFIRMED, DropStatus.PENDING_REVIEW, DropStatus.DISPUTED) &&
                    (drop.status == DropStatus.PENDING_REVIEW || reports.isNotEmpty()) &&
                    drop.authorId != profile.id &&
                    drop.authorId !in store.deleted
            }.map { drop ->
                val reports = open.filter { it.targetType == ReportTarget.DROP && it.targetId == drop.id }.sortedBy { it.createdAt }
                ModerationQueueItem(
                    kind = QueueItemKind.DROP,
                    targetId = drop.id,
                    shortId = drop.shortId,
                    title = drop.title,
                    body = drop.body,
                    categorySlug = drop.category.slug,
                    status = drop.status,
                    sensitive = drop.category.sensitive,
                    subjectId = drop.authorId,
                    subjectHandle = drop.authorHandle,
                    subjectSuspendedUntil = store.suspendedUntil[drop.authorId],
                    openReports = reports.map { it.reporterId }.distinct().size,
                    reasons = reports.map { it.reason }.distinct(),
                    latestDetails = reports.lastOrNull { it.details != null }?.details,
                    reportIds = reports.map { it.id },
                    queuedAt = reports.firstOrNull()?.createdAt ?: drop.createdAt,
                )
            }
            val people = open.filter { it.targetType == ReportTarget.PROFILE && it.targetId != profile.id && it.targetId !in store.deleted }
                .groupBy { it.targetId }
                .map { (userId, reports) ->
                    val sorted = reports.sortedBy { it.createdAt }
                    ModerationQueueItem(
                        kind = QueueItemKind.PROFILE,
                        targetId = userId,
                        subjectId = userId,
                        subjectHandle = store.profiles[userId]?.handle
                            ?: store.drops.values.firstOrNull { it.authorId == userId }?.authorHandle,
                        subjectSuspendedUntil = store.suspendedUntil[userId],
                        openReports = sorted.map { it.reporterId }.distinct().size,
                        reasons = sorted.map { it.reason }.distinct(),
                        latestDetails = sorted.lastOrNull { it.details != null }?.details,
                        reportIds = sorted.map { it.id },
                        queuedAt = sorted.first().createdAt,
                    )
                }
            return (drops + people)
                .sortedWith(compareByDescending<ModerationQueueItem> { it.sensitive }.thenByDescending { it.isPending }.thenBy { it.queuedAt })
                .take(limit.coerceIn(1, 200))
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
    val blockedAt: MutableMap<Pair<Uuid, Uuid>, Instant> = linkedMapOf()
    /** (viewer, drop) → when it was hidden. */
    val hidden: MutableMap<Pair<Uuid, Uuid>, Instant> = linkedMapOf()
    /** Other accounts the fake knows about, for `public_profile` and the moderator queue. */
    val profiles: MutableMap<Uuid, Profile> = linkedMapOf()
    /** `moderation_actions`: append-only. */
    val auditLog: MutableList<ModerationAction> = mutableListOf()
    val deleted: MutableSet<Uuid> = linkedSetOf()
    val suspendedUntil: MutableMap<Uuid, Instant> = linkedMapOf()
    val suggestionLogs: MutableList<StoredSuggestion> = mutableListOf()
    /** (drop, viewer) pairs that already counted toward "seen by". */
    val views: MutableSet<Pair<Uuid, Uuid>> = linkedSetOf()
    val impactNotices: MutableList<ImpactNotice> = mutableListOf()
    val mediaBytes: MutableMap<String, ByteArray> = linkedMapOf()
    var pendingEmail: String? = null
    private var shortIds: Int = 0

    fun verdict(dropId: Uuid, viewerId: Uuid?): ConfirmationVerdict? =
        viewerId?.let { confirmations[dropId to it]?.verdict }

    /** One inbox row per author, drop, and UTC day. Imported drops still notify when they have an author. */
    fun noteHelped(drop: StoredDrop, kind: String, at: Instant) {
        val day = at.toLocalDateTime(TimeZone.UTC).date.toString()
        val index = impactNotices.indexOfFirst {
            it.recipientId == drop.authorId && it.dropId == drop.id && it.noticeDay == day
        }
        if (index < 0) {
            impactNotices += ImpactNotice(
                id = Uuid.random(),
                dropId = drop.id,
                shortId = drop.shortId,
                title = drop.title,
                noticeDay = day,
                viewEvents = if (kind == "view") 1 else 0,
                confirmEvents = if (kind == "confirm") 1 else 0,
                readAt = null,
                createdAt = at,
                updatedAt = at,
                recipientId = drop.authorId,
            )
            return
        }
        val current = impactNotices[index]
        impactNotices[index] = current.copy(
            viewEvents = current.viewEvents + if (kind == "view") 1 else 0,
            confirmEvents = current.confirmEvents + if (kind == "confirm") 1 else 0,
            updatedAt = at,
        )
    }

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
        if (viewerId != null && (viewerId to drop.id) in hidden) return false
        return true
    }

    fun blocked(a: Uuid, b: Uuid): Boolean = (a to b) in blocks || (b to a) in blocks

    fun log(actorId: Uuid?, targetType: String, targetId: Uuid, action: ModerationActionType, reason: String?, now: Instant) {
        auditLog += ModerationAction(auditLog.size + 1L, actorId, targetType, targetId, action, reason, now)
    }
}

class StoredSuggestion(
    val id: Uuid,
    val userId: Uuid,
    val inputs: kotlinx.serialization.json.JsonObject,
    val suggested: List<String>,
    val chosenSlug: String?,
    val createdAt: Instant,
)

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
    var viewCount: Long = 0,
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

private fun StoredDrop.toCard(
    viewerId: Uuid?,
    distance: Double?,
    myVerdict: ConfirmationVerdict?,
): DropCard {
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
        tags = if (revealed) tags else null,
        status = status,
        isConfirmed = status == DropStatus.CONFIRMED,
        confirmCount = confirmCount,
        denyCount = denyCount,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        createdAt = createdAt,
        authorHandle = if (displayAnonymous && category.allowAnonymousDisplay && !mine) null else authorHandle,
        isMine = mine,
        viewCount = viewCount,
        myVerdict = myVerdict,
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

private const val MAX_PHOTO_BYTES: Int = 10 * 1024 * 1024
