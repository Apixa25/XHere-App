package world.xhere.app.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ThingCard

data class ThingsUiState(
    val around: Boolean = false,
    val giving: Boolean = false,
    val reporting: Boolean = false,
    val busy: Boolean = false,
    val created: ThingCard? = null,
    val nearby: List<NearbyThing> = emptyList(),
    val selected: NearbyThing? = null,
    val speakingId: String? = null,
    val unresolved: List<String> = emptyList(),
    val errorCode: String? = null,
    val notice: String? = null,
    val nfcWaiting: Boolean = false,
)

class ThingsViewModel(
    private val services: XHereServices,
    private val session: VoiceSession,
) : ViewModel() {
    private val _state = MutableStateFlow(ThingsUiState())
    val state: StateFlow<ThingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { session.nearby.collect { list -> _state.update { it.copy(nearby = list) } } }
        viewModelScope.launch { session.selected.collect { card -> _state.update { it.copy(selected = card) } } }
        viewModelScope.launch { session.speaking.collect { id -> _state.update { it.copy(speakingId = id) } } }
        viewModelScope.launch { session.unresolved.collect { keys -> _state.update { it.copy(unresolved = keys) } } }
    }

    fun openAround() {
        session.watch(VoiceSurface.SHEET)
        _state.update { it.copy(around = true, errorCode = null) }
    }

    fun closeAround() {
        session.unwatch(VoiceSurface.SHEET)
        _state.update { it.copy(around = false, giving = false) }
    }

    fun openGive() {
        _state.update { it.copy(giving = true, errorCode = null, notice = null) }
    }

    fun closeGive() {
        _state.update { it.copy(giving = false, nfcWaiting = false) }
    }

    fun select(thing: NearbyThing) {
        _state.update { it.copy(selected = thing, reporting = false, errorCode = null) }
    }

    fun dismissCard() {
        session.clearSelection()
        _state.update { it.copy(reporting = false) }
    }

    fun openFromLink(voiceId: String) {
        viewModelScope.launch {
            session.open(voiceId)
        }
    }

    fun create(title: String, story: String, latitude: Double?, longitude: Double?) {
        val cleanTitle = title.trim()
        val cleanStory = story.trim()
        if (cleanTitle.length !in 1..80 || cleanStory.length !in 1..2000) {
            _state.update { it.copy(errorCode = "invalid_story") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorCode = null) }
            val card = runCatching {
                services.things.create(
                    CreateThingRequest(
                        title = cleanTitle,
                        story = cleanStory,
                        categorySlug = "note",
                        latitude = latitude,
                        longitude = longitude,
                    ),
                )
            }
            card.onSuccess { created ->
                _state.update { it.copy(busy = false, created = created, notice = "created") }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, errorCode = codeOf(error)) }
            }
        }
    }

    fun bind(key: String) {
        val thing = _state.value.created ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorCode = null) }
            val bound = runCatching { services.things.bindBeacon(thing.id, key) }
            _state.update {
                it.copy(
                    busy = false,
                    errorCode = bound.exceptionOrNull()?.let(::codeOf),
                    notice = if (bound.isSuccess) "bound" else it.notice,
                )
            }
        }
    }

    fun speak() {
        val voiceId = _state.value.created?.voiceId ?: _state.value.selected?.thing?.voiceId ?: return
        session.speak(voiceId)
    }

    fun stopSpeaking() {
        session.stopSpeaking()
    }

    fun startReport() {
        _state.update { it.copy(reporting = true) }
    }

    fun dismissReport() {
        _state.update { it.copy(reporting = false) }
    }

    fun report(reason: ReportReason, details: String?) {
        val thing = _state.value.selected?.thing ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorCode = null) }
            val sent = runCatching { services.things.report(thing.id, reason, details) }
            _state.update {
                it.copy(
                    busy = false,
                    reporting = false,
                    errorCode = sent.exceptionOrNull()?.let(::codeOf),
                    notice = if (sent.isSuccess) "reported" else it.notice,
                )
            }
        }
    }

    fun waitForNfc(waiting: Boolean) {
        _state.update { it.copy(nfcWaiting = waiting) }
    }

    fun nfcFinished(wrote: Boolean) {
        _state.update {
            it.copy(
                nfcWaiting = false,
                notice = if (wrote) "nfc" else "nfc_unavailable",
            )
        }
    }

    private fun codeOf(error: Throwable): String =
        (error as? XHereApiException)?.code ?: "failed"
}
