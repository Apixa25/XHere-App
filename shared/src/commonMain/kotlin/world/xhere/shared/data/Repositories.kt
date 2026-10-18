package world.xhere.shared.data

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.CreateDropResult
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.Profile
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.Taxonomy
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

    suspend fun dropDetail(dropId: Uuid, location: LatLng? = null): DropCard

    /** Share-page teaser. Never reveals the body for someone else's drop. */
    suspend fun dropTeaser(shortId: String): DropCard

    suspend fun createDrop(request: CreateDropRequest): CreateDropResult

    suspend fun confirmDrop(
        dropId: Uuid,
        verdict: ConfirmationVerdict,
        location: LatLng,
        note: String? = null,
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
}

interface AccountRepository {
    /** Soft-deletes the profile. The nightly cron hard-deletes after 30 days. */
    suspend fun deleteMyAccount()
}

interface ModerationRepository {
    suspend fun moderateDrop(dropId: Uuid, action: ModerateDropAction, reason: String? = null): DropStatus

    suspend fun resolveReport(reportId: Uuid, status: ReportStatus, note: String? = null)

    /** Pass a null [until] to lift a suspension. */
    suspend fun suspendUser(userId: Uuid, until: Instant?, reason: String? = null)
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
    val suggestions: SuggestionRepository
    val media: MediaRepository
    val reports: ReportRepository
    val blocks: BlockRepository
    val account: AccountRepository
    val moderation: ModerationRepository
}

/** Placeholder role check used by screens that should hide moderator tools. The RPC still enforces it. */
fun Profile.canModerate(): Boolean = role == AppRole.MODERATOR || role == AppRole.ADMIN
