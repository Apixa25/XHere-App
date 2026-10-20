package world.xhere.shared.data.fake

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.contribution.ContributionCard
import world.xhere.shared.contribution.ContributionFile
import world.xhere.shared.contribution.ContributionMediaPolicy
import world.xhere.shared.contribution.ContributionMediaRef
import world.xhere.shared.contribution.ContributionPlace
import world.xhere.shared.contribution.ContributionRules
import world.xhere.shared.contribution.ContributionSort
import world.xhere.shared.contribution.ContributionTarget
import world.xhere.shared.contribution.ContributionUploadSlot
import world.xhere.shared.contribution.CreatedContribution
import world.xhere.shared.contribution.LivePlace
import world.xhere.shared.contribution.NewContribution
import world.xhere.shared.data.ContributionRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.media.ExifStrip
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.Profile
import world.xhere.shared.model.UserTier

class StoredContribution(
    val id: Uuid,
    val targetKind: String,
    val targetId: Uuid,
    val authorId: Uuid,
    val authorHandle: String,
    val anonymous: Boolean,
    var kind: String,
    var body: String?,
    var status: String,
    val createdAt: Instant,
    val parentId: Uuid? = null,
    var editedAt: Instant? = null,
    val media: List<StoredContributionMedia> = emptyList(),
)

class StoredContributionMedia(
    val id: Uuid,
    val storagePath: String,
    val thumbPath: String?,
    val mimeType: String,
    val byteSize: Int,
    val width: Int?,
    val height: Int?,
    val durationS: Double?,
    val altText: String?,
)

class StoredContributionVote(
    val contributionId: Uuid,
    val voterId: Uuid,
    var value: Int,
    val createdAt: Instant,
)

/**
 * The same doors as the contribution RPCs, without a database.
 * Anonymous cards never carry the author id. The storage path does not either.
 */
internal class MemoryContributions(
    private val store: MemoryStore,
    private val viewer: () -> Profile?,
    private val clock: () -> Instant,
) : ContributionRepository {
    override suspend fun place(targetKind: String, targetId: Uuid): ContributionPlace {
        if (!exists(targetKind, targetId)) {
            throw XHereApiException("place_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        }
        val drop = when (targetKind) {
            ContributionTarget.DROP, ContributionTarget.MARKER, ContributionTarget.OPEN_DATA -> store.drops[targetId]
            else -> null
        }
        return ContributionPlace(
            anonymousAllowed = anonymousAllowed(targetKind, targetId),
            visible = targetVisible(targetKind, targetId, viewer()?.id),
            shareUrl = drop?.shortId?.let { "https://xhere.world/d/$it" },
        )
    }

    override suspend fun list(
        targetKind: String,
        targetId: Uuid,
        sort: ContributionSort,
        offset: Int,
        query: String?,
    ): List<ContributionCard> {
        val me = viewer()
        if (!exists(targetKind, targetId)) {
            throw XHereApiException("place_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        }
        val now = clock()
        val needle = query?.trim()?.lowercase()?.ifEmpty { null }
        val rows = store.contributions.filter { row ->
            row.targetKind == targetKind && row.targetId == targetId && readable(row, me?.id) &&
                (needle == null || row.body?.lowercase()?.contains(needle) == true)
        }
        val byId = rows.associateBy { it.id }
        val roots = rows.filter { it.parentId == null || byId[it.parentId] == null }
        val orderedRoots = ContributionRules.ordered(roots, sort, { it.createdAt }, { row ->
            val votes = store.contributionVotes.filter { it.contributionId == row.id }
            ContributionRules.wilson(votes.count { it.value == 1 }, votes.count { it.value == -1 })
        })
        val page = orderedRoots.drop(offset.coerceAtLeast(0)).take(ContributionRules.PAGE_SIZE)
        val pageIds = page.map { it.id }.toSet()
        val shown = rows.filter { it.id in pageIds || it.parentId in pageIds }
        val rootOrder = page.withIndex().associate { it.value.id to it.index }
        return shown.map { card(it, me?.id, now) }.sortedWith(
            compareBy<ContributionCard> { rootOrder[it.parentId ?: it.id] ?: 0 }
                .thenBy { it.parentId != null }
                .thenBy { it.createdAt },
        )
    }

    override suspend fun add(request: NewContribution): CreatedContribution {
        val profile = requireViewer()
        if (!targetVisible(request.targetKind, request.targetId, profile.id)) {
            throw XHereApiException("place_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        }
        if (request.anonymous && !anonymousAllowed(request.targetKind, request.targetId)) {
            throw XHereApiException("anonymous_not_allowed", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val body = request.body?.trim()?.ifEmpty { null }
        if (request.kind == "text" && (body == null || body.length > 500)) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION, detail = "text is 1..500 characters")
        }
        if (request.kind !in setOf("text", "photo", "video")) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val dayAgo = clock() - 1.days
        val recent = store.contributions.count { it.authorId == profile.id && it.createdAt > dayAgo }
        if (recent >= dailyCap(profile.tier)) {
            throw XHereApiException("rate_limited", sqlState = XHereApiException.SQL_RATE_LIMITED, detail = "contributions per day")
        }
        val files = request.files.ifEmpty { listOfNotNull(request.file) }
        if (files.size > ContributionRules.MAX_FILES) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION, detail = "at most 6 files")
        }
        if (request.kind == "text" && files.isNotEmpty()) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION)
        }
        if (request.kind != "text" && files.isEmpty()) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION, detail = "a photo or video needs a file")
        }
        if (request.parentId != null) {
            val parent = store.contributions.firstOrNull { it.id == request.parentId && readable(it, profile.id) }
            if (parent == null || parent.targetKind != request.targetKind || parent.targetId != request.targetId) {
                throw XHereApiException("contribution_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
            }
        }
        files.forEach { file ->
            problem(request.kind, file, file.thumbnail ?: request.thumbnail)?.let { detail ->
                throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION, detail = detail)
            }
        }
        val storedKind = storedKind(request.kind, files)
        val status = if (held(request.targetKind, request.targetId, profile.tier)) "pending_review" else "active"
        val id = Uuid.random()
        val media = files.map { file ->
            reserved(id, profile.id, file, file.thumbnail ?: request.thumbnail)
        }
        store.contributions += StoredContribution(
            id = id,
            targetKind = request.targetKind,
            targetId = request.targetId,
            authorId = profile.id,
            authorHandle = profile.handle,
            anonymous = request.anonymous,
            kind = storedKind,
            body = body,
            status = status,
            createdAt = clock(),
            parentId = request.parentId,
            media = media,
        )
        return CreatedContribution(
            id = id,
            status = status,
            anonymous = request.anonymous,
            media = media.map {
                ContributionUploadSlot(
                    id = it.id,
                    storagePath = it.storagePath,
                    thumbPath = it.thumbPath,
                    mimeType = it.mimeType,
                )
            },
        )
    }

    override suspend fun edit(id: Uuid, body: String) {
        val profile = requireViewer()
        val row = store.contributions.firstOrNull { it.id == id && it.authorId == profile.id && it.status != "removed" }
            ?: throw XHereApiException("contribution_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        val cleaned = body.trim().ifEmpty { null }
        if (row.kind == "text" && (cleaned == null || cleaned.length > 500)) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION)
        }
        if (cleaned != null && cleaned.length > 500) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION)
        }
        row.body = cleaned
        row.editedAt = clock()
    }

    override suspend fun vote(id: Uuid, value: Int) {
        val profile = requireViewer()
        if (value !in setOf(-1, 0, 1)) {
            throw XHereApiException("invalid_vote", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val row = store.contributions.firstOrNull { it.id == id && readable(it, profile.id) }
            ?: throw XHereApiException("contribution_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        if (row.authorId == profile.id) {
            throw XHereApiException("cannot_vote_self", sqlState = XHereApiException.SQL_VALIDATION)
        }
        if (row.kind !in setOf("photo", "video", "media") || row.status != "active") {
            throw XHereApiException(
                if (row.kind !in setOf("photo", "video", "media")) "votes_are_for_media" else "contribution_not_found",
                sqlState = if (row.kind !in setOf("photo", "video", "media")) XHereApiException.SQL_VALIDATION else XHereApiException.SQL_NOT_FOUND,
            )
        }
        val existing = store.contributionVotes.firstOrNull { it.contributionId == id && it.voterId == profile.id }
        if (value == 0 || existing?.value == value) {
            store.contributionVotes.removeAll { it.contributionId == id && it.voterId == profile.id }
            store.contributionDownvotes.removeAll { it.first == id && it.second == profile.id }
            refreshVoteState(row)
            return
        }
        if (existing == null) {
            val hourAgo = clock() - 1.hours
            val recent = store.contributionVotes.count { it.voterId == profile.id && it.createdAt > hourAgo }
            if (recent >= 120) {
                throw XHereApiException("rate_limited", sqlState = XHereApiException.SQL_RATE_LIMITED, detail = "120 new votes per hour")
            }
            store.contributionVotes += StoredContributionVote(id, profile.id, value, clock())
        } else {
            existing.value = value
        }
        store.contributionDownvotes.removeAll { it.first == id && it.second == profile.id }
        if (value == -1) {
            store.contributionDownvotes += Triple(id, profile.id, row.authorId)
        }
        refreshVoteState(row)
    }

    override suspend fun delete(id: Uuid) {
        val profile = requireViewer()
        val row = store.contributions.firstOrNull { it.id == id && it.authorId == profile.id && it.status != "removed" }
            ?: throw XHereApiException("contribution_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        row.status = "removed"
    }

    override suspend fun liveInView(box: BoundingBox): List<LivePlace> {
        val me = viewer()?.id
        val now = clock()
        return store.contributions.mapNotNull { row ->
            if (row.status != "active" || !ContributionRules.pulses(row.kind, row.createdAt, now)) return@mapNotNull null
            if (!readable(row, me)) return@mapNotNull null
            val point = point(row.targetKind, row.targetId) ?: return@mapNotNull null
            if (point.longitude !in box.minLongitude..box.maxLongitude || point.latitude !in box.minLatitude..box.maxLatitude) {
                return@mapNotNull null
            }
            LivePlace(row.targetKind, row.targetId, point.latitude, point.longitude)
        }.distinctBy { it.targetKind to it.targetId }
    }

    private fun card(row: StoredContribution, viewerId: Uuid?, now: Instant): ContributionCard {
        val votes = store.contributionVotes.filter { it.contributionId == row.id }
        val up = votes.count { it.value == 1 }
        val down = votes.count { it.value == -1 }
        val mine = votes.firstOrNull { it.voterId == viewerId }?.value
        return ContributionCard(
            id = row.id,
            targetKind = row.targetKind,
            targetId = row.targetId,
            kind = row.kind,
            body = row.body,
            createdAt = row.createdAt,
            live = ContributionRules.live(row.createdAt, now),
            upCount = up,
            downCount = down,
            score = ContributionRules.wilson(up, down),
            myVote = mine,
            authorHandle = if (row.anonymous) ContributionRules.ANONYMOUS_NAME else row.authorHandle,
            anonymous = row.anonymous,
            isMine = viewerId != null && row.authorId == viewerId,
            collapsed = ContributionRules.collapsed(up, down),
            media = row.media.map {
                ContributionMediaRef(
                    id = it.id,
                    storagePath = it.storagePath,
                    thumbPath = it.thumbPath,
                    mimeType = it.mimeType,
                    byteSize = it.byteSize,
                    width = it.width,
                    height = it.height,
                    durationS = it.durationS,
                    altText = it.altText,
                )
            },
            parentId = row.parentId,
            editedAt = row.editedAt,
        )
    }

    private fun refreshVoteState(row: StoredContribution) {
        val votes = store.contributionVotes.filter { it.contributionId == row.id }
        val up = votes.count { it.value == 1 }
        val down = votes.count { it.value == -1 }
        if (row.status == "active" && ContributionRules.autoHidden(up, down)) {
            row.status = "hidden"
        }
    }

    private fun storedKind(requested: String, files: List<ContributionFile>): String {
        if (requested == "text") return "text"
        val images = files.count { it.mimeType.substringBefore(';').trim().lowercase().startsWith("image/") }
        val videos = files.count { it.mimeType.substringBefore(';').trim().lowercase().startsWith("video/") }
        return when {
            images > 0 && videos > 0 -> "media"
            videos > 0 -> "video"
            else -> "photo"
        }
    }

    private fun reserved(
        contributionId: Uuid,
        authorId: Uuid,
        file: ContributionFile,
        thumbnail: ByteArray?,
    ): StoredContributionMedia {
        val mediaId = Uuid.random()
        val ext = when (file.mimeType.substringBefore(';').trim().lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/webm" -> "webm"
            "video/mp4" -> "mp4"
            else -> "jpg"
        }
        val path = "c/$contributionId/$mediaId.$ext"
        if (authorId.toString() in path) {
            throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val bytes = if (file.mimeType.startsWith("image/")) ExifStrip.strip(file.bytes, file.mimeType) else file.bytes
        store.contributionBytes[path] = bytes
        val video = file.mimeType.substringBefore(';').trim().lowercase().startsWith("video/")
        val thumbBytes = thumbnail?.takeIf { it.isNotEmpty() }
        val thumb = if (video || thumbBytes != null) "c/$contributionId/$mediaId.thumb.jpg" else null
        if (thumb != null) {
            store.contributionBytes[thumb] = thumbBytes ?: ByteArray(0)
        }
        return StoredContributionMedia(
            id = mediaId,
            storagePath = path,
            thumbPath = thumb,
            mimeType = file.mimeType.substringBefore(';').trim().lowercase(),
            byteSize = bytes.size,
            width = file.width,
            height = file.height,
            durationS = file.durationS,
            altText = file.altText?.trim()?.ifEmpty { null },
        )
    }

    private fun problem(kind: String, file: ContributionFile, thumbnail: ByteArray?): String? {
        val mime = file.mimeType.substringBefore(';').trim().lowercase()
        val branch = when {
            kind == "photo" -> "photo"
            kind == "video" -> "video"
            mime.startsWith("video/") -> "video"
            else -> "photo"
        }
        return when (branch) {
            "photo" -> when {
                !mime.startsWith("image/") -> "photos are images"
                file.bytes.isEmpty() || file.bytes.size > ContributionMediaPolicy.PHOTO_MAX_BYTES -> "photos are at most 500 KB"
                file.width != null && file.height != null &&
                    maxOf(file.width, file.height) > ContributionMediaPolicy.PHOTO_MAX_EDGE -> "photos are at most 2048 px"
                thumbnail != null && thumbnail.isNotEmpty() && thumbnail.size > ContributionMediaPolicy.THUMB_MAX_BYTES ->
                    "a thumbnail is at most 500 KB"
                else -> null
            }
            "video" -> when {
                mime != "video/mp4" && mime != "video/webm" -> "videos are mp4 or webm"
                file.durationS == null || file.durationS <= 0 || file.durationS > ContributionMediaPolicy.VIDEO_MAX_SECONDS ->
                    "videos are at most 60 seconds and 22 MB"
                file.bytes.isEmpty() || file.bytes.size > ContributionMediaPolicy.VIDEO_MAX_BYTES -> "videos are at most 60 seconds and 22 MB"
                file.width == null || file.height == null ||
                    maxOf(file.width, file.height) > ContributionMediaPolicy.VIDEO_MAX_EDGE -> "videos are at most 720p"
                thumbnail == null || thumbnail.isEmpty() || thumbnail.size > ContributionMediaPolicy.THUMB_MAX_BYTES ->
                    "a video needs a thumbnail"
                else -> null
            }
            else -> "invalid_contribution"
        }
    }

    private fun readable(row: StoredContribution, viewerId: Uuid?): Boolean {
        if (row.status == "removed") return false
        if (!targetVisible(row.targetKind, row.targetId, viewerId)) return false
        if (viewerId != null && row.authorId == viewerId) {
            return row.status in setOf("active", "pending_review", "hidden")
        }
        if (row.status != "active") return false
        if (viewerId != null && store.blocked(viewerId, row.authorId)) return false
        return true
    }

    private fun exists(kind: String, id: Uuid): Boolean = when (kind) {
        ContributionTarget.DROP -> store.drops[id] != null
        ContributionTarget.MARKER -> store.drops[id]?.category?.slug == "historical_marker"
        ContributionTarget.OPEN_DATA -> store.drops[id]?.source == DropSource.OPEN_DATA
        ContributionTarget.THING -> true
        ContributionTarget.CIVIC -> store.civicItems.any { it.id == id }
        else -> false
    }

    private fun dropOpen(drop: world.xhere.shared.data.fake.StoredDrop, viewerId: Uuid?): Boolean {
        if (!store.canView(drop, viewerId, clock())) return false
        if (drop.capsuleMode != null && drop.capsuleOpenedAt == null && drop.authorId != viewerId) return false
        return true
    }

    private fun targetVisible(kind: String, id: Uuid, viewerId: Uuid?): Boolean {
        val now = clock()
        return when (kind) {
            ContributionTarget.DROP -> store.drops[id]?.let { dropOpen(it, viewerId) } == true
            ContributionTarget.MARKER -> {
                val drop = store.drops[id]
                drop != null && drop.category.slug == "historical_marker" && dropOpen(drop, viewerId)
            }
            ContributionTarget.OPEN_DATA -> {
                val drop = store.drops[id]
                drop != null && drop.source == DropSource.OPEN_DATA && dropOpen(drop, viewerId)
            }
            ContributionTarget.THING -> true
            ContributionTarget.CIVIC -> store.civicItems.any { item ->
                item.id == id && (item.expiresAt == null || item.expiresAt > now)
            }
            else -> false
        }
    }

    private fun anonymousAllowed(kind: String, id: Uuid): Boolean = when (kind) {
        ContributionTarget.DROP, ContributionTarget.MARKER, ContributionTarget.OPEN_DATA ->
            store.drops[id]?.category?.allowAnonymousDisplay == true
        ContributionTarget.THING, ContributionTarget.CIVIC -> exists(kind, id)
        else -> false
    }

    private fun held(kind: String, id: Uuid, tier: UserTier): Boolean {
        val mode = store.drops[id]?.category?.moderationMode ?: ModerationMode.POST
        if (kind !in setOf(ContributionTarget.DROP, ContributionTarget.MARKER, ContributionTarget.OPEN_DATA)) return false
        return mode == ModerationMode.PRE || (mode == ModerationMode.PRE_FOR_NEW && tier == UserTier.NEW)
    }

    private fun point(kind: String, id: Uuid): LatLng? = when (kind) {
        ContributionTarget.DROP, ContributionTarget.MARKER, ContributionTarget.OPEN_DATA -> store.drops[id]?.displayLocation
        ContributionTarget.CIVIC -> store.civicItems.firstOrNull { it.id == id }?.location
        else -> null
    }

    private fun dailyCap(tier: UserTier): Int = when (tier) {
        UserTier.NEW -> 10
        UserTier.REGULAR -> 30
        UserTier.TRUSTED -> 60
        UserTier.GUIDE -> 120
    }

    private fun requireViewer(): Profile = viewer()
        ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)
}

internal fun MemoryStore.holdContributionForReports(contributionId: Uuid, openReporters: Int) {
    val row = contributions.firstOrNull { it.id == contributionId && it.status == "active" } ?: return
    val base = when (row.targetKind) {
        ContributionTarget.DROP, ContributionTarget.MARKER, ContributionTarget.OPEN_DATA ->
            drops[row.targetId]?.category?.reportsToHide ?: 3
        else -> 3
    }
    if (openReporters >= base) {
        row.status = "pending_review"
        log(null, "contribution", row.id, world.xhere.shared.model.ModerationActionType.AUTO_HIDE, "report threshold reached", row.createdAt)
    }
}

internal fun Profile.isContributionModerator(): Boolean =
    role == AppRole.MODERATOR || role == AppRole.ADMIN
