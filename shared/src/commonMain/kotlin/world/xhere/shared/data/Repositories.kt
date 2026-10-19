package world.xhere.shared.data

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationIntegrityFlag
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.BlockedUser
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.HiddenDrop
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.PublicProfile
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.CreateDropResult
import world.xhere.shared.here.Here
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.Profile
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.BadgeShelf
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.CheckInResult
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.Walk
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.suggest.CategorySuggestion
import world.xhere.shared.suggest.SuggestionQuery

/** A signed-in session. [accessToken] is omitted from [toString] so it does not land in logs. */
class SessionInfo(
    val userId: Uuid,
    val accessToken: String,
    val email: String?,
) {
    override fun toString(): String = "SessionInfo(userId=$userId, email=$email)"
}

enum class IdentityProvider {
    GOOGLE,
    APPLE,
}

interface AuthRepository {
    suspend fun currentSession(): SessionInfo?

    fun sessionChanges(): Flow<SessionInfo?>

    /** Sends a one-time code. Pair with [verifyEmailOtp]. */
    suspend fun signInWithEmailOtp(email: String)

    suspend fun verifyEmailOtp(email: String, token: String): SessionInfo

    /** Native Google or Apple sign-in. The ID token comes from the platform SDK, not from this module. */
    suspend fun signInWithIdToken(provider: IdentityProvider, idToken: String, nonce: String? = null): SessionInfo

    suspend fun signOut()
}

interface ProfileRepository {
    suspend fun currentProfile(): Profile

    /** Null when no live account has that handle. Used to report or block the author shown on a drop. */
    suspend fun publicProfile(handle: String): PublicProfile?
}

interface TaxonomyRepository {
    /** Groups, categories, content filters, and tier limits. Cached in memory by the Supabase client. */
    suspend fun load(force: Boolean = false): Taxonomy
}

interface DropRepository {
    suspend fun dropsInView(
        box: BoundingBox,
        categories: List<String>? = null,
        zoom: Int = 16,
    ): List<MapFeature>

    suspend fun dropsNearby(
        location: LatLng,
        radiusM: Int = 1_000,
        categories: List<String>? = null,
        limit: Int = 200,
    ): List<DropCard>

    /**
     * Everything visible within a short walk of [spot].
     * Distance is from the tap. A user drop's body stays hidden until [viewer]
     * is inside that drop's reveal radius. Does not count a view.
     */
    suspend fun hereCard(
        spot: LatLng,
        viewer: LatLng? = null,
        radiusM: Int = Here.RADIUS_M,
        limit: Int = Here.LIMIT,
    ): List<DropCard>

    suspend fun dropDetail(dropId: Uuid, location: LatLng? = null, signal: LocationSignal? = null): DropCard

    /** Share-page teaser. Never reveals the body, attributes, media, or tags. */
    suspend fun dropTeaser(shortId: String): DropCard

    suspend fun createDrop(request: CreateDropRequest): CreateDropResult

    suspend fun confirmDrop(
        dropId: Uuid,
        verdict: ConfirmationVerdict,
        location: LatLng,
        note: String? = null,
        signal: LocationSignal? = null,
    ): ConfirmDropResult
}

interface ReportRepository {
    /** Idempotent while the previous report from this user is still open. Returns the report id. */
    suspend fun report(
        targetType: ReportTarget,
        targetId: Uuid,
        reason: ReportReason,
        details: String? = null,
    ): Uuid
}

interface BlockRepository {
    suspend fun blockUser(userId: Uuid)

    suspend fun unblockUser(userId: Uuid)

    /** Newest first. Blocks hide drops in both directions. */
    suspend fun myBlocks(): List<BlockedUser>
}

/**
 * In-app "your drop helped" notes. One row per drop per UTC day.
 * Nothing here sends a push notification.
 */
interface ImpactRepository {
    /** Newest unread notes first, then recent read ones. */
    suspend fun myNotices(limit: Int = 30): List<ImpactNotice>

    /** Marks one note read. A null id marks every unread note. */
    suspend fun markRead(noticeId: Uuid? = null)
}

/** Hide one drop for yourself only. Nobody else is affected, and the author is not told. */
interface HiddenDropRepository {
    suspend fun hideDrop(dropId: Uuid)

    suspend fun unhideDrop(dropId: Uuid)

    /** Newest first. Expired and removed drops drop off the list. */
    suspend fun myHiddenDrops(): List<HiddenDrop>
}

interface AccountRepository {
    /** Soft-deletes the profile. The nightly cron hard-deletes after 30 days. */
    suspend fun deleteMyAccount()

    /** `export_my_data()`: one JSON document (`xhere-export-v1`), pretty-printed. */
    suspend fun exportMyData(): String
}

interface ModerationRepository {
    suspend fun moderateDrop(dropId: Uuid, action: ModerateDropAction, reason: String? = null): DropStatus

    suspend fun resolveReport(reportId: Uuid, status: ReportStatus, note: String? = null)

    /** Pass a null [until] to lift a suspension. */
    suspend fun suspendUser(userId: Uuid, until: Instant?, reason: String? = null)

    /** Pending drops and open reports: sensitive first, then oldest. Throws `not_moderator` otherwise. */
    suspend fun queue(limit: Int = 50): List<ModerationQueueItem>

    /**
     * Recent location soft-flags. Reason codes only, no coordinates.
     * Throws `not_moderator`. Does not suspend anyone.
     */
    suspend fun integrityQueue(limit: Int = 50): List<LocationIntegrityFlag>
}

/**
 * Top-3 category chips for the quick-drop composer (PLAN §4.4, §7 M5).
 * The Supabase client calls the `suggest-category` Edge Function and falls back
 * to the on-device ranker when that call cannot be made. The in-memory fake
 * always uses the on-device ranker.
 */
interface SuggestionRepository {
    suspend fun suggest(query: SuggestionQuery): List<CategorySuggestion>

    /** Records what was offered and what the person picked. Returns the log row id. */
    suspend fun log(query: SuggestionQuery, suggested: List<String>, chosenSlug: String?): Uuid
}

/**
 * Which free live-layer config covers a point, if a region file has been loaded.
 * Null means no row. The client then uses the bundled catalog and coverage boxes.
 */
interface LiveRepository {
    suspend fun regionAt(point: LatLng): world.xhere.shared.live.LiveRegion?
}

/**
 * Cached OpenStreetMap place names. The database stamps attribution.
 * [claimNominatimSlot] is the project gate: one lookup every 1.1 seconds,
 * and the same account cannot take another for 10 seconds. A cache write is
 * limited to one mutating row every 10 seconds and 30 an hour. The in-memory
 * fake does not simulate those clocks. Both paths reject a label that is not
 * a place name.
 */
interface PlaceRepository {
    suspend fun cachedLabel(location: LatLng): PlaceLabel?

    suspend fun claimNominatimSlot(): Boolean

    suspend fun remember(location: LatLng, name: String, address: String?)
}

/**
 * Walks and the starter quest.
 *
 * A walk is an ordered route of places that already exist, configured per region.
 * The starter quest (visit places, confirm a drop, make a drop) is the same in every region.
 * [checkIn] uses the caller's point the way a reveal does, then the server drops the point.
 */
interface WalkRepository {
    /** Active walks whose region box contains [point], plus one the caller has started. */
    suspend fun walksFor(point: LatLng): List<Walk>

    /** Marks the walk as started. Calling it again keeps the same progress. */
    suspend fun start(walkId: Uuid): Walk

    /**
     * Records places inside the reveal radius, and checks off stops on [walkId]
     * when that walk is already started. Does not keep [point].
     */
    suspend fun checkIn(point: LatLng, walkId: Uuid? = null, signal: LocationSignal? = null): CheckInResult

    /** Quests the caller has not dismissed. The global starter quest is in this list. */
    suspend fun quests(): List<QuestProgress>

    /** Hides a quest for this caller. Progress stays on the server. */
    suspend fun dismissQuest(questId: Uuid)
}

/**
 * Founding Locals and time-boxed map campaigns.
 *
 * A campaign is config for one region. The map asks for the viewport.
 * The profile lists every live campaign, because a profile has no map position.
 * Leaderboards return a display name only. Opting out removes the name and keeps the drop in the total.
 * Imported public records never count.
 */
interface CampaignRepository {
    /** Live campaigns whose region overlaps [box]. Signed-out callers get a community total and `myDrops` of 0. */
    suspend fun campaignsFor(box: BoundingBox): List<CampaignProgress>

    /** Every live campaign, with this caller's own count. */
    suspend fun myCampaigns(): List<CampaignProgress>

    suspend fun myBadges(): BadgeShelf

    /** Hides or shows the caller's display name on every campaign board. */
    suspend fun setShowOnLeaderboard(show: Boolean)
}

/** Photo bytes go to Storage only after Exif/XMP is removed. */
interface MediaRepository {
    suspend fun uploadPhoto(bytes: ByteArray, mimeType: String): MediaUpload
}

/** The surface the app injects. Both the Supabase client and the in-memory fake implement it. */
interface XHereServices {
    val auth: AuthRepository
    val profiles: ProfileRepository
    val taxonomy: TaxonomyRepository
    val drops: DropRepository
    val places: PlaceRepository
    val live: LiveRepository
    val suggestions: SuggestionRepository
    val media: MediaRepository
    val reports: ReportRepository
    val blocks: BlockRepository
    val hidden: HiddenDropRepository
    val impact: ImpactRepository
    val walks: WalkRepository
    val campaigns: CampaignRepository
    val account: AccountRepository
    val moderation: ModerationRepository
}

/** Placeholder role check used by screens that should hide moderator tools. The RPC still enforces it. */
fun Profile.canModerate(): Boolean = role == AppRole.MODERATOR || role == AppRole.ADMIN
