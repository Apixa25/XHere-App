package world.xhere.app.place

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.CapsuleRequest

data class PlaceDeskUi(
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    /** Shown when the postcard path was asked for. Mail is not sent. */
    val postcard: String? = null,
)

/**
 * Claim a place or leave a time capsule. The story is sent to the server
 * and is not kept on this screen after a save.
 */
class PlaceDeskViewModel(
    private val services: XHereServices,
) : ViewModel() {
    private val _state = MutableStateFlow(PlaceDeskUi())
    val state: StateFlow<PlaceDeskUi> = _state.asStateFlow()

    fun reset() {
        _state.value = PlaceDeskUi()
    }

    fun leaveMemory(
        spot: LatLng,
        title: String,
        body: String,
        mode: String,
        oral: Boolean,
        unlockAt: Instant?,
    ) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, postcard = null) }
            try {
                services.desk.createCapsule(
                    CapsuleRequest(
                        title = title,
                        body = body.trim().ifEmpty { null },
                        latitude = spot.latitude,
                        longitude = spot.longitude,
                        mode = mode,
                        unlockAt = if (mode == "visit") null else unlockAt,
                        oralHistory = oral,
                    ),
                )
                _state.update { it.copy(busy = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update { it.copy(busy = false, error = e.code) }
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = "failed") }
            }
        }
    }

    fun claim(spot: LatLng, name: String, website: String?, anchorDropId: Uuid?, proof: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, postcard = null) }
            try {
                val dropId = anchorDropId ?: services.desk.createUserPlace(
                    name = name,
                    point = spot,
                    website = website,
                    signal = null,
                ).dropId
                val ticket = services.desk.startClaim(dropId, website, spot, null)
                when (proof) {
                    "email" -> {
                        services.desk.proveEmail(ticket.claimId)
                        _state.update { it.copy(busy = false, saved = true) }
                    }
                    "postcard" -> {
                        val stub = services.desk.requestPostcard(ticket.claimId)
                        _state.update {
                            it.copy(busy = false, postcard = if (stub.mailed) "mailed" else "pending_proof")
                        }
                    }
                    else -> {
                        services.desk.submitForReview(ticket.claimId, null)
                        _state.update { it.copy(busy = false, saved = true) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update { it.copy(busy = false, error = e.code) }
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = "failed") }
            }
        }
    }
}
