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
import world.xhere.app.location.DeviceLocation
import world.xhere.app.location.LocationReading
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.safety.SafetyDirectory
import world.xhere.shared.safety.SafetyResources
import world.xhere.shared.share.ShareLinks

enum class DetailNotice {
    TOO_FAR,
    RATE_LIMITED,
    FAILED,
    UNAVAILABLE,
    NOT_COUNTED,
}

enum class SafetyDialog { REPORT_DROP, REPORT_AUTHOR, BLOCK_AUTHOR }

enum class SafetyMessage { REPORTED, REPORTED_HIDDEN, HIDDEN, BLOCKED, FAILED }

data class DetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val categoryLabel: String = "",
    val icon: String = "📍",
    val authorHandle: String? = null,
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
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState(shareUrl = ShareLinks.url(shortId)))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var card: DropCard? = null
    private var taxonomy: Taxonomy? = null
    private var dropId: Uuid? = null
    private var place: LatLng? = null
    private var revealRadius: Int = 0
    private var mine: Boolean = false
    private var distance: Double? = null
    private var fetchedInside: Boolean? = null
    private var lastReading: LocationReading? = null
    private var lastFetchedKey: String? = null
    private var detailJob: Job? = null

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
        viewModelScope.launch {
            _state.update { it.copy(answering = true, notice = null) }
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
            place = teaser.displayLocation
            revealRadius = teaser.revealRadiusM
            mine = teaser.isMine
            // drop_detail is what counts a unique view. The teaser does not.
            card = services.drops.dropDetail(teaser.id, null)
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

    private fun publish() {
        val current = card ?: return
        val publicRecord = current.source == DropSource.OPEN_DATA
        val insideReveal = distance != null && distance!! <= current.revealRadiusM
        val showBody = publicRecord || current.isMine || (current.revealed && insideReveal)
        val live = current.status == DropStatus.ACTIVE || current.status == DropStatus.CONFIRMED
        val canAnswer = !publicRecord &&
            !current.isMine &&
            distance != null &&
            Geo.isWithinConfirmationRange(distance!!, current.revealRadiusM) &&
            live
        val category = taxonomy?.category(current.categorySlug)
        val group = taxonomy?.group(current.groupSlug)
        _state.update { previous ->
            DetailUiState(
                loading = false,
                missing = false,
                title = current.title,
                categoryLabel = category?.label ?: current.categorySlug,
                icon = category?.icon ?: group?.icon ?: "📍",
                authorHandle = current.authorHandle,
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
                locationWithheld = current.locationWithheld && !publicRecord && !current.isMine,
            )
        }
    }

    private fun noticeFor(error: XHereApiException): DetailNotice = when (error.code) {
        "too_far" -> DetailNotice.TOO_FAR
        "rate_limited" -> DetailNotice.RATE_LIMITED
        "drop_not_found" -> DetailNotice.UNAVAILABLE
        else -> DetailNotice.FAILED
    }

    private fun signalKey(reading: LocationReading): String =
        "${reading.signal.mock}|${reading.signal.deviceFix}|${reading.signal.developerOptions}|${reading.signal.accuracyM}"

    private companion object {
        const val SAFETY_GROUP = "safety_alerts"
    }
}
