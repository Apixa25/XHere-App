package world.xhere.shared.data

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.access.AccessAnswer
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.contribution.ContributionCard
import world.xhere.shared.contribution.ContributionPlace
import world.xhere.shared.contribution.ContributionSort
import world.xhere.shared.contribution.CreatedContribution
import world.xhere.shared.contribution.LivePlace
import world.xhere.shared.contribution.NewContribution
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
import world.xhere.shared.model.AnnouncementPosted
import world.xhere.shared.model.CapsuleCreated
import world.xhere.shared.model.CapsuleRequest
import world.xhere.shared.model.ClaimQueueItem
import world.xhere.shared.model.ClaimTicket
import world.xhere.shared.model.OfficialPlace
import world.xhere.shared.model.OwnDrop
import world.xhere.shared.model.OwnerReply
import world.xhere.shared.model.PlaceCreated
import world.xhere.shared.model.PlaceHours
import world.xhere.shared.model.PlaceMembership
import world.xhere.shared.model.PostcardStub
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.Profile
import world.xhere.shared.reputation.ReputationStanding
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.BadgeShelf
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.CheckInResult
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.Walk
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.ResolvedThing
import world.xhere.shared.model.ThingCard
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

    /**
     * Points, tier, and the public thresholds (`my_reputation`).
     * The level-up sheet reads this. A pinned tier can sit above the score.
     */
    suspend fun reputation(): ReputationStanding

    /**
     * The name shown on drops. [handle] is optional; null keeps the current one.
     * A generated `user_…` handle is not a display name.
     */
    suspend fun setPublicName(displayName: String, handle: String? = null): Profile

    /**
     * Display name, @handle, bio, home town, website, and avatar path.
     * A blank optional field clears it. Only the signed-in owner is updated.
     */
    suspend fun updateProfile(draft: ProfileDraft): Profile

    /**
     * True when [handle] can be saved. The caller's current handle is available.
     * Invalid spelling and a handle someone else has are false.
     */
    suspend fun handleAvailable(handle: String): Boolean
}

/** The edit-profile form. Empty strings clear bio, home town, website, and the picture. */
data class ProfileDraft(
    val displayName: String,
    val handle: String,
    val bio: String = "",
    val homeArea: String = "",
    val website: String = "",
    val avatarPath: String = "",
)

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

    /** Owner-only. [altText] null leaves the photo description alone; blank clears it. */
    suspend fun updateMyDrop(
        id: Uuid,
        title: String,
        body: String?,
        attributes: JsonObject,
        altText: String? = null,
    ): DropCard

    /** Soft-delete. The author can undo for a short window with [restoreMyDrop]. */
    suspend fun deleteMyDrop(id: Uuid)

    suspend fun restoreMyDrop(id: Uuid)

    /** Live drops this account posted. Deleted and removed drops are left out. */
    suspend fun myDrops(): List<OwnDrop>
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

    /** Approve, remove, or restore a community contribution. Throws `not_moderator`. */
    suspend fun moderateContribution(id: Uuid, action: ModerateDropAction, reason: String? = null): String

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
 * Claimed places and time capsules. Billing is not implemented.
 * A locked capsule's story is not on the drop until the server releases it.
 */
interface PlaceDeskRepository {
    suspend fun createUserPlace(name: String, point: LatLng, website: String?, signal: LocationSignal? = null): PlaceCreated

    suspend fun startClaim(anchorDropId: Uuid, website: String?, point: LatLng, signal: LocationSignal? = null): ClaimTicket

    suspend fun proveEmail(claimId: Uuid): ClaimTicket

    suspend fun requestPostcard(claimId: Uuid): PostcardStub

    suspend fun submitForReview(claimId: Uuid, note: String? = null): ClaimTicket

    suspend fun review(claimId: Uuid, approve: Boolean, note: String? = null): ClaimTicket

    suspend fun revoke(claimId: Uuid, reason: String): ClaimTicket

    suspend fun invite(placeId: Uuid, handle: String)

    suspend fun acceptInvite(placeId: Uuid)

    suspend fun setHours(placeId: Uuid, hours: List<PlaceHours>, summary: String?): OfficialPlace

    suspend fun postAnnouncement(
        placeId: Uuid,
        title: String,
        body: String,
        expiresAt: Instant,
        point: LatLng,
        signal: LocationSignal? = null,
    ): AnnouncementPosted

    suspend fun reply(dropId: Uuid, body: String): OwnerReply

    suspend fun repliesFor(dropId: Uuid): List<OwnerReply>

    suspend fun dispute(placeId: Uuid, reason: String, details: String? = null)

    suspend fun reviewQueue(limit: Int = 50): List<ClaimQueueItem>

    suspend fun resolveDispute(disputeId: Uuid, dismiss: Boolean)

    suspend fun officialAt(point: LatLng): OfficialPlace?

    suspend fun officialForDrop(dropId: Uuid): OfficialPlace?

    suspend fun myPlaces(): List<PlaceMembership>

    suspend fun createCapsule(request: CapsuleRequest): CapsuleCreated

    suspend fun openCapsulesHere(point: LatLng, signal: LocationSignal? = null): Int
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

/** Photo bytes go to Storage only after Exif/XMP is removed. Voice clips are a separate short audio file. */
interface MediaRepository {
    /**
     * [capture] is the GPS read from the original JPEG before the phone
     * resized it. Pass it when the bytes no longer contain Exif.
     */
    suspend fun uploadPhoto(bytes: ByteArray, mimeType: String, capture: LatLng? = null): MediaUpload

    /**
     * A compressed clip, at most 30 seconds and 256 KB, in the private `voice-clips` bucket.
     * The path starts with the caller's id, the same rule as photos.
     */
    suspend fun uploadVoiceClip(bytes: ByteArray, mimeType: String, durationS: Double): MediaUpload

    /** A URL the image loader can fetch. Signed when the bucket is private. */
    suspend fun photoUrl(bucket: String, path: String): String

    /**
     * A square picture, about 512 px, jpeg or webp, at most 512 KB.
     * Stored at `avatars/<user id>/<id>.webp` with a new-object upload
     * (`POST`, upsert). Returns the storage path.
     */
    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String

    /**
     * Removes an avatar object after the profile row points somewhere else.
     * A blank path, or a path outside the caller's folder, is ignored.
     * A storage failure does not undo the saved profile.
     */
    suspend fun deleteAvatar(path: String)

    /** Public URL for an avatar path. Null when there is no picture. */
    fun avatarUrl(path: String?): String?
}

/**
 * 3-tap accessibility checks. The public read is the majority, the confidence,
 * and the last confirmed time. Individual notes stay with the person who wrote them.
 */
interface AccessRepository {
    suspend fun submit(
        placeKey: String,
        location: LatLng,
        stepFree: AccessAnswer,
        ramp: AccessAnswer,
        restroom: AccessAnswer,
        notes: String? = null,
        dropId: Uuid? = null,
    ): AccessSummary

    /** Null when nobody has answered for this place. */
    suspend fun summary(placeKey: String): AccessSummary?

    /** One pin per place inside the box. OpenStreetMap rows are included when the region imported them. */
    suspend fun inView(box: BoundingBox): List<AccessSummary>
}

/**
 * Time Machine and "This day here".
 *
 * Both calls read the region cache. They do not call loc.gov. [historyAt]
 * returns every cached sheet for the town, town-level photos, and point
 * photos within 800 m. [clippingsAt] returns newspaper lines whose terms
 * overlap [terms]. The phone ranks those lines for the calendar day.
 */
interface HistoryRepository {
    suspend fun historyAt(point: LatLng): world.xhere.shared.history.HistoryPlace

    suspend fun clippingsAt(point: LatLng, terms: List<String>): List<world.xhere.shared.history.Clipping>
}

/**
 * Objects that speak over BLE. [resolve] and [hear] are rate-limited.
 * [hear] adds to an aggregate count and does not record the caller with the tag.
 */
interface ThingRepository {
    suspend fun create(request: CreateThingRequest): ThingCard

    suspend fun update(thingId: Uuid, request: CreateThingRequest): ThingCard

    suspend fun mine(): List<ThingCard>

    /** Public things only. Unknown keys are omitted. */
    suspend fun resolve(keys: List<String>): List<ResolvedThing>

    /** How many public things had their aggregate count increased. */
    suspend fun hear(keys: List<String>): Int

    suspend fun bindBeacon(thingId: Uuid, key: String)

    /** The https URL written on the optional sticker. */
    suspend fun bindNfc(thingId: Uuid): String

    suspend fun report(thingId: Uuid, reason: ReportReason, details: String? = null): Uuid
}

/**
 * Town Hall records. These are public agendas, hearings, permits, and notices,
 * not drops. [near] is about 300 m. [upcoming] is the town, about 25 km,
 * and includes a row with no pin when that town is the one under the camera.
 */
interface CivicRepository {
    suspend fun near(point: LatLng, radiusM: Int = world.xhere.shared.civic.Civic.NEAR_M): List<world.xhere.shared.civic.CivicItem>

    suspend fun inView(box: BoundingBox): List<world.xhere.shared.civic.CivicItem>

    suspend fun upcoming(point: LatLng, radiusM: Int = world.xhere.shared.civic.Civic.REGION_M): List<world.xhere.shared.civic.CivicItem>

    suspend fun follow(regionId: String, address: String)

    suspend fun unfollow(regionId: String, address: String)

    suspend fun notices(): List<world.xhere.shared.civic.CivicNotice>

    suspend fun markRead(id: Uuid)
}

/**
 * Photos, short videos, and notes on any place.
 * Cards never include an author id. Anonymous posts show "Anonymous".
 */
interface ContributionRepository {
    suspend fun place(targetKind: String, targetId: Uuid): ContributionPlace

    suspend fun list(
        targetKind: String,
        targetId: Uuid,
        sort: ContributionSort = ContributionSort.NEWEST,
        offset: Int = 0,
        query: String? = null,
    ): List<ContributionCard>

    /** Reserves the row and, when files are set, stores the bytes. */
    suspend fun add(request: NewContribution): CreatedContribution

    /** The author changes the words. Sets the edited marker. */
    suspend fun edit(id: Uuid, body: String)

    /**
     * 1 or -1 sets the vote. Repeating the same value removes it.
     * The other direction switches it. 0 removes it. The author cannot vote.
     */
    suspend fun vote(id: Uuid, value: Int)

    suspend fun delete(id: Uuid)

    /** Places with a photo or video from the last 24 hours. No author. */
    suspend fun liveInView(box: BoundingBox): List<LivePlace>
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
    val access: AccessRepository
    val reports: ReportRepository
    val blocks: BlockRepository
    val hidden: HiddenDropRepository
    val impact: ImpactRepository
    val walks: WalkRepository
    val campaigns: CampaignRepository
    val history: HistoryRepository
    val things: ThingRepository
    val account: AccountRepository
    val moderation: ModerationRepository
    val desk: PlaceDeskRepository
    val civic: CivicRepository
    val contributions: ContributionRepository
}

/** Placeholder role check used by screens that should hide moderator tools. The RPC still enforces it. */
fun Profile.canModerate(): Boolean = role == AppRole.MODERATOR || role == AppRole.ADMIN
