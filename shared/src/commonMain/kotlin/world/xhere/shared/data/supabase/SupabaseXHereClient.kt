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
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
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
import kotlin.time.Duration.Companion.minutes
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
import world.xhere.shared.data.CampaignRepository
import world.xhere.shared.data.AuthRepository
import world.xhere.shared.data.BlockRepository
import world.xhere.shared.data.DropRepository
import world.xhere.shared.data.HiddenDropRepository
import world.xhere.shared.data.HistoryRepository
import world.xhere.shared.data.ImpactRepository
import world.xhere.shared.data.IdentityProvider
import world.xhere.shared.data.MediaRepository
import world.xhere.shared.data.ModerationRepository
import world.xhere.shared.data.LiveRepository
import world.xhere.shared.data.PlaceDeskRepository
import world.xhere.shared.data.PlaceRepository
import world.xhere.shared.data.ProfileRepository
import world.xhere.shared.data.ReportRepository
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.SuggestionRepository
import world.xhere.shared.data.TaxonomyCache
import world.xhere.shared.data.TaxonomyRepository
import world.xhere.shared.data.ThingRepository
import world.xhere.shared.data.WalkRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.remote.CampaignsForMapParams
import world.xhere.shared.data.remote.ClippingsAtParams
import world.xhere.shared.data.remote.HistoryAtParams
import world.xhere.shared.data.remote.CategoryRow
import world.xhere.shared.data.remote.LeaderboardVisibilityParams
import world.xhere.shared.data.remote.CheckInParams
import world.xhere.shared.data.remote.ConfirmDropParams
import world.xhere.shared.data.remote.DismissQuestParams
import world.xhere.shared.data.remote.StartWalkParams
import world.xhere.shared.data.remote.WalksForPointParams
import world.xhere.shared.data.remote.DropDetailParams
import world.xhere.shared.data.remote.HereCardParams
import world.xhere.shared.data.remote.ImpactNoticesParams
import world.xhere.shared.data.remote.PlaceLabelParams
import world.xhere.shared.data.remote.RegionAtParams
import world.xhere.shared.data.remote.RegionLiveRow
import world.xhere.shared.data.remote.RememberPlaceParams
import world.xhere.shared.data.remote.MarkImpactReadParams
import world.xhere.shared.data.remote.DropIdParams
import world.xhere.shared.data.remote.SetPublicNameParams
import world.xhere.shared.data.remote.UpdateMyDropParams
import world.xhere.shared.data.remote.HandleParams
import world.xhere.shared.data.remote.QueueParams
import world.xhere.shared.data.remote.DropTeaserParams
import world.xhere.shared.data.remote.DropsInViewParams
import world.xhere.shared.data.remote.DropsNearbyParams
import world.xhere.shared.data.remote.ModerateDropParams
import world.xhere.shared.data.remote.BindBeaconParams
import world.xhere.shared.data.remote.CreateThingParams
import world.xhere.shared.data.remote.ReportParams
import world.xhere.shared.data.remote.ReportThingParams
import world.xhere.shared.data.remote.ThingIdParams
import world.xhere.shared.data.remote.ThingKeysParams
import world.xhere.shared.data.remote.UpdateThingParams
import world.xhere.shared.data.remote.toThingParams
import world.xhere.shared.data.remote.toUpdateParams
import world.xhere.shared.data.remote.ResolveReportParams
import world.xhere.shared.data.remote.SuspendUserParams
import world.xhere.shared.data.remote.UserIdParams
import world.xhere.shared.data.remote.taxonomyOf
import world.xhere.shared.data.remote.toParams
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.integrity.LocationIntegrityFlag
import world.xhere.shared.integrity.LocationSignal
import io.ktor.client.request.put
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.data.AccessRepository
import world.xhere.shared.data.remote.AnnouncementParams
import world.xhere.shared.data.remote.ClaimIdParams
import world.xhere.shared.data.remote.CreateCapsuleParams
import world.xhere.shared.data.remote.CreatePlaceParams
import world.xhere.shared.data.remote.DisputeClaimParams
import world.xhere.shared.data.remote.InviteCoownerParams
import world.xhere.shared.data.remote.OfficialAtParams
import world.xhere.shared.data.remote.OpenCapsulesParams
import world.xhere.shared.data.remote.OwnerReplyParams
import world.xhere.shared.data.remote.PlaceIdParams
import world.xhere.shared.data.remote.ResolveDisputeParams
import world.xhere.shared.data.remote.ReviewClaimParams
import world.xhere.shared.data.remote.RevokeClaimParams
import world.xhere.shared.data.remote.SetHoursParams
import world.xhere.shared.data.remote.StartClaimParams
import world.xhere.shared.data.remote.SubmitClaimParams
import world.xhere.shared.data.remote.AccessBoxParams
import world.xhere.shared.data.remote.AccessPlaceParams
import world.xhere.shared.data.remote.SubmitAccessParams
import world.xhere.shared.media.ExifGps
import world.xhere.shared.voice.VoiceClips
import world.xhere.shared.geo.Geo
import world.xhere.shared.history.Clipping
import world.xhere.shared.history.HistoryPlace
import world.xhere.shared.geo.LatLng
import world.xhere.shared.live.LiveRegion
import world.xhere.shared.here.Here
import world.xhere.shared.http.platformHttpEngine
import world.xhere.shared.json.ExportJson
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.BadgeShelf
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.CheckInResult
import world.xhere.shared.model.BlockedUser
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.HiddenDrop
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.PublicProfile
import world.xhere.shared.reputation.ReputationStanding
import world.xhere.shared.model.ConfirmDropResult
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.CreateDropResult
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.OwnDrop
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.model.AnnouncementPosted
import world.xhere.shared.model.CapsuleCreated
import world.xhere.shared.model.ClaimQueueItem
import world.xhere.shared.model.ClaimTicket
import world.xhere.shared.model.OfficialPlace
import world.xhere.shared.model.OpenedCount
import world.xhere.shared.model.OwnerReply
import world.xhere.shared.model.PlaceCreated
import world.xhere.shared.model.PlaceHours
import world.xhere.shared.model.PlaceMembership
import world.xhere.shared.model.PostcardStub
import world.xhere.shared.model.CapsuleRequest
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.Profile
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.HearResult
import world.xhere.shared.model.OwnedThings
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ResolvedThing
import world.xhere.shared.model.ThingCard
import world.xhere.shared.model.ThingList
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.Walk
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
        install(Storage)
    }

    override val auth: AuthRepository = AuthRepo()
    override val profiles: ProfileRepository = ProfileRepo()
    override val taxonomy: TaxonomyRepository = TaxonomyRepo()
    override val drops: DropRepository = DropRepo()
    override val places: PlaceRepository = PlaceRepo()
    override val live: LiveRepository = LiveRepo()
    override val suggestions: SuggestionRepository = SuggestionRepo()
    override val media: MediaRepository = MediaRepo()
    override val access: AccessRepository = AccessRepo()
    override val reports: ReportRepository = ReportRepo()
    override val blocks: BlockRepository = BlockRepo()
    override val hidden: HiddenDropRepository = HiddenRepo()
    override val impact: ImpactRepository = ImpactRepo()
    override val walks: WalkRepository = WalkRepo()
    override val campaigns: CampaignRepository = CampaignRepo()
    override val history: HistoryRepository = HistoryRepo()
    override val things: ThingRepository = ThingRepo()
    override val account: AccountRepository = AccountRepo()
    override val moderation: ModerationRepository = ModerationRepo()
    override val desk: PlaceDeskRepository = PlaceDeskRepo()

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

        override suspend fun reputation(): ReputationStanding = call {
            decode(supabase.postgrest.rpc("my_reputation").data)
        }

        override suspend fun publicProfile(handle: String): PublicProfile? = call {
            supabase.postgrest.rpc("public_profile", HandleParams(handle).rpcJson())
                .decodeList<PublicProfile>()
                .firstOrNull()
        }

        override suspend fun setPublicName(displayName: String, handle: String?): Profile = call {
            supabase.postgrest.rpc(
                "set_my_public_name",
                SetPublicNameParams(displayName, handle?.trim()?.ifBlank { null }).rpcJson(),
            ).decodeAs<Profile>()
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

        override suspend fun hereCard(spot: LatLng, viewer: LatLng?, radiusM: Int, limit: Int): List<DropCard> = call {
            if (!Geo.isValid(spot)) throw invalid("invalid_location", "lat=${spot.latitude} lng=${spot.longitude}")
            if (viewer != null && !Geo.isValid(viewer)) {
                throw invalid("invalid_location", "lat=${viewer.latitude} lng=${viewer.longitude}")
            }
            if (radiusM !in Here.MIN_RADIUS_M..Here.MAX_RADIUS_M) {
                throw invalid("invalid_radius", "radius must be 25..1000 m")
            }
            supabase.postgrest.rpc(
                "here_card",
                HereCardParams(
                    lat = spot.latitude,
                    lng = spot.longitude,
                    viewerLat = viewer?.latitude,
                    viewerLng = viewer?.longitude,
                    radiusM = radiusM,
                    limit = limit.coerceIn(1, Here.MAX_LIMIT),
                ).rpcJson(),
            ).decodeList<DropCard>()
        }

        override suspend fun dropDetail(dropId: Uuid, location: LatLng?, signal: LocationSignal?): DropCard = call {
            if (location != null && !Geo.isValid(location)) {
                throw invalid("invalid_location", "lat=${location.latitude} lng=${location.longitude}")
            }
            supabase.postgrest.rpc(
                "drop_detail",
                DropDetailParams(dropId, location?.latitude, location?.longitude, signal).rpcJson(),
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
            signal: LocationSignal?,
        ): ConfirmDropResult = call {
            if (!Geo.isValid(location)) throw invalid("invalid_location", "lat=${location.latitude} lng=${location.longitude}")
            supabase.postgrest.rpc(
                "confirm_drop",
                ConfirmDropParams(dropId, verdict, location.latitude, location.longitude, note, signal).rpcJson(),
            ).decodeAs<ConfirmDropResult>()
        }

        override suspend fun updateMyDrop(
            id: Uuid,
            title: String,
            body: String?,
            attributes: JsonObject,
            altText: String?,
        ): DropCard = call {
            supabase.postgrest.rpc(
                "update_my_drop",
                UpdateMyDropParams(
                    dropId = id,
                    title = title,
                    body = body?.trim().orEmpty(),
                    attributes = attributes,
                    altText = altText,
                ).rpcJson(),
            ).decodeAs<DropCard>()
        }

        override suspend fun deleteMyDrop(id: Uuid) = call {
            supabase.postgrest.rpc("delete_my_drop", DropIdParams(id).rpcJson())
            Unit
        }

        override suspend fun restoreMyDrop(id: Uuid) = call {
            supabase.postgrest.rpc("restore_my_drop", DropIdParams(id).rpcJson())
            Unit
        }

        override suspend fun myDrops(): List<OwnDrop> = call {
            supabase.postgrest.rpc("my_drops").decodeList()
        }
    }

    private inner class LiveRepo : LiveRepository {
        override suspend fun regionAt(point: LatLng): LiveRegion? = call {
            if (!Geo.isValid(point)) return@call null
            supabase.postgrest.rpc(
                "region_live_at",
                RegionAtParams(point.latitude, point.longitude).rpcJson(),
            ).decodeList<RegionLiveRow>().firstOrNull()?.let { row ->
                LiveRegion(
                    id = row.regionId,
                    country = row.country,
                    weatherProvider = row.weatherProvider,
                    name = row.name,
                )
            }
        }
    }

    private inner class PlaceRepo : PlaceRepository {
        override suspend fun cachedLabel(location: LatLng): PlaceLabel? = call {
            if (!Geo.isValid(location)) return@call null
            supabase.postgrest.rpc(
                "place_label",
                PlaceLabelParams(location.latitude, location.longitude).rpcJson(),
            ).decodeList<PlaceLabel>().firstOrNull()
        }

        override suspend fun claimNominatimSlot(): Boolean = call {
            val raw = supabase.postgrest.rpc("claim_nominatim_slot").data
            XHereJson.decodeFromString(serializer<Boolean>(), raw)
        }

        override suspend fun remember(location: LatLng, name: String, address: String?) = call {
            if (!Geo.isValid(location)) throw invalid("invalid_location", "lat=${location.latitude} lng=${location.longitude}")
            supabase.postgrest.rpc(
                "remember_place_label",
                RememberPlaceParams(location.latitude, location.longitude, name, address).rpcJson(),
            )
            Unit
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

    private inner class HistoryRepo : HistoryRepository {
        override suspend fun historyAt(point: LatLng): HistoryPlace = call {
            if (!Geo.isValid(point)) {
                throw invalid("invalid_location", "lat=${point.latitude} lng=${point.longitude}")
            }
            decode(
                supabase.postgrest.rpc(
                    "history_at",
                    HistoryAtParams(point.latitude, point.longitude).rpcJson(),
                ).data,
            )
        }

        override suspend fun clippingsAt(point: LatLng, terms: List<String>): List<Clipping> = call {
            if (!Geo.isValid(point)) {
                throw invalid("invalid_location", "lat=${point.latitude} lng=${point.longitude}")
            }
            val kept = terms.map { it.trim() }.filter { it.length in 3..80 }.distinct().take(8)
            if (kept.isEmpty()) return@call emptyList()
            decode(
                supabase.postgrest.rpc(
                    "clippings_at",
                    ClippingsAtParams(point.latitude, point.longitude, kept).rpcJson(),
                ).data,
            )
        }
    }

    private inner class WalkRepo : WalkRepository {
        override suspend fun walksFor(point: LatLng): List<Walk> = call {
            if (!Geo.isValid(point)) {
                throw invalid("invalid_location", "lat=${point.latitude} lng=${point.longitude}")
            }
            decode(supabase.postgrest.rpc(
                "walks_for_point",
                WalksForPointParams(point.latitude, point.longitude).rpcJson(),
            ).data)
        }

        override suspend fun start(walkId: Uuid): Walk = call {
            decode(supabase.postgrest.rpc("start_walk", StartWalkParams(walkId).rpcJson()).data)
        }

        override suspend fun checkIn(point: LatLng, walkId: Uuid?, signal: LocationSignal?): CheckInResult = call {
            if (!Geo.isValid(point)) {
                throw invalid("invalid_location", "lat=${point.latitude} lng=${point.longitude}")
            }
            decode(supabase.postgrest.rpc(
                "check_in",
                CheckInParams(point.latitude, point.longitude, walkId, signal).rpcJson(),
            ).data)
        }

        override suspend fun quests(): List<QuestProgress> = call {
            decode(supabase.postgrest.rpc("my_quests").data)
        }

        override suspend fun dismissQuest(questId: Uuid) = call {
            supabase.postgrest.rpc("dismiss_quest", DismissQuestParams(questId).rpcJson())
            Unit
        }
    }

    private inner class CampaignRepo : CampaignRepository {
        override suspend fun campaignsFor(box: BoundingBox): List<CampaignProgress> = call {
            if (!Geo.isValidBoundingBox(box)) {
                throw invalid("invalid_location", "box")
            }
            decode(supabase.postgrest.rpc(
                "campaigns_for_map",
                CampaignsForMapParams(box.minLongitude, box.minLatitude, box.maxLongitude, box.maxLatitude).rpcJson(),
            ).data)
        }

        override suspend fun myCampaigns(): List<CampaignProgress> = call {
            decode(supabase.postgrest.rpc("my_campaigns").data)
        }

        override suspend fun myBadges(): BadgeShelf = call {
            decode(supabase.postgrest.rpc("my_badges").data)
        }

        override suspend fun setShowOnLeaderboard(show: Boolean) = call {
            supabase.postgrest.rpc("set_leaderboard_visibility", LeaderboardVisibilityParams(show).rpcJson())
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

        override suspend fun integrityQueue(limit: Int): List<LocationIntegrityFlag> = call {
            supabase.postgrest.rpc("location_integrity_queue", QueueParams(limit.coerceIn(1, 200)).rpcJson())
                .decodeList<LocationIntegrityFlag>()
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
                val capture = if (mime == "image/jpeg") ExifGps.read(bytes) else null
                return MediaUpload(
                    storagePath = stored.storagePath,
                    mediaKind = MediaKind.IMAGE,
                    mimeType = stored.mimeType,
                    byteSize = stored.byteSize,
                    captureLat = capture?.latitude,
                    captureLng = capture?.longitude,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                throw e
            } catch (e: Throwable) {
                throw XHereApiException("upload_failed", detail = "photo upload failed", cause = e)
            }
        }

        override suspend fun uploadVoiceClip(bytes: ByteArray, mimeType: String, durationS: Double): MediaUpload {
            val mime = mimeType.substringBefore(';').trim().lowercase()
            VoiceClips.problem(mime, bytes.size.toLong(), durationS)?.let { code ->
                throw XHereApiException(code, sqlState = XHereApiException.SQL_VALIDATION, detail = code)
            }
            val userId = bearerUserId()
            val path = "$userId/${Uuid.random()}.m4a"
            try {
                val response = edge.put(config.url.trimEnd('/') + "/storage/v1/object/${VoiceClips.BUCKET}/$path") {
                    header(HttpHeaders.Authorization, "Bearer ${bearer() ?: ""}")
                    header("apikey", config.anonKey)
                    header("x-upsert", "false")
                    contentType(ContentType.parse(mime))
                    setBody(bytes)
                }
                if (!response.status.isSuccess()) {
                    throw XHereApiException("upload_failed", detail = "voice clip upload failed")
                }
                return MediaUpload(
                    storageBucket = VoiceClips.BUCKET,
                    storagePath = path,
                    mediaKind = world.xhere.shared.model.MediaKind.AUDIO,
                    mimeType = mime,
                    byteSize = bytes.size.toLong(),
                    durationS = durationS,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                throw e
            } catch (e: Throwable) {
                throw XHereApiException("upload_failed", detail = "voice clip upload failed", cause = e)
            }
        }

        override suspend fun photoUrl(bucket: String, path: String): String = call {
            supabase.storage.from(bucket).createSignedUrl(path, 20.minutes)
        }
    }

    private inner class AccessRepo : AccessRepository {
        override suspend fun submit(
            placeKey: String,
            location: LatLng,
            stepFree: world.xhere.shared.access.AccessAnswer,
            ramp: world.xhere.shared.access.AccessAnswer,
            restroom: world.xhere.shared.access.AccessAnswer,
            notes: String?,
            dropId: Uuid?,
        ): AccessSummary = call {
            decode(
                supabase.postgrest.rpc(
                    "submit_accessibility_check",
                    SubmitAccessParams(
                        placeKey = placeKey,
                        lat = location.latitude,
                        lng = location.longitude,
                        stepFree = stepFree,
                        ramp = ramp,
                        restroom = restroom,
                        notes = notes,
                        dropId = dropId,
                    ).rpcJson(),
                ).data,
            )
        }

        override suspend fun summary(placeKey: String): AccessSummary? = call {
            val raw = supabase.postgrest.rpc("accessibility_summary", AccessPlaceParams(placeKey).rpcJson()).data
            val element = XHereJson.parseToJsonElement(raw)
            val body = if (element is JsonPrimitive && element.isString) {
                XHereJson.parseToJsonElement(element.content)
            } else {
                element
            }
            if (body is JsonNull) null else XHereJson.decodeFromString(AccessSummary.serializer(), body.toString())
        }

        override suspend fun inView(box: BoundingBox): List<AccessSummary> = call {
            supabase.postgrest.rpc(
                "accessibility_in_view",
                AccessBoxParams(box.minLongitude, box.minLatitude, box.maxLongitude, box.maxLatitude).rpcJson(),
            ).decodeList<AccessSummary>()
        }
    }

    private inner class ThingRepo : ThingRepository {
        override suspend fun create(request: CreateThingRequest): ThingCard = call {
            supabase.postgrest.rpc("create_thing", request.toThingParams().rpcJson()).decodeAs<ThingCard>()
        }

        override suspend fun update(thingId: Uuid, request: CreateThingRequest): ThingCard = call {
            supabase.postgrest.rpc("update_thing", request.toUpdateParams(thingId).rpcJson()).decodeAs<ThingCard>()
        }

        override suspend fun mine(): List<ThingCard> = call {
            supabase.postgrest.rpc("my_things").decodeAs<OwnedThings>().things
        }

        override suspend fun resolve(keys: List<String>): List<ResolvedThing> = call {
            supabase.postgrest.rpc("resolve_things", ThingKeysParams(keys).rpcJson()).decodeAs<ThingList>().things
        }

        override suspend fun hear(keys: List<String>): Int = call {
            supabase.postgrest.rpc("hear_things", ThingKeysParams(keys).rpcJson()).decodeAs<HearResult>().counted
        }

        override suspend fun bindBeacon(thingId: Uuid, key: String) = call {
            supabase.postgrest.rpc("bind_ble_tag", BindBeaconParams(thingId, key).rpcJson())
            Unit
        }

        override suspend fun bindNfc(thingId: Uuid): String = call {
            val body = supabase.postgrest.rpc("bind_nfc", ThingIdParams(thingId).rpcJson()).data
            XHereJson.decodeFromString(serializer<String>(), body)
        }

        override suspend fun report(thingId: Uuid, reason: ReportReason, details: String?): Uuid = call {
            val body = supabase.postgrest.rpc(
                "report_thing",
                ReportThingParams(thingId, reason, details).rpcJson(),
            ).data
            Uuid.parse(XHereJson.decodeFromString(serializer<String>(), body))
        }
    }

    private suspend fun bearer(): String? = supabase.auth.currentSessionOrNull()?.accessToken

    private suspend fun bearerUserId(): String {
        val id = supabase.auth.currentSessionOrNull()?.user?.id
        if (id.isNullOrBlank()) {
            throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)
        }
        return id
    }

    private fun io.github.jan.supabase.auth.user.UserSession.toInfo(): SessionInfo = SessionInfo(
        userId = Uuid.parse(user?.id ?: throw XHereApiException("not_authenticated", sqlState = XHereApiException.SQL_PERMISSION)),
        accessToken = accessToken,
        email = user?.email,
    )

    private inner class PlaceDeskRepo : PlaceDeskRepository {
        override suspend fun createUserPlace(name: String, point: LatLng, website: String?, signal: LocationSignal?): PlaceCreated = call {
            supabase.postgrest.rpc(
                "create_user_place",
                CreatePlaceParams(name, point.latitude, point.longitude, website, signal).rpcJson(),
            ).decodeAs()
        }

        override suspend fun startClaim(anchorDropId: Uuid, website: String?, point: LatLng, signal: LocationSignal?): ClaimTicket = call {
            supabase.postgrest.rpc(
                "start_place_claim",
                StartClaimParams(anchorDropId, website, point.latitude, point.longitude, signal).rpcJson(),
            ).decodeAs()
        }

        override suspend fun proveEmail(claimId: Uuid): ClaimTicket = call {
            supabase.postgrest.rpc("prove_claim_email", ClaimIdParams(claimId).rpcJson()).decodeAs()
        }

        override suspend fun requestPostcard(claimId: Uuid): PostcardStub = call {
            supabase.postgrest.rpc("request_claim_postcard", ClaimIdParams(claimId).rpcJson()).decodeAs()
        }

        override suspend fun submitForReview(claimId: Uuid, note: String?): ClaimTicket = call {
            supabase.postgrest.rpc("submit_claim_for_review", SubmitClaimParams(claimId, note).rpcJson()).decodeAs()
        }

        override suspend fun review(claimId: Uuid, approve: Boolean, note: String?): ClaimTicket = call {
            supabase.postgrest.rpc("review_place_claim", ReviewClaimParams(claimId, approve, note).rpcJson()).decodeAs()
        }

        override suspend fun revoke(claimId: Uuid, reason: String): ClaimTicket = call {
            supabase.postgrest.rpc("revoke_place_claim", RevokeClaimParams(claimId, reason).rpcJson()).decodeAs()
        }

        override suspend fun invite(placeId: Uuid, handle: String) {
            call { supabase.postgrest.rpc("invite_place_coowner", InviteCoownerParams(placeId, handle).rpcJson()) }
        }

        override suspend fun acceptInvite(placeId: Uuid) {
            call { supabase.postgrest.rpc("accept_place_invite", PlaceIdParams(placeId).rpcJson()) }
        }

        override suspend fun setHours(placeId: Uuid, hours: List<PlaceHours>, summary: String?): OfficialPlace = call {
            supabase.postgrest.rpc("set_place_hours", SetHoursParams(placeId, hours, summary).rpcJson()).decodeAs()
        }

        override suspend fun postAnnouncement(
            placeId: Uuid,
            title: String,
            body: String,
            expiresAt: Instant,
            point: LatLng,
            signal: LocationSignal?,
        ): AnnouncementPosted = call {
            supabase.postgrest.rpc(
                "post_place_announcement",
                AnnouncementParams(placeId, title, body, expiresAt, point.latitude, point.longitude, signal).rpcJson(),
            ).decodeAs()
        }

        override suspend fun reply(dropId: Uuid, body: String): OwnerReply = call {
            supabase.postgrest.rpc("reply_as_owner", OwnerReplyParams(dropId, body).rpcJson()).decodeAs()
        }

        override suspend fun repliesFor(dropId: Uuid): List<OwnerReply> = call {
            decodeListOrEmpty(supabase.postgrest.rpc("owner_replies_for_drop", DropIdParams(dropId).rpcJson()).data)
        }

        override suspend fun dispute(placeId: Uuid, reason: String, details: String?) {
            call { supabase.postgrest.rpc("dispute_place_claim", DisputeClaimParams(placeId, reason, details).rpcJson()) }
        }

        override suspend fun reviewQueue(limit: Int): List<ClaimQueueItem> = call {
            decodeListOrEmpty(supabase.postgrest.rpc("claim_review_queue", QueueParams(limit.coerceIn(1, 100)).rpcJson()).data)
        }

        override suspend fun resolveDispute(disputeId: Uuid, dismiss: Boolean) {
            call { supabase.postgrest.rpc("resolve_claim_dispute", ResolveDisputeParams(disputeId, dismiss).rpcJson()) }
        }

        override suspend fun officialAt(point: LatLng): OfficialPlace? = call {
            decodeNullable(supabase.postgrest.rpc("official_at", OfficialAtParams(point.latitude, point.longitude).rpcJson()).data)
        }

        override suspend fun officialForDrop(dropId: Uuid): OfficialPlace? = call {
            decodeNullable(supabase.postgrest.rpc("official_for_drop", DropIdParams(dropId).rpcJson()).data)
        }

        override suspend fun myPlaces(): List<PlaceMembership> = call {
            decodeListOrEmpty(supabase.postgrest.rpc("my_places").data)
        }

        override suspend fun createCapsule(request: CapsuleRequest): CapsuleCreated = call {
            supabase.postgrest.rpc(
                "create_capsule",
                CreateCapsuleParams(
                    title = request.title,
                    body = request.body,
                    lat = request.latitude,
                    lng = request.longitude,
                    mode = request.mode,
                    unlockAt = request.unlockAt,
                    oralHistory = request.oralHistory,
                    media = request.media,
                    integrity = request.signal,
                ).rpcJson(),
            ).decodeAs()
        }

        override suspend fun openCapsulesHere(point: LatLng, signal: LocationSignal?): Int = call {
            supabase.postgrest.rpc(
                "open_capsules_here",
                OpenCapsulesParams(point.latitude, point.longitude, signal).rpcJson(),
            ).decodeAs<OpenedCount>().opened
        }
    }
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

/** PostgREST returns a jsonb value as JSON. A quoted string is unwrapped once. */
private inline fun <reified T> decode(raw: String): T {
    val element = XHereJson.parseToJsonElement(raw)
    val body = if (element is JsonPrimitive && element.isString) element.content else raw
    return XHereJson.decodeFromString(body)
}

private inline fun <reified T> decodeNullable(raw: String): T? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "null") return null
    return decode(trimmed)
}

private inline fun <reified T> decodeListOrEmpty(raw: String): List<T> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "[]") return emptyList()
    return decode(trimmed)
}

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
