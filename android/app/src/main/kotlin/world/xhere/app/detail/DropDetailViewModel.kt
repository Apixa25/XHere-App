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
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.share.ShareLinks

enum class DetailNotice {
    TOO_FAR,
    RATE_LIMITED,
    FAILED,
    UNAVAILABLE,
}

data class DetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val categoryLabel: String = "",
    val icon: String = "📍",
    val authorHandle: String? = null,
    val approximate: Boolean = false,
    val revealRadiusM: Int = 0,
    val confirmBar: Int = 3,
    val distanceM: Double? = null,
    val showBody: Boolean = false,
    val body: String? = null,
    val isConfirmed: Boolean = false,
    val confirmCount: Int = 0,
    val denyCount: Int = 0,
    val lastConfirmedAt: Instant? = null,
    val isMine: Boolean = false,
    val canAnswer: Boolean = false,
    val answering: Boolean = false,
    val locationOff: Boolean = false,
    val listening: Boolean = false,
    val notice: DetailNotice? = null,
    val shareUrl: String = "",
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
    private var lastFix: LatLng? = null
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
        val here = lastFix ?: return
        viewModelScope.launch {
            _state.update { it.copy(answering = true, notice = null) }
            try {
                services.drops.confirmDrop(id, verdict, here)
                val detailed = services.drops.dropDetail(id, here)
                card = detailed
                publish()
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
            card = teaser
            publish()
            if (teaser.isMine) {
                card = services.drops.dropDetail(teaser.id, null)
                publish()
            }
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

    private fun onFix(fix: LatLng) {
        val here = place ?: return
        lastFix = fix
        val next = Geo.distanceMeters(fix, here)
        val wasInside = distance?.let { it <= revealRadius }
        distance = next
        val nowInside = next <= revealRadius
        publish()
        if (mine) return
        if (fetchedInside != null && wasInside == nowInside) return
        val id = dropId ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val detailed = services.drops.dropDetail(id, fix)
                if (lastFix != fix) return@launch
                card = detailed
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
        val insideReveal = distance != null && distance!! <= current.revealRadiusM
        val showBody = current.isMine || (current.revealed && insideReveal)
        val live = current.status == DropStatus.ACTIVE || current.status == DropStatus.CONFIRMED
        val canAnswer = !current.isMine &&
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
                approximate = current.locationPrecisionM > 0,
                revealRadiusM = current.revealRadiusM,
                confirmBar = category?.confirmationsToConfirm ?: 3,
                distanceM = distance,
                showBody = showBody,
                body = if (showBody) current.body else null,
                isConfirmed = current.isConfirmed,
                confirmCount = current.confirmCount,
                denyCount = current.denyCount,
                lastConfirmedAt = current.lastConfirmedAt,
                isMine = current.isMine,
                canAnswer = canAnswer,
                answering = false,
                locationOff = !location.hasPermission(),
                listening = previous.listening,
                notice = null,
                shareUrl = ShareLinks.url(current.shortId),
            )
        }
    }

    private fun noticeFor(error: XHereApiException): DetailNotice = when (error.code) {
        "too_far" -> DetailNotice.TOO_FAR
        "rate_limited" -> DetailNotice.RATE_LIMITED
        "drop_not_found" -> DetailNotice.UNAVAILABLE
        else -> DetailNotice.FAILED
    }
}
