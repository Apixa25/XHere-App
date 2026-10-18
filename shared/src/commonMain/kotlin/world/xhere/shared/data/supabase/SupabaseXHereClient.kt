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
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.serializer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import world.xhere.shared.SupabaseConfig
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
import world.xhere.shared.data.TaxonomyCache
import world.xhere.shared.data.TaxonomyRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.remote.CategoryRow
import world.xhere.shared.data.remote.ConfirmDropParams
import world.xhere.shared.data.remote.DropDetailParams
import world.xhere.shared.data.remote.ImpactNoticesParams
import world.xhere.shared.data.remote.MarkImpactReadParams
import world.xhere.shared.data.remote.DropIdParams
import world.xhere.shared.data.remote.HandleParams
import world.xhere.shared.data.remote.QueueParams
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
import world.xhere.shared.json.ExportJson
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.BlockedUser
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.HiddenDrop
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.PublicProfile
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.CreateDropResult
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.Profile
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.TierLimits
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.rules.RuleViolation
import world.xhere.shared.suggest.CategoryRanker
import world.xhere.shared.suggest.CategorySuggestion
import world.xhere.shared.suggest.SuggestionQuery

/**
 * `supabase-kt` repositories over the M1 RPCs.
 *
 * Reads of other people's drops go through `drops_in_view`, `drops_nearby`, `drop_detail`,
 * and `drop_teaser`. Writes go through `create_drop`, `confirm_drop`, `report`, `block_user`,
 * and the rest listed in `supabase/README.md`. The raw `drops` table is not queried: it holds
 * the true position, and RLS only lets the author see their own rows.
 */
class SupabaseXHereClient(
    private val config: SupabaseConfig,
    engine: HttpClientEngine = platformHttpEngine(),
    private val cache: TaxonomyCache = TaxonomyCache(),
) : XHereServices {
    private val edge = HttpClient(platformHttpEngine()) {
        expectSuccess = false
    }
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
    override val suggestions: SuggestionRepository = SuggestionRepo()
    override val media: MediaRepository = MediaRepo()
    override val reports: ReportRepository = ReportRepo()
    override val blocks: BlockRepository = BlockRepo()
    override val hidden: HiddenDropRepository = HiddenRepo()
    override val impact: ImpactRepository = ImpactRepo()
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

        override suspend fun publicProfile(handle: String): PublicProfile? = call {
            supabase.postgrest.rpc("public_profile", HandleParams(handle).rpcJson())
                .decodeList<PublicProfile>()
                .firstOrNull()
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

        override suspend fun myBlocks(): List<BlockedUser> = call {
            supabase.postgrest.rpc("my_blocks").decodeList<BlockedUser>()
        }
    }

    private inner class HiddenRepo : HiddenDropRepository {
        override suspend fun hideDrop(dropId: Uuid) = call {
            supabase.postgrest.rpc("hide_drop", DropIdParams(dropId).rpcJson())
            Unit
        }

        override suspend fun unhideDrop(dropId: Uuid) = call {
            supabase.postgrest.rpc("unhide_drop", DropIdParams(dropId).rpcJson())
            Unit
        }

        override suspend fun myHiddenDrops(): List<HiddenDrop> = call {
            supabase.postgrest.rpc("my_hidden_drops").decodeList<HiddenDrop>()
        }
    }

    private inner class ImpactRepo : ImpactRepository {
        override suspend fun myNotices(limit: Int): List<ImpactNotice> = call {
            supabase.postgrest.rpc(
                "my_impact_notices",
                ImpactNoticesParams(limit.coerceIn(1, 50)).rpcJson(),
            ).decodeList<ImpactNotice>()
        }

        override suspend fun markRead(noticeId: Uuid?) = call {
            supabase.postgrest.rpc("mark_impact_notice_read", MarkImpactReadParams(noticeId).rpcJson())
            Unit
        }
    }

    private inner class AccountRepo : AccountRepository {
        override suspend fun deleteMyAccount() = call {
            supabase.postgrest.rpc("delete_my_account")
            supabase.auth.signOut()
        }

        override suspend fun exportMyData(): String = call {
            val raw = supabase.postgrest.rpc("export_my_data").data
            ExportJson.encodeToString(JsonElement.serializer(), XHereJson.parseToJsonElement(raw))
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
            // p_until has no SQL default, and XHereJson drops nulls, so lifting must send it explicitly.
            val params = SuspendUserParams(userId, until, reason).rpcJson()
            supabase.postgrest.rpc("suspend_user", JsonObject(params + ("p_until" to (params["p_until"] ?: JsonNull))))
            Unit
        }

        override suspend fun queue(limit: Int): List<ModerationQueueItem> = call {
            supabase.postgrest.rpc("moderation_queue", QueueParams(limit).rpcJson()).decodeList<ModerationQueueItem>()
        }
    }

    private inner class SuggestionRepo : SuggestionRepository {
        override suspend fun suggest(query: SuggestionQuery): List<CategorySuggestion> {
            try {
                val response = edge.post(config.url.trimEnd('/') + "/functions/v1/suggest-category") {
                    header(HttpHeaders.Authorization, "Bearer ${bearer() ?: config.anonKey}")
                    header("apikey", config.anonKey)
                    contentType(ContentType.Application.Json)
                    setBody(XHereJson.encodeToString(SuggestBody.serializer(), query.toBody()))
                }
                if (response.status.isSuccess()) {
                    return parseSuggestions(response.bodyAsText())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The phone still ranks locally when the function or the network is down.
            }
            return localSuggest(query)
        }

        override suspend fun log(query: SuggestionQuery, suggested: List<String>, chosenSlug: String?): Uuid = call {
            val raw = supabase.postgrest.rpc(
                "log_category_suggestion",
                LogSuggestionParams(query.toInputs(), suggested, chosenSlug).rpcJson(),
            ).data
            Uuid.parse(raw.trim().trim('"'))
        }

        private suspend fun localSuggest(query: SuggestionQuery): List<CategorySuggestion> {
            val taxonomy = taxonomy.load()
            val nearby = nearbyCounts(query)
            return CategoryRanker.suggest(taxonomy.categories, query, nearby)
        }

        private suspend fun nearbyCounts(query: SuggestionQuery): Map<String, Int> {
            val latitude = query.latitude ?: return emptyMap()
            val longitude = query.longitude ?: return emptyMap()
            return try {
                drops.dropsNearby(LatLng(latitude, longitude), radiusM = 500)
                    .groupingBy { it.categorySlug }
                    .eachCount()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyMap()
            }
        }
    }

    private inner class MediaRepo : MediaRepository {
        override suspend fun uploadPhoto(bytes: ByteArray, mimeType: String): MediaUpload {
            val mime = mimeType.substringBefore(';').trim().lowercase()
            try {
                val response = edge.post(config.url.trimEnd('/') + "/functions/v1/strip-exif") {
                    header(HttpHeaders.Authorization, "Bearer ${bearer() ?: ""}")
                    header("apikey", config.anonKey)
                    contentType(ContentType.parse(mime))
                    setBody(bytes)
                }
                val payload = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    val code = runCatching {
                        XHereJson.parseToJsonElement(payload).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull() ?: "upload_failed"
                    throw XHereApiException(code, detail = "photo upload failed")
                }
                val stored = XHereJson.decodeFromString(StoredPhotoWire.serializer(), payload)
                return MediaUpload(
                    storagePath = stored.storagePath,
                    mediaKind = MediaKind.IMAGE,
                    mimeType = stored.mimeType,
                    byteSize = stored.byteSize,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                throw e
            } catch (e: Throwable) {
                throw XHereApiException("upload_failed", detail = "photo upload failed", cause = e)
            }
        }
    }

    private suspend fun bearer(): String? = supabase.auth.currentSessionOrNull()?.accessToken

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

@Serializable
private data class SuggestBody(
    val text: String,
    val labels: List<String>,
    val lat: Double? = null,
    val lng: Double? = null,
    val hour: Int? = null,
    @kotlinx.serialization.SerialName("recent_slugs") val recentSlugs: List<String> = emptyList(),
)

@Serializable
private data class LogSuggestionParams(
    @kotlinx.serialization.SerialName("p_inputs") val inputs: JsonObject,
    @kotlinx.serialization.SerialName("p_suggested") val suggested: List<String>,
    @kotlinx.serialization.SerialName("p_chosen") val chosen: String? = null,
)

@Serializable
private data class StoredPhotoWire(
    @kotlinx.serialization.SerialName("storage_path") val storagePath: String,
    @kotlinx.serialization.SerialName("mime_type") val mimeType: String,
    @kotlinx.serialization.SerialName("byte_size") val byteSize: Long,
)

private fun SuggestionQuery.toBody(): SuggestBody = SuggestBody(
    text = text,
    labels = labels,
    lat = latitude,
    lng = longitude,
    hour = hour,
    recentSlugs = recentSlugs,
)

private fun SuggestionQuery.toInputs(): JsonObject = buildJsonObject {
    put("text", text)
    put("labels", JsonArray(labels.map { JsonPrimitive(it) }))
    latitude?.let { put("lat", it) }
    longitude?.let { put("lng", it) }
    hour?.let { put("hour", it) }
    put("recent_slugs", JsonArray(recentSlugs.map { JsonPrimitive(it) }))
}

private fun parseSuggestions(payload: String): List<CategorySuggestion> {
    val rows = XHereJson.parseToJsonElement(payload).jsonObject["suggestions"]?.jsonArray ?: return emptyList()
    return rows.map { element ->
        val row = element.jsonObject
        CategorySuggestion(
            slug = row.string("slug"),
            label = row.string("label"),
            score = row["score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
        )
    }
}

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: ""

private fun JsonElement?.asDetail(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> toString()
}
