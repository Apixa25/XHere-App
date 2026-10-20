package world.xhere.app.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import world.xhere.app.location.DeviceLocation
import world.xhere.app.location.LocationReading
import world.xhere.app.map.MapPins
import world.xhere.shared.capsule.Capsules
import world.xhere.shared.access.AccessAnswer
import world.xhere.shared.access.AccessPlaces
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.OfficialPlace
import world.xhere.shared.model.OwnerReply
import world.xhere.shared.model.PlaceHours
import world.xhere.shared.place.PlaceClaims
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.form.AttributeField
import world.xhere.shared.form.AttributeFields
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.profile.AuthorNames
import world.xhere.shared.safety.SafetyDirectory
import world.xhere.shared.safety.SafetyResources
import world.xhere.shared.reputation.Reputation
import world.xhere.shared.share.ShareLinks

enum class DetailNotice {
    TOO_FAR,
    RATE_LIMITED,
    FAILED,
    UNAVAILABLE,
    NOT_COUNTED,
}

enum class SafetyDialog { REPORT_DROP, REPORT_AUTHOR, BLOCK_AUTHOR }

enum class SafetyMessage { REPORTED, REPORTED_HIDDEN, HIDDEN, BLOCKED, DELETED, FAILED }

data class DetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val categoryLabel: String = "",
    val icon: String = "📍",
    val authorHandle: String? = null,
    /** Display name, or a handle the person chose. Null means "A neighbor". */
    val authorLabel: String? = null,
    val authorAvatarUrl: String? = null,
    /** Null when the author is hidden or this is a public record. */
    val authorTierLabel: String? = null,
    /** Empty when the drop is anonymous, a public record, or the author has no badge here. */
    val authorBadges: List<BadgeAward> = emptyList(),
    val approximate: Boolean = false,
    val revealRadiusM: Int = 0,
    val confirmBar: Int = 3,
    val distanceM: Double? = null,
    val showBody: Boolean = false,
    val body: String? = null,
    val isConfirmed: Boolean = false,
    val confirmCount: Int = 0,
    val denyCount: Int = 0,
    val viewCount: Long = 0,
    /** True after this person taps Confirm. One answer per drop. */
    val youConfirmed: Boolean = false,
    val lastConfirmedAt: Instant? = null,
    val isMine: Boolean = false,
    val canAnswer: Boolean = false,
    /** Public records stay readable without walking closer and are not a user's drop. */
    val publicRecord: Boolean = false,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val license: String? = null,
    val attribution: String? = null,
    val answering: Boolean = false,
    val locationOff: Boolean = false,
    val listening: Boolean = false,
    val notice: DetailNotice? = null,
    val shareUrl: String = "",
    /** Safety & Alerts drops show the tip line and 911 footer. */
    val safetyResources: SafetyResources? = null,
    val dialog: SafetyDialog? = null,
    val sending: Boolean = false,
    val safetyMessage: SafetyMessage? = null,
    /** Hidden or blocked from this screen: the drop is gone for this person. */
    val gone: Boolean = false,
    val canUndoHide: Boolean = false,
    /** Close enough, but the location check withheld the body. */
    val locationWithheld: Boolean = false,
    /** Revealed photos. Empty until a signed URL is ready, and empty while the photo is hidden. */
    val photos: List<DropPhotoUi> = emptyList(),
    val editing: Boolean = false,
    val editTitle: String = "",
    val editBody: String = "",
    val editAlt: String = "",
    val editFields: List<AttributeField> = emptyList(),
    val editValues: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val editFailed: Boolean = false,
    val confirmDelete: Boolean = false,
    val canUndoDelete: Boolean = false,
    val undoToken: Int = 0,
    /** Inscription copied from an open-data historical marker, when the source published one. */
    val inscription: String? = null,
    /** HMdb link only. Marker text is never copied from HMdb. */
    val hmdbUrl: String? = null,
    /** Majority for this drop, or the OpenStreetMap place when the drop is an imported pin. */
    val access: AccessSummary? = null,
    val accessBusy: Boolean = false,
    val accessFailed: Boolean = false,
    val officialBadge: String? = null,
    val officialSummary: String? = null,
    val officialHours: String? = null,
    val capsuleLocked: Boolean = false,
    val capsuleCondition: String? = null,
    val ownerReplies: List<String> = emptyList(),
    val canReply: Boolean = false,
    val canDispute: Boolean = false,
)

/**
 * One drop. Distance is recomputed from foreground fixes only: collecting
 * [DeviceLocation.fixes] starts the GPS updates, and cancelling that collect
 * stops them. Nothing is written down.
 *
 * The body is shown only when the latest payload says the drop is revealed
 * and the live distance is still inside the category reveal radius.
 */
class DropDetailViewModel(
    private val shortId: String,
    private val services: XHereServices,
    private val location: DeviceLocation,
    private val mapPins: MapPins,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState(shareUrl = ShareLinks.url(shortId)))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var card: DropCard? = null
    private var taxonomy: Taxonomy? = null
    private var dropId: Uuid? = null
    private var accessKey: String? = null
    private var accessSummary: AccessSummary? = null
    private var place: LatLng? = null
    private var revealRadius: Int = 0
    private var mine: Boolean = false
    private var distance: Double? = null
    private var fetchedInside: Boolean? = null
    private var lastReading: LocationReading? = null
    private var lastFetchedKey: String? = null
    private var detailJob: Job? = null
    private var photoJob: Job? = null
    private var photoKey: String? = null
    private var pendingEdit: Boolean = false
    private var undoArmed: Boolean = false
    private var official: OfficialPlace? = null
    private var replies: List<OwnerReply> = emptyList()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            combine(foreground, kicks.onStart { emit(Unit) }) { active, _ -> active }
                .collectLatest { active ->
                    if (!active || !location.hasPermission()) {
                        _state.update { it.copy(listening = false, locationOff = !location.hasPermission()) }
                        return@collectLatest
                    }
                    _state.update { it.copy(listening = true, locationOff = false) }
                    location.fixes().collect { fix -> onFix(fix) }
                }
        }
    }

    fun setForeground(active: Boolean) {
        foreground.value = active
    }

    /** Call after the person grants location, so the collector starts. */
    fun refreshLocation() {
        kicks.tryEmit(Unit)
    }

    fun confirm(verdict: ConfirmationVerdict) {
        val id = dropId ?: return
        val here = lastReading ?: return
        if (_state.value.answering) return
        _state.update { it.copy(answering = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = services.drops.confirmDrop(id, verdict, here.point, signal = here.signal)
                val detailed = services.drops.dropDetail(id, here.point, here.signal)
                card = detailed
                publish()
                if (!result.countsForCredit) {
                    _state.update { it.copy(notice = DetailNotice.NOT_COUNTED) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update {
                    it.copy(
                        answering = false,
                        notice = noticeFor(e),
                        canAnswer = e.code != "drop_not_found" && it.canAnswer,
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(answering = false, notice = DetailNotice.FAILED) }
            }
        }
    }

    fun startEditing() {
        pendingEdit = true
        openEditorIfReady()
    }

    fun dismissEditing() {
        pendingEdit = false
        _state.update { it.copy(editing = false, editFailed = false, saving = false) }
    }

    fun onEditTitle(value: String) {
        _state.update { it.copy(editTitle = value.take(120), editFailed = false) }
    }

    fun onEditBody(value: String) {
        _state.update { it.copy(editBody = value.take(2000), editFailed = false) }
    }

    fun onEditAlt(value: String) {
        _state.update { it.copy(editAlt = value.take(300), editFailed = false) }
    }

    fun onEditField(name: String, value: String) {
        _state.update { it.copy(editValues = it.editValues + (name to value), editFailed = false) }
    }

    fun saveEdit() {
        val id = dropId ?: return
        val snapshot = _state.value
        if (snapshot.saving || snapshot.editTitle.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, editFailed = false) }
            try {
                val attributes = AttributeFields.toAttributes(snapshot.editFields, snapshot.editValues)
                val updated = services.drops.updateMyDrop(
                    id = id,
                    title = snapshot.editTitle,
                    body = snapshot.editBody,
                    attributes = attributes,
                    altText = snapshot.editAlt,
                )
                card = updated
                photoKey = null
                _state.update { it.copy(editing = false, saving = false, editFailed = false) }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, editFailed = true) }
            }
        }
    }

    fun requestDelete() {
        if (dropId == null || _state.value.publicRecord) return
        _state.update { it.copy(confirmDelete = true) }
    }

    fun dismissDelete() {
        _state.update { it.copy(confirmDelete = false) }
    }

    fun deleteDrop() {
        val id = dropId ?: return
        if (_state.value.publicRecord) return
        undoArmed = true
        mapPins.hide(shortId)
        _state.update {
            it.copy(
                confirmDelete = false,
                gone = true,
                canUndoDelete = true,
                undoToken = it.undoToken + 1,
                safetyMessage = SafetyMessage.DELETED,
                canAnswer = false,
                dialog = null,
            )
        }
        viewModelScope.launch {
            try {
                services.drops.deleteMyDrop(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                undoArmed = false
                mapPins.restore(shortId)
                _state.update { it.copy(gone = false, canUndoDelete = false, safetyMessage = SafetyMessage.FAILED) }
                reload()
            }
        }
    }

    fun undoDelete() {
        if (!undoArmed) return
        undoArmed = false
        val id = dropId ?: return
        _state.update { it.copy(canUndoDelete = false, gone = false, loading = true, safetyMessage = null) }
        viewModelScope.launch {
            try {
                services.drops.restoreMyDrop(id)
                mapPins.restore(shortId)
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, gone = true, safetyMessage = SafetyMessage.FAILED) }
            }
        }
    }

    /** The snackbar window closed. The drop stays deleted. */
    fun expireUndo() {
        if (!undoArmed) return
        undoArmed = false
        _state.update { it.copy(canUndoDelete = false) }
    }

    fun openDialog(dialog: SafetyDialog) {
        _state.update { it.copy(dialog = dialog, safetyMessage = null) }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = null) }
    }

    fun reportDrop(reason: ReportReason, details: String?, alsoHide: Boolean) {
        val id = dropId ?: return
        safetyAction {
            services.reports.report(ReportTarget.DROP, id, reason, details)
            if (alsoHide) {
                services.hidden.hideDrop(id)
                gone(SafetyMessage.REPORTED_HIDDEN, canUndoHide = true)
            } else {
                _state.update { it.copy(sending = false, dialog = null, safetyMessage = SafetyMessage.REPORTED) }
            }
        }
    }

    /** Profiles are reported by id; the drop only carries the handle, so look it up first. */
    fun reportAuthor(reason: ReportReason, details: String?) {
        val handle = card?.authorHandle ?: return
        safetyAction {
            val author = services.profiles.publicProfile(handle) ?: error("no profile for @$handle")
            services.reports.report(ReportTarget.PROFILE, author.id, reason, details)
            _state.update { it.copy(sending = false, dialog = null, safetyMessage = SafetyMessage.REPORTED) }
        }
    }

    fun blockAuthor() {
        val handle = card?.authorHandle ?: return
        safetyAction {
            val author = services.profiles.publicProfile(handle) ?: error("no profile for @$handle")
            services.blocks.blockUser(author.id)
            gone(SafetyMessage.BLOCKED, canUndoHide = false)
        }
    }

    fun hideDrop() {
        val id = dropId ?: return
        safetyAction {
            services.hidden.hideDrop(id)
            gone(SafetyMessage.HIDDEN, canUndoHide = true)
        }
    }

    fun undoHide() {
        val id = dropId ?: return
        safetyAction {
            services.hidden.unhideDrop(id)
            _state.update { it.copy(sending = false, gone = false, canUndoHide = false, safetyMessage = null) }
            fetchedInside = null
            val reading = lastReading
            card = services.drops.dropDetail(id, reading?.point, reading?.signal)
            publish()
        }
    }

    private fun gone(message: SafetyMessage, canUndoHide: Boolean) {
        detailJob?.cancel()
        _state.update {
            it.copy(sending = false, dialog = null, gone = true, canUndoHide = canUndoHide, safetyMessage = message, canAnswer = false)
        }
    }

    private fun safetyAction(block: suspend () -> Unit) {
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, safetyMessage = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(sending = false, dialog = null, safetyMessage = SafetyMessage.FAILED) }
            }
        }
    }

    private suspend fun load() {
        if (shortId.isBlank()) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        try {
            taxonomy = runCatching { services.taxonomy.load() }.getOrNull()
            val teaser = services.drops.dropTeaser(shortId)
            dropId = teaser.id
            val osmKey = if (teaser.categorySlug == "access_place") AccessPlaces.fromOsmUrl(teaser.sourceUrl) else null
            accessKey = osmKey ?: AccessPlaces.drop(teaser.id)
            place = teaser.displayLocation
            revealRadius = teaser.revealRadiusM
            mine = teaser.isMine
            // drop_detail is what counts a unique view. The teaser does not.
            card = services.drops.dropDetail(teaser.id, null)
            refreshPlace(teaser.id)
            accessSummary = runCatching { services.access.summary(accessKey!!) }.getOrNull()
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: XHereApiException) {
            _state.update {
                it.copy(loading = false, missing = true, notice = if (e.isNotFound) null else DetailNotice.FAILED)
            }
        } catch (_: Exception) {
            _state.update { it.copy(loading = false, missing = true, notice = DetailNotice.FAILED) }
        }
    }

    private fun onFix(reading: LocationReading) {
        val here = place ?: return
        val fix = reading.point
        lastReading = reading
        if (_state.value.gone) return
        val next = Geo.distanceMeters(fix, here)
        val wasInside = distance?.let { it <= revealRadius }
        distance = next
        val nowInside = next <= revealRadius
        publish()
        if (mine) return
        val key = signalKey(reading)
        if (fetchedInside != null && wasInside == nowInside && key == lastFetchedKey) return
        val id = dropId ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val detailed = services.drops.dropDetail(id, fix, reading.signal)
                if (lastReading != reading) return@launch
                card = detailed
                refreshPlace(id)
                lastFetchedKey = key
                fetchedInside = detailed.isMine || (distance != null && distance!! <= revealRadius)
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                if (e.isNotFound) {
                    _state.update { it.copy(loading = false, missing = true, canAnswer = false) }
                }
            } catch (_: Exception) {
                _state.update { it.copy(notice = DetailNotice.FAILED) }
            }
        }
    }

    fun retryPhoto(index: Int) {
        val current = _state.value.photos.getOrNull(index) ?: return
        if (current.path.isBlank()) return
        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    photos = state.photos.mapIndexed { i, photo ->
                        if (i == index) photo.copy(url = "", phase = DropPhotoPhase.LOADING) else photo
                    },
                )
            }
            val url = runCatching { services.media.photoUrl(current.bucket, current.path) }.getOrDefault("")
            _state.update { state ->
                state.copy(
                    photos = state.photos.mapIndexed { i, photo ->
                        if (i == index && photo.path == current.path) {
                            photo.copy(
                                url = url,
                                phase = if (url.isBlank()) DropPhotoPhase.FAILED else DropPhotoPhase.READY,
                            )
                        } else {
                            photo
                        }
                    },
                )
            }
        }
    }

    private fun publish() {
        val current = card ?: return
        val publicRecord = current.source == DropSource.OPEN_DATA
        val insideReveal = distance != null && distance!! <= current.revealRadiusM
        val officialReadable = current.source == DropSource.OFFICIAL && current.revealed
        val showBody = !current.capsuleLocked && (
            publicRecord || officialReadable || current.isMine || (current.revealed && insideReveal)
            )
        val live = current.status == DropStatus.ACTIVE || current.status == DropStatus.CONFIRMED
        val canAnswer = !publicRecord &&
            !current.isMine &&
            distance != null &&
            Geo.isWithinConfirmationRange(distance!!, current.revealRadiusM) &&
            live
        val category = taxonomy?.category(current.categorySlug)
        val group = taxonomy?.group(current.groupSlug)
        val images = if (showBody) {
            current.media.orEmpty().filter { it.mediaKind == MediaKind.IMAGE }
        } else {
            emptyList()
        }
        val key = images.joinToString("\n") { "${it.storageBucket}/${it.storagePath}\t${it.altText.orEmpty()}" }
        val keepPhotos = key.isNotEmpty() && key == photoKey
        val pending = images.map { media ->
            DropPhotoUi(
                url = "",
                alt = media.altText?.trim().orEmpty(),
                bucket = media.storageBucket,
                path = media.storagePath,
                phase = DropPhotoPhase.LOADING,
            )
        }
        if (!keepPhotos) {
            photoKey = key
            photoJob?.cancel()
            if (key.isNotEmpty()) {
                photoJob = viewModelScope.launch {
                    val resolved = images.map { media ->
                        val url = runCatching { services.media.photoUrl(media.storageBucket, media.storagePath) }.getOrDefault("")
                        DropPhotoUi(
                            url = url,
                            alt = media.altText?.trim().orEmpty(),
                            bucket = media.storageBucket,
                            path = media.storagePath,
                            phase = if (url.isBlank()) DropPhotoPhase.FAILED else DropPhotoPhase.READY,
                        )
                    }
                    if (photoKey == key) _state.update { it.copy(photos = resolved) }
                }
            }
        }
        _state.update { previous ->
            DetailUiState(
                loading = false,
                missing = false,
                title = current.title,
                categoryLabel = category?.label ?: current.categorySlug,
                icon = category?.icon ?: group?.icon ?: "📍",
                authorHandle = current.authorHandle,
                authorLabel = AuthorNames.label(current.authorDisplayName, current.authorHandle),
                authorAvatarUrl = services.media.avatarUrl(current.authorAvatarPath),
                authorTierLabel = current.authorTier?.let(Reputation::label),
                authorBadges = if (publicRecord) emptyList() else current.authorBadges,
                approximate = current.locationPrecisionM > 0,
                revealRadiusM = current.revealRadiusM,
                confirmBar = category?.confirmationsToConfirm ?: 3,
                distanceM = distance,
                showBody = showBody,
                body = if (showBody) current.body else null,
                isConfirmed = current.isConfirmed,
                confirmCount = current.confirmCount,
                denyCount = current.denyCount,
                viewCount = current.viewCount,
                youConfirmed = current.myVerdict == ConfirmationVerdict.STILL_TRUE,
                lastConfirmedAt = current.lastConfirmedAt,
                isMine = current.isMine,
                canAnswer = canAnswer,
                publicRecord = publicRecord,
                sourceName = current.sourceName,
                sourceUrl = current.sourceUrl,
                license = current.license,
                attribution = current.attribution,
                answering = false,
                locationOff = !location.hasPermission(),
                listening = previous.listening,
                notice = null,
                shareUrl = ShareLinks.url(current.shortId),
                safetyResources = if (current.groupSlug == SAFETY_GROUP) SafetyDirectory.forLocation(current.displayLocation) else null,
                dialog = previous.dialog,
                sending = previous.sending,
                safetyMessage = previous.safetyMessage,
                gone = previous.gone,
                canUndoHide = previous.canUndoHide,
                locationWithheld = current.locationWithheld && !publicRecord && !current.isMine && !current.capsuleLocked,
                photos = if (keepPhotos) previous.photos else pending,
                editing = previous.editing,
                editTitle = previous.editTitle,
                editBody = previous.editBody,
                editAlt = previous.editAlt,
                editFields = previous.editFields,
                editValues = previous.editValues,
                saving = previous.saving,
                editFailed = previous.editFailed,
                confirmDelete = previous.confirmDelete,
                canUndoDelete = previous.canUndoDelete,
                undoToken = previous.undoToken,
                inscription = if (showBody) attributeText(current, "inscription") else null,
                hmdbUrl = if (showBody) attributeText(current, "hmdb_url") else null,
                access = accessSummary,
                accessBusy = previous.accessBusy,
                accessFailed = previous.accessFailed,
                officialBadge = if (current.official || official != null) (official?.badge ?: PlaceClaims.BADGE) else null,
                officialSummary = official?.summary ?: current.officialSummary,
                officialHours = hoursLabel(if (official != null) official!!.hours else current.officialHours),
                capsuleLocked = current.capsuleLocked,
                capsuleCondition = if (current.capsuleLocked) {
                    Capsules.condition(current.capsuleMode, current.capsuleUnlockAt, current.capsuleOpenedAt)
                } else {
                    null
                },
                ownerReplies = replies.map { reply ->
                    val who = reply.authorHandle?.let { "@$it: " }.orEmpty()
                    who + reply.body
                },
                canReply = official?.canReply == true,
                canDispute = official != null && official?.canReply != true,
            )
        }
        openEditorIfReady()
    }

    private fun openEditorIfReady() {
        if (!pendingEdit) return
        val current = card ?: return
        if (!current.isMine || current.source == DropSource.OPEN_DATA) {
            pendingEdit = false
            return
        }
        pendingEdit = false
        val category = taxonomy?.category(current.categorySlug)
        val fields = AttributeFields.fromSchema(category?.attributesSchema ?: kotlinx.serialization.json.JsonObject(emptyMap()))
        val alt = current.media.orEmpty().firstOrNull { it.mediaKind == MediaKind.IMAGE }?.altText.orEmpty()
        _state.update {
            it.copy(
                editing = true,
                editFailed = false,
                editTitle = current.title,
                editBody = current.body.orEmpty(),
                editAlt = alt,
                editFields = fields,
                editValues = AttributeFields.readValues(fields, current.attributes),
            )
        }
    }

    private suspend fun reload() {
        val id = dropId ?: return
        val reading = lastReading
        card = services.drops.dropDetail(id, reading?.point, reading?.signal)
        publish()
    }

    fun sendReply(text: String) {
        val id = dropId ?: return
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                services.desk.reply(id, text)
                refreshPlace(id)
                _state.update { it.copy(sending = false) }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(sending = false, notice = DetailNotice.FAILED) }
            }
        }
    }

    fun disputeClaim() {
        val placeId = official?.placeId ?: return
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                services.desk.dispute(placeId, "Wrong owner", null)
                refreshPlace(dropId ?: return@launch)
                _state.update { it.copy(sending = false) }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(sending = false, notice = DetailNotice.FAILED) }
            }
        }
    }

    private suspend fun refreshPlace(id: Uuid) {
        official = runCatching { services.desk.officialForDrop(id) }.getOrNull()
        replies = runCatching { services.desk.repliesFor(id) }.getOrDefault(emptyList())
    }

    fun submitAccess(stepFree: AccessAnswer, ramp: AccessAnswer, restroom: AccessAnswer, notes: String?) {
        val key = accessKey ?: return
        val here = place ?: return
        if (_state.value.accessBusy) return
        viewModelScope.launch {
            _state.update { it.copy(accessBusy = true, accessFailed = false) }
            try {
                val summary = services.access.submit(
                    placeKey = key,
                    location = here,
                    stepFree = stepFree,
                    ramp = ramp,
                    restroom = restroom,
                    notes = notes?.trim()?.ifEmpty { null },
                    dropId = dropId,
                )
                accessSummary = summary
                _state.update { it.copy(access = summary, accessBusy = false, accessFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(accessBusy = false, accessFailed = true) }
            }
        }
    }

    private fun attributeText(card: DropCard, key: String): String? =
        (card.attributes?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun noticeFor(error: XHereApiException): DetailNotice = when (error.code) {
        "too_far" -> DetailNotice.TOO_FAR
        "rate_limited" -> DetailNotice.RATE_LIMITED
        "drop_not_found" -> DetailNotice.UNAVAILABLE
        else -> DetailNotice.FAILED
    }

    private fun hoursLabel(hours: List<PlaceHours>): String? {
        if (hours.isEmpty()) return null
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return hours.joinToString("\n") { row ->
            val day = names.getOrNull(row.day - 1) ?: row.day.toString()
            "$day ${row.open}–${row.close}"
        }
    }

    private fun signalKey(reading: LocationReading): String =
        "${reading.signal.mock}|${reading.signal.deviceFix}|${reading.signal.developerOptions}|${reading.signal.accuracyM}"

    private companion object {
        const val SAFETY_GROUP = "safety_alerts"
    }
}
