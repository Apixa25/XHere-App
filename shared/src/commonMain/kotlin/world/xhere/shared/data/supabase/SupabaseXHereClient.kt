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
import io.ktor.client.request.put
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
import world.xhere.shared.civic.CivicItem
import world.xhere.shared.civic.CivicNotice
import world.xhere.shared.civic.CivicType
import world.xhere.shared.data.CivicRepository
import world.xhere.shared.data.PlaceDeskRepository
import world.xhere.shared.data.PlaceRepository
import world.xhere.shared.data.ProfileDraft
import world.xhere.shared.data.ProfileRepository
import world.xhere.shared.data.ReportRepository
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.SuggestionRepository
import world.xhere.shared.data.TaxonomyCache
import world.xhere.shared.data.TaxonomyRepository
import world.xhere.shared.data.ThingRepository
import world.xhere.shared.data.WalkRepository
import world.xhere.shared.contribution.ContributionCard
import world.xhere.shared.contribution.ContributionMediaPolicy
import world.xhere.shared.contribution.ContributionRules
import world.xhere.shared.contribution.ContributionPlace
import world.xhere.shared.contribution.ContributionSort
import world.xhere.shared.contribution.CreatedContribution
import world.xhere.shared.contribution.LivePlace
import world.xhere.shared.contribution.NewContribution
import world.xhere.shared.data.ContributionRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.media.ExifStrip
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
import world.xhere.shared.data.remote.UpdateProfileParams
import world.xhere.shared.data.remote.UpdateMyDropParams
import world.xhere.shared.data.remote.HandleParams
import world.xhere.shared.data.remote.QueueParams
import world.xhere.shared.data.remote.DropTeaserParams
import world.xhere.shared.data.remote.DropsInViewParams
import world.xhere.shared.data.remote.DropsNearbyParams
import world.xhere.shared.data.remote.ModerateContributionParams
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
import world.xhere.shared.data.remote.ContributionIdParams
import world.xhere.shared.data.remote.ContributionMediaWire
import world.xhere.shared.data.remote.ContributionPlaceParams
import world.xhere.shared.data.remote.ContributionVoteParams
import world.xhere.shared.data.remote.ContributionsForParams
import world.xhere.shared.data.remote.CreateContributionParams
import world.xhere.shared.data.remote.EditContributionParams
import world.xhere.shared.data.remote.LivePlacesParams
import world.xhere.shared.data.remote.taxonomyOf
import world.xhere.shared.data.remote.toParams
import world.xhere.shared.json.UuidSerializer
import world.xhere.shared.integrity.LocationIntegrityFlag
import world.xhere.shared.integrity.LocationSignal
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
import world.xhere.shared.media.MediaPaths
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
import world.xhere.shared.profile.ProfileFields
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
    override val civic: CivicRepository = CivicRepo()
    override val contributions: ContributionRepository = ContributionRepo()

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
                // Raw nonce only. GoTrue SHA-256s it to lowercase hex and compares that to the ID token.
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

        override suspend fun updateProfile(draft: ProfileDraft): Profile = call {
            supabase.postgrest.rpc(
                "update_my_profile",
                UpdateProfileParams(
                    displayName = draft.displayName,
                    handle = draft.handle,
                    bio = draft.bio,
                    homeArea = draft.homeArea,
                    website = draft.website,
                    avatarPath = draft.avatarPath,
                ).rpcJson(),
            ).decodeAs<Profile>()
        }

        override suspend fun handleAvailable(handle: String): Boolean = call {
            decode(supabase.postgrest.rpc("handle_available", HandleParams(handle).rpcJson()).data)
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

        override suspend fun moderateContribution(id: Uuid, action: ModerateDropAction, reason: String?): String = call {
            supabase.postgrest.rpc(
                "moderate_contribution",
                ModerateContributionParams(id, action.wire, reason).rpcJson(),
            ).decodeAs<String>()
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
        override suspend fun uploadPhoto(
            bytes: ByteArray,
            mimeType: String,
            capture: LatLng?,
            thumbnail: ByteArray?,
        ): MediaUpload {
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
                storeDropThumb(stored.storagePath, thumbnail)
                val located = capture ?: if (mime == "image/jpeg") ExifGps.read(bytes) else null
                return MediaUpload(
                    storagePath = stored.storagePath,
                    mediaKind = MediaKind.IMAGE,
                    mimeType = stored.mimeType,
                    byteSize = stored.byteSize,
                    captureLat = located?.latitude,
                    captureLng = located?.longitude,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                throw e
            } catch (e: Throwable) {
                throw XHereApiException("upload_failed", detail = "photo upload failed", cause = e)
            }
        }

        /**
         * The list image sits beside the full photo. A failed thumb upload
         * leaves the original in place, and the map falls back to that file.
         */
        private suspend fun storeDropThumb(storagePath: String, thumbnail: ByteArray?) {
            val bytes = thumbnail?.takeIf { it.isNotEmpty() && it.size <= ContributionMediaPolicy.THUMB_MAX_BYTES } ?: return
            val path = MediaPaths.thumbSibling(storagePath) ?: return
            try {
                supabase.storage.from("drop-media").upload(path, bytes) {
                    upsert = false
                    contentType = ContentType.Image.JPEG
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The full photo is already stored.
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

        override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String {
            val mime = mimeType.substringBefore(';').trim().lowercase()
            val extension = when (mime) {
                "image/webp" -> "webp"
                "image/jpeg" -> "jpg"
                else -> throw XHereApiException("unsupported_type", sqlState = XHereApiException.SQL_VALIDATION, detail = mime)
            }
            if (bytes.isEmpty() || bytes.size > ProfileFields.AVATAR_MAX_BYTES) {
                throw XHereApiException("invalid_avatar", sqlState = XHereApiException.SQL_VALIDATION, detail = "avatar must be 1 byte..512 KB")
            }
            // POST /object creates the new uuid path. PUT is update(), and Storage
            // answers 400 not_found when that object does not exist yet.
            val type = when (mime) {
                "image/webp" -> ContentType("image", "webp")
                else -> ContentType.Image.JPEG
            }
            val userId = bearerUserId()
            val path = "$userId/${Uuid.random()}.$extension"
            try {
                supabase.storage.from(ProfileFields.AVATAR_BUCKET).upload(path, bytes) {
                    upsert = true
                    contentType = type
                }
                return path
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                throw e
            } catch (e: Throwable) {
                val reason = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(180)
                throw XHereApiException("upload_failed", detail = reason ?: "avatar upload failed", cause = e)
            }
        }

        override suspend fun deleteAvatar(path: String) {
            val stored = path.trim().trimStart('/')
            if (stored.isEmpty()) return
            val userId = bearerUserId()
            if (!stored.startsWith("$userId/")) return
            try {
                supabase.storage.from(ProfileFields.AVATAR_BUCKET).delete(listOf(stored))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The profile row is already saved. A leftover object can be replaced later.
            }
        }

        override fun avatarUrl(path: String?): String? = ProfileFields.avatarUrl(config.url, path)
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

    private inner class CivicRepo : CivicRepository {
        override suspend fun near(point: LatLng, radiusM: Int): List<CivicItem> = call {
            if (!Geo.isValid(point)) return@call emptyList()
            decodeListOrEmpty<CivicItemWire>(
                supabase.postgrest.rpc(
                    "civic_items_near",
                    CivicNearParams(point.latitude, point.longitude, radiusM).rpcJson(),
                ).data,
            ).map { it.toItem() }
        }

        override suspend fun inView(box: BoundingBox): List<CivicItem> = call {
            if (!Geo.isValidBoundingBox(box)) return@call emptyList()
            decodeListOrEmpty<CivicItemWire>(
                supabase.postgrest.rpc(
                    "civic_items_in_view",
                    CivicBoxParams(box.minLongitude, box.minLatitude, box.maxLongitude, box.maxLatitude).rpcJson(),
                ).data,
            ).map { it.toItem() }
        }

        override suspend fun upcoming(point: LatLng, radiusM: Int): List<CivicItem> = call {
            if (!Geo.isValid(point)) return@call emptyList()
            decodeListOrEmpty<CivicItemWire>(
                supabase.postgrest.rpc(
                    "civic_upcoming",
                    CivicNearParams(point.latitude, point.longitude, radiusM).rpcJson(),
                ).data,
            ).map { it.toItem() }
        }

        override suspend fun follow(regionId: String, address: String) {
            call {
                supabase.postgrest.rpc("follow_civic_address", CivicFollowParams(regionId, address).rpcJson())
                Unit
            }
        }

        override suspend fun unfollow(regionId: String, address: String) {
            call {
                supabase.postgrest.rpc("unfollow_civic_address", CivicFollowParams(regionId, address).rpcJson())
                Unit
            }
        }

        override suspend fun notices(): List<CivicNotice> = call {
            decodeListOrEmpty<CivicNoticeWire>(
                supabase.postgrest.rpc("my_civic_notices", CivicLimitParams(40).rpcJson()).data,
            ).map { it.toNotice() }
        }

        override suspend fun markRead(id: Uuid) {
            call {
                supabase.postgrest.rpc("mark_civic_notice_read", CivicIdParams(id).rpcJson())
                Unit
            }
        }
    }

    private inner class ContributionRepo : ContributionRepository {
        override suspend fun place(targetKind: String, targetId: Uuid): ContributionPlace = call {
            val rows = supabase.postgrest.rpc(
                "contribution_place",
                ContributionPlaceParams(targetKind, targetId).rpcJson(),
            ).decodeList<ContributionPlace>()
            rows.firstOrNull() ?: throw XHereApiException("place_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)
        }

        override suspend fun list(
            targetKind: String,
            targetId: Uuid,
            sort: ContributionSort,
            offset: Int,
            query: String?,
        ): List<ContributionCard> = call {
            supabase.postgrest.rpc(
                "contributions_for",
                ContributionsForParams(
                    targetKind,
                    targetId,
                    sort.wire,
                    ContributionRules.PAGE_SIZE,
                    offset,
                    query?.trim()?.ifEmpty { null },
                ).rpcJson(),
            ).decodeList<ContributionCard>()
        }

        override suspend fun add(request: NewContribution): CreatedContribution = call {
            val prepared = prepare(request)
            val created = decode<CreatedContribution>(
                supabase.postgrest.rpc(
                    "create_contribution",
                    CreateContributionParams(
                        targetKind = request.targetKind,
                        targetId = request.targetId,
                        kind = request.kind,
                        body = request.body,
                        displayAnonymous = request.anonymous,
                        media = prepared?.wire.orEmpty(),
                        parentId = request.parentId,
                    ).rpcJson(),
                ).data,
            )
            try {
                uploadReserved(created, prepared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                runCatching { delete(created.id) }
                throw e
            } catch (e: Throwable) {
                runCatching { delete(created.id) }
                throw XHereApiException("upload_failed", detail = "contribution upload failed", cause = e)
            }
            created
        }

        override suspend fun edit(id: Uuid, body: String) {
            call {
                supabase.postgrest.rpc("edit_contribution", EditContributionParams(id, body).rpcJson())
                Unit
            }
        }

        override suspend fun vote(id: Uuid, value: Int) {
            call {
                supabase.postgrest.rpc("cast_contribution_vote", ContributionVoteParams(id, value).rpcJson())
                Unit
            }
        }

        override suspend fun delete(id: Uuid) {
            call {
                supabase.postgrest.rpc("delete_my_contribution", ContributionIdParams(id).rpcJson())
                Unit
            }
        }

        override suspend fun liveInView(box: BoundingBox): List<LivePlace> = call {
            if (!Geo.isValidBoundingBox(box)) return@call emptyList()
            supabase.postgrest.rpc(
                "live_places_in_view",
                LivePlacesParams(box.minLongitude, box.minLatitude, box.maxLongitude, box.maxLatitude).rpcJson(),
            ).decodeList<LivePlace>()
        }

        private fun prepare(request: NewContribution): PreparedContribution? {
            val files = request.files.ifEmpty { listOfNotNull(request.file) }
            if (files.isEmpty()) return null
            if (files.size > ContributionRules.MAX_FILES) {
                throw XHereApiException("invalid_contribution", sqlState = XHereApiException.SQL_VALIDATION, detail = "at most 6 files")
            }
            val parts = files.map { file ->
                val mime = file.mimeType.substringBefore(';').trim().lowercase()
                val bytes = if (mime.startsWith("image/")) ExifStrip.strip(file.bytes, mime) else file.bytes
                val thumb = file.thumbnail ?: request.thumbnail
                val video = mime.startsWith("video/")
                if (!video && (bytes.isEmpty() || bytes.size > ContributionMediaPolicy.PHOTO_MAX_BYTES)) {
                    throw XHereApiException(
                        "invalid_contribution",
                        sqlState = XHereApiException.SQL_VALIDATION,
                        detail = "photos are at most 500 KB",
                    )
                }
                if (
                    !video && file.width != null && file.height != null &&
                    maxOf(file.width, file.height) > ContributionMediaPolicy.PHOTO_MAX_EDGE
                ) {
                    throw XHereApiException(
                        "invalid_contribution",
                        sqlState = XHereApiException.SQL_VALIDATION,
                        detail = "photos are at most 2048 px",
                    )
                }
                if (thumb != null && thumb.isNotEmpty() && thumb.size > ContributionMediaPolicy.THUMB_MAX_BYTES) {
                    throw XHereApiException(
                        "invalid_contribution",
                        sqlState = XHereApiException.SQL_VALIDATION,
                        detail = "a thumbnail is at most 500 KB",
                    )
                }
                val usableThumb = thumb?.takeIf { it.isNotEmpty() }
                if (video) {
                    val duration = file.durationS
                    if (
                        duration == null || duration <= 0.0 || duration > ContributionMediaPolicy.VIDEO_MAX_SECONDS ||
                        bytes.isEmpty() || bytes.size > ContributionMediaPolicy.VIDEO_MAX_BYTES ||
                        file.width == null || file.height == null ||
                        maxOf(file.width, file.height) > ContributionMediaPolicy.VIDEO_MAX_EDGE ||
                        usableThumb == null
                    ) {
                        throw XHereApiException(
                            "invalid_contribution",
                            sqlState = XHereApiException.SQL_VALIDATION,
                            detail = "videos are at most 60 seconds, 720p, and 22 MB",
                        )
                    }
                }
                PreparedPart(
                    bytes = bytes,
                    mime = mime,
                    thumb = usableThumb,
                    wire = ContributionMediaWire(
                        mimeType = mime,
                        byteSize = bytes.size,
                        width = file.width,
                        height = file.height,
                        durationS = file.durationS,
                        altText = file.altText,
                        thumbByteSize = usableThumb?.size,
                    ),
                )
            }
            return PreparedContribution(parts)
        }

        private suspend fun uploadReserved(created: CreatedContribution, prepared: PreparedContribution?) {
            val parts = prepared?.parts.orEmpty()
            created.media.forEachIndexed { index, slot ->
                val file = parts.getOrNull(index) ?: return@forEachIndexed
                putContributionObject(slot.storagePath, file.bytes, file.mime)
                val thumbPath = slot.thumbPath
                val thumb = file.thumb
                if (thumbPath != null && thumb != null) {
                    putContributionObject(thumbPath, thumb, "image/jpeg")
                }
            }
        }

        private suspend fun putContributionObject(path: String, bytes: ByteArray, mime: String) {
            val userId = bearerUserId()
            if (!contributionPath(path) || path.contains(userId)) {
                throw XHereApiException("invalid_media", sqlState = XHereApiException.SQL_VALIDATION, detail = "storage path")
            }
            val base = config.url.trimEnd('/')
            val token = bearer() ?: ""
            val posted = edge.post("$base/functions/v1/store-contribution-media") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", config.anonKey)
                header("x-storage-path", path)
                contentType(ContentType.parse(mime))
                setBody(bytes)
            }
            if (posted.status.value == 404) {
                val direct = edge.post("$base/storage/v1/object/contribution-media/$path") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("apikey", config.anonKey)
                    header("x-upsert", "false")
                    contentType(ContentType.parse(mime))
                    setBody(bytes)
                }
                if (!direct.status.isSuccess()) {
                    throw XHereApiException("upload_failed", detail = "contribution upload failed")
                }
                return
            }
            if (!posted.status.isSuccess()) {
                throw XHereApiException("upload_failed", detail = "contribution upload failed")
            }
        }
    }
}

private val CONTRIBUTION_FILE = Regex(
    "^c/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|mp4|webm)$",
)
private val CONTRIBUTION_THUMB = Regex(
    "^c/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.thumb\\.jpg$",
)

private fun contributionPath(path: String): Boolean = CONTRIBUTION_FILE.matches(path) || CONTRIBUTION_THUMB.matches(path)

private data class PreparedPart(
    val bytes: ByteArray,
    val mime: String,
    val thumb: ByteArray?,
    val wire: ContributionMediaWire,
)

private data class PreparedContribution(
    val parts: List<PreparedPart>,
) {
    val wire: List<ContributionMediaWire> get() = parts.map { it.wire }
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

@Serializable
private data class CivicNearParams(
    @kotlinx.serialization.SerialName("p_lat") val lat: Double,
    @kotlinx.serialization.SerialName("p_lng") val lng: Double,
    @kotlinx.serialization.SerialName("p_radius_m") val radiusM: Int,
)

@Serializable
private data class CivicBoxParams(
    @kotlinx.serialization.SerialName("p_min_lng") val minLng: Double,
    @kotlinx.serialization.SerialName("p_min_lat") val minLat: Double,
    @kotlinx.serialization.SerialName("p_max_lng") val maxLng: Double,
    @kotlinx.serialization.SerialName("p_max_lat") val maxLat: Double,
)

@Serializable
private data class CivicFollowParams(
    @kotlinx.serialization.SerialName("p_region_id") val regionId: String,
    @kotlinx.serialization.SerialName("p_address") val address: String,
)

@Serializable
private data class CivicLimitParams(
    @kotlinx.serialization.SerialName("p_limit") val limit: Int,
)

@Serializable
private data class CivicIdParams(
    @Serializable(with = UuidSerializer::class)
    @kotlinx.serialization.SerialName("p_id") val id: Uuid,
)

@Serializable
private data class CivicItemWire(
    @Serializable(with = UuidSerializer::class) val id: Uuid,
    @kotlinx.serialization.SerialName("region_id") val regionId: String,
    @kotlinx.serialization.SerialName("item_type") val itemType: String,
    val title: String,
    val summary: String? = null,
    @kotlinx.serialization.SerialName("occurs_on") val occursOn: String? = null,
    @kotlinx.serialization.SerialName("body_name") val bodyName: String? = null,
    @kotlinx.serialization.SerialName("source_url") val sourceUrl: String,
    @kotlinx.serialization.SerialName("address_text") val addressText: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val attribution: String = "",
    val license: String = "",
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String? = null,
    @kotlinx.serialization.SerialName("source_slug") val sourceSlug: String = "",
    @kotlinx.serialization.SerialName("distance_m") val distanceM: Int? = null,
) {
    fun toItem(): CivicItem = CivicItem(
        id = id,
        regionId = regionId,
        itemType = CivicType.fromWire(itemType) ?: CivicType.NOTICE,
        title = title,
        summary = summary,
        occursOn = occursOn,
        bodyName = bodyName,
        sourceUrl = sourceUrl,
        addressText = addressText,
        latitude = lat,
        longitude = lng,
        attribution = attribution,
        license = license,
        expiresAt = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        sourceSlug = sourceSlug,
        distanceM = distanceM,
    )
}

@Serializable
private data class CivicNoticeWire(
    @Serializable(with = UuidSerializer::class) val id: Uuid,
    val title: String,
    @kotlinx.serialization.SerialName("address_text") val addressText: String? = null,
    @kotlinx.serialization.SerialName("source_url") val sourceUrl: String,
    @kotlinx.serialization.SerialName("read_at") val readAt: String? = null,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
) {
    fun toNotice(): CivicNotice = CivicNotice(
        id = id,
        title = title,
        addressText = addressText,
        sourceUrl = sourceUrl,
        readAt = readAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        createdAt = runCatching { Instant.parse(createdAt) }.getOrElse { Instant.parse("1970-01-01T00:00:00Z") },
    )
}
