package world.xhere.shared.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.serializer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.AccountRepository
import world.xhere.shared.data.AuthRepository
import world.xhere.shared.data.BlockRepository
import world.xhere.shared.data.DropRepository
import world.xhere.shared.data.IdentityProvider
import world.xhere.shared.data.ModerationRepository
import world.xhere.shared.data.ProfileRepository
import world.xhere.shared.data.ReportRepository
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.TaxonomyCache
import world.xhere.shared.data.TaxonomyRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.remote.CategoryRow
import world.xhere.shared.data.remote.ConfirmDropParams
import world.xhere.shared.data.remote.DropDetailParams
import world.xhere.shared.data.remote.DropTeaserParams
import world.xhere.shared.data.remote.DropsInViewParams
import world.xhere.shared.data.remote.DropsNearbyParams
import world.xhere.shared.data.remote.ModerateDropParams
import world.xhere.shared.data.remote.ReportParams
import world.xhere.shared.data.remote.ResolveReportParams
import world.xhere.shared.data.remote.SuspendUserParams
import world.xhere.shared.data.remote.UserIdParams
import world.xhere.shared.data.remote.taxonomyOf
import world.xhere.shared.data.remote.toParams
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.http.platformHttpEngine
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.ContentFilter
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
import world.xhere.shared.model.TierLimits
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.rules.RuleViolation

/**
 * `supabase-kt` repositories over the M1 RPCs.
 *
 * Reads of other people's drops go through `drops_in_view`, `drops_nearby`, `drop_detail`,
 * and `drop_teaser`. Writes go through `create_drop`, `confirm_drop`, `report`, `block_user`,
 * and the rest listed in `supabase/README.md`. The raw `drops` table is not queried: it holds
 * the true position, and RLS only lets the author see their own rows.
 */
class SupabaseXHereClient(
    config: SupabaseConfig,
    engine: HttpClientEngine = platformHttpEngine(),
    private val cache: TaxonomyCache = TaxonomyCache(),
) : XHereServices {
    val supabase: SupabaseClient = createSupabaseClient(
        supabaseUrl = config.url,
        supabaseKey = config.anonKey,
    ) {
        defaultSerializer = KotlinXSerializer(XHereJson)
        httpEngine = engine
        install(Auth)
        install(Postgrest)
    }

    override val auth: AuthRepository = AuthRepo()
    override val profiles: ProfileRepository = ProfileRepo()
    override val taxonomy: TaxonomyRepository = TaxonomyRepo()
    override val drops: DropRepository = DropRepo()
    override val reports: ReportRepository = ReportRepo()
    override val blocks: BlockRepository = BlockRepo()
    override val account: AccountRepository = AccountRepo()
    override val moderation: ModerationRepository = ModerationRepo()

    private inner class AuthRepo : AuthRepository {
        override suspend fun currentSession(): SessionInfo? = call {
            supabase.auth.currentSessionOrNull()?.toInfo()
        }

        override fun sessionChanges(): Flow<SessionInfo?> =
            supabase.auth.sessionStatus.map { status ->
                when (status) {
                    is SessionStatus.Authenticated -> status.session.toInfo()
                    else -> null
                }
            }

        override suspend fun signInWithEmailOtp(email: String) = call {
            supabase.auth.signInWith(OTP) {
                this.email = email
            }
        }

        override suspend fun verifyEmailOtp(email: String, token: String): SessionInfo = call {
            supabase.auth.verifyEmailOtp(type = io.github.jan.supabase.auth.OtpType.Email.EMAIL, email = email, token = token)
            supabase.auth.currentSessionOrNull()?.toInfo()
                ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)
        }

        override suspend fun signInWithIdToken(
            provider: IdentityProvider,
            idToken: String,
            nonce: String?,
        ): SessionInfo = call {
            supabase.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = when (provider) {
                    IdentityProvider.GOOGLE -> Google
                    IdentityProvider.APPLE -> Apple
                }
                this.nonce = nonce
            }
            supabase.auth.currentSessionOrNull()?.toInfo()
                ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)
        }

        override suspend fun signOut() = call {
            supabase.auth.signOut()
        }
    }

    private inner class ProfileRepo : ProfileRepository {
        override suspend fun currentProfile(): Profile = call {
            val userId = supabase.auth.currentSessionOrNull()?.user?.id
                ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)
            supabase.postgrest.from("profiles").select {
                filter { eq("id", userId) }
            }.decodeSingle()
        }
    }

    private inner class TaxonomyRepo : TaxonomyRepository {
        override suspend fun load(force: Boolean): Taxonomy = cache.getOrLoad(force) {
            call {
                val groups = supabase.postgrest.from("category_groups").select().decodeList<CategoryGroup>()
                val categories = supabase.postgrest.from("categories").select(Columns.raw(CATEGORY_COLUMNS)).decodeList<CategoryRow>()
                val filters = supabase.postgrest.from("content_filters").select(Columns.raw("slug,description,pattern,message")).decodeList<ContentFilter>()
                val limits = supabase.postgrest.from("tier_limits").select().decodeList<TierLimits>()
                taxonomyOf(groups, categories, filters, limits)
            }
        }
    }

    private inner class DropRepo : DropRepository {
        override suspend fun dropsInView(box: BoundingBox, categories: List<String>?, zoom: Int): List<MapFeature> = call {
            if (!Geo.isValidBoundingBox(box)) {
                throw invalid("invalid_bbox", "min must be below max on both axes")
            }
            if (!Geo.isValidZoom(zoom)) throw invalid("invalid_zoom", "zoom must be 0..22")
            supabase.postgrest.rpc(
                "drops_in_view",
                DropsInViewParams(box.minLongitude, box.minLatitude, box.maxLongitude, box.maxLatitude, categories, zoom).rpcJson(),
            ).decodeList<MapFeature>()
        }

        override suspend fun dropsNearby(
            location: LatLng,
            radiusM: Int,
            categories: List<String>?,
            limit: Int,
        ): List<DropCard> = call {
            if (!Geo.isValid(location)) throw invalid("invalid_location", "lat=${location.latitude} lng=${location.longitude}")
            if (!Geo.isValidRadius(radiusM)) throw invalid("invalid_radius", "radius must be 1..50000 m")
            supabase.postgrest.rpc(
                "drops_nearby",
                DropsNearbyParams(location.latitude, location.longitude, radiusM, categories, limit).rpcJson(),
            ).decodeList<DropCard>()
        }

        override suspend fun dropDetail(dropId: Uuid, location: LatLng?): DropCard = call {
            if (location != null && !Geo.isValid(location)) {
                throw invalid("invalid_location", "lat=${location.latitude} lng=${location.longitude}")
            }
            supabase.postgrest.rpc(
                "drop_detail",
                DropDetailParams(dropId, location?.latitude, location?.longitude).rpcJson(),
            ).decodeAs<DropCard>()
        }

        override suspend fun dropTeaser(shortId: String): DropCard = call {
            supabase.postgrest.rpc("drop_teaser", DropTeaserParams(shortId).rpcJson()).decodeAs<DropCard>()
        }

        override suspend fun createDrop(request: CreateDropRequest): CreateDropResult = call {
            supabase.postgrest.rpc("create_drop", request.toParams().rpcJson()).decodeAs<CreateDropResult>()
        }

        override suspend fun confirmDrop(
            dropId: Uuid,
            verdict: ConfirmationVerdict,
            location: LatLng,
            note: String?,
        ): ConfirmDropResult = call {
            if (!Geo.isValid(location)) throw invalid("invalid_location", "lat=${location.latitude} lng=${location.longitude}")
            supabase.postgrest.rpc(
                "confirm_drop",
                ConfirmDropParams(dropId, verdict, location.latitude, location.longitude, note).rpcJson(),
            ).decodeAs<ConfirmDropResult>()
        }
    }

    private inner class ReportRepo : ReportRepository {
        override suspend fun report(
            targetType: ReportTarget,
            targetId: Uuid,
            reason: ReportReason,
            details: String?,
        ): Uuid = call {
            val body = supabase.postgrest.rpc(
                "report",
                ReportParams(targetType, targetId, reason, details).rpcJson(),
            ).data
            Uuid.parse(XHereJson.decodeFromString(kotlinx.serialization.serializer<String>(), body))
        }
    }

    private inner class BlockRepo : BlockRepository {
        override suspend fun blockUser(userId: Uuid) = call {
            supabase.postgrest.rpc("block_user", UserIdParams(userId).rpcJson())
            Unit
        }

        override suspend fun unblockUser(userId: Uuid) = call {
            supabase.postgrest.rpc("unblock_user", UserIdParams(userId).rpcJson())
            Unit
        }
    }

    private inner class AccountRepo : AccountRepository {
        override suspend fun deleteMyAccount() = call {
            supabase.postgrest.rpc("delete_my_account")
            supabase.auth.signOut()
        }
    }

    private inner class ModerationRepo : ModerationRepository {
        override suspend fun moderateDrop(dropId: Uuid, action: ModerateDropAction, reason: String?): DropStatus = call {
            supabase.postgrest.rpc("moderate_drop", ModerateDropParams(dropId, action.wire, reason).rpcJson()).decodeAs<DropStatus>()
        }

        override suspend fun resolveReport(reportId: Uuid, status: ReportStatus, note: String?) = call {
            supabase.postgrest.rpc("resolve_report", ResolveReportParams(reportId, status, note).rpcJson())
            Unit
        }

        override suspend fun suspendUser(userId: Uuid, until: Instant?, reason: String?) = call {
            supabase.postgrest.rpc("suspend_user", SuspendUserParams(userId, until, reason).rpcJson())
            Unit
        }
    }

    private fun io.github.jan.supabase.auth.user.UserSession.toInfo(): SessionInfo = SessionInfo(
        userId = Uuid.parse(user?.id ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)),
        accessToken = accessToken,
        email = user?.email,
    )
}

private const val CATEGORY_COLUMNS =
    "id,group_id,slug,label,description,icon,color,attributes_schema,min_tier_to_post,moderation_mode," +
        "premod_exempt_tier,sensitive,posting_notice,content_filters,default_reveal_m,max_reveal_m," +
        "location_precision_m,default_ttl,max_ttl,max_ttl_new,expires_at_attribute,confirm_extends_ttl," +
        "permanent_when_confirmed,confirmations_to_confirm,denials_to_hide,reports_to_hide,requires_photo," +
        "allow_anonymous_display,active,sort,version"

private fun invalid(code: String, detail: String): XHereApiException =
    XHereApiException.fromViolation(RuleViolation(code, detail = detail))

private suspend fun <T> call(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: XHereApiException) {
        throw e
    } catch (e: PostgrestRestException) {
        throw e.toXHere()
    }
}

private inline fun <reified T> T.rpcJson(): JsonObject =
    XHereJson.encodeToJsonElement(serializer<T>(), this).jsonObject

private fun PostgrestRestException.toXHere(): XHereApiException {
    val slug = message?.substringBefore('\n')?.substringBefore(':')?.trim().orEmpty().ifBlank { "request_failed" }
    return XHereApiException(
        code = slug,
        sqlState = code,
        detail = details.asDetail(),
        hint = hint,
        cause = this,
    )
}

private fun JsonElement?.asDetail(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> toString()
}
