package world.xhere.app.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import world.xhere.app.location.DeviceLocation
import world.xhere.app.location.LocationReading
import world.xhere.app.map.SpotNames
import world.xhere.shared.access.AccessPlaces
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.here.Here
import world.xhere.shared.model.MediaKind
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.voice.HereSpeech
import world.xhere.shared.voice.VoiceAutoPost
import world.xhere.shared.voice.VoiceClips
import world.xhere.shared.voice.VoiceDrops
import world.xhere.shared.voice.VoiceParse
import world.xhere.shared.voice.VoiceQuestion

enum class VoicePhase {
    IDLE,
    LISTENING,
    DENIED,
    CONFIRM,
    SPEAKING,
    POSTED,
    ERROR,
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val transcript: String = "",
    val title: String = "",
    val categorySlug: String = "",
    val body: String = "",
    val question: VoiceQuestion? = null,
    val spoken: String = "",
    /** Null when auto-post is off. Counts down from [VoiceAutoPost.SECONDS]. */
    val secondsLeft: Int? = null,
    val clipSeconds: Double? = null,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Talk to the map. A report becomes a drop at the latest foreground fix.
 * A question is read aloud from the Here Card. Auto-post is off until Settings says otherwise.
 */
class VoiceViewModel(
    private val services: XHereServices,
    private val location: DeviceLocation,
    private val speech: SpeechCapture,
    private val speaker: HereSpeaker,
    private val prefs: VoicePrefs,
    private val refiner: VoiceRefiner,
    private val clips: VoiceClipRecorder,
    private val places: SpotNames,
    private val clock: Clock = Clock.System,
    private val tickMs: Long = 1_000L,
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var pending: VoiceParse? = null
    private var clip: RecordedClip? = null
    private var countdown: Job? = null
    private var recordJob: Job? = null

    fun onPermission(granted: Boolean) {
        if (granted) listen() else _state.update { it.copy(phase = VoicePhase.DENIED, error = null) }
    }

    fun listen() {
        countdown?.cancel()
        speaker.stop()
        viewModelScope.launch {
            _state.update {
                VoiceUiState(phase = VoicePhase.LISTENING)
            }
            val heard = try {
                speech.listen()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (heard.isNullOrBlank()) {
                _state.update { it.copy(phase = VoicePhase.ERROR, error = "voice_empty") }
                return@launch
            }
            val categories = runCatching { services.taxonomy.load().categories }.getOrDefault(emptyList())
            val parse = try {
                refiner.refine(heard, categories)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (parse == null || (!parse.isQuestion && !parse.isDrop)) {
                _state.update { it.copy(phase = VoicePhase.ERROR, transcript = heard, error = "voice_unparsed") }
                return@launch
            }
            if (parse.isQuestion) {
                ask(parse)
            } else {
                showConfirm(parse)
            }
        }
    }

    fun post() {
        val parse = pending ?: return
        if (_state.value.phase != VoicePhase.CONFIRM || _state.value.busy) return
        countdown?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, secondsLeft = null, error = null) }
            val reading = reading()
            if (reading == null) {
                _state.update { it.copy(busy = false, error = "voice_location") }
                return@launch
            }
            try {
                val media = clip?.let { recorded ->
                    val problem = VoiceClips.problem(recorded.mimeType, recorded.bytes.size.toLong(), recorded.durationS)
                    if (problem != null) {
                        _state.update { it.copy(busy = false, error = problem) }
                        return@launch
                    }
                    services.media.uploadVoiceClip(recorded.bytes, recorded.mimeType, recorded.durationS)
                }
                services.drops.createDrop(
                    CreateDropRequest(
                        categorySlug = parse.categorySlug.orEmpty(),
                        latitude = reading.point.latitude,
                        longitude = reading.point.longitude,
                        title = parse.title.orEmpty(),
                        body = parse.body,
                        attributes = VoiceDrops.attributes(parse, clock.now()),
                        tags = emptyList(),
                        revealRadiusM = null,
                        expiresAt = null,
                        displayAnonymous = false,
                        media = listOfNotNull(media?.copy(mediaKind = MediaKind.AUDIO)),
                        acknowledgeGuidelines = true,
                        signal = reading.signal,
                    ),
                )
                pending = null
                clip = null
                _state.update { it.copy(phase = VoicePhase.POSTED, busy = false, secondsLeft = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update { it.copy(busy = false, error = e.code) }
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = "voice_failed") }
            }
        }
    }

    fun cancelCountdown() {
        countdown?.cancel()
        _state.update { it.copy(secondsLeft = null) }
    }

    fun recordClip() {
        if (_state.value.recording || _state.value.phase != VoicePhase.CONFIRM) return
        recordJob = viewModelScope.launch {
            _state.update { it.copy(recording = true, error = null) }
            val recorded = try {
                clips.record()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (recorded == null) {
                _state.update { it.copy(recording = false, error = "voice_clip") }
                return@launch
            }
            val problem = VoiceClips.problem(recorded.mimeType, recorded.bytes.size.toLong(), recorded.durationS)
            if (problem != null) {
                _state.update { it.copy(recording = false, error = problem) }
                return@launch
            }
            clip = recorded
            _state.update { it.copy(recording = false, clipSeconds = recorded.durationS) }
        }
    }

    fun stopClip() {
        clips.stop()
    }

    fun dismiss() {
        countdown?.cancel()
        recordJob?.cancel()
        clips.stop()
        speaker.stop()
        pending = null
        clip = null
        _state.value = VoiceUiState()
    }

    private fun showConfirm(parse: VoiceParse) {
        pending = parse
        clip = null
        _state.update {
            VoiceUiState(
                phase = VoicePhase.CONFIRM,
                transcript = parse.transcript,
                title = parse.title.orEmpty(),
                categorySlug = parse.categorySlug.orEmpty(),
                body = parse.body.orEmpty(),
            )
        }
        if (!prefs.autoPost()) return
        countdown = viewModelScope.launch {
            var elapsed = 0L
            while (elapsed < VoiceAutoPost.SECONDS * 1_000L) {
                val left = VoiceAutoPost.secondsLeft(elapsed)
                _state.update { current ->
                    if (current.phase == VoicePhase.CONFIRM) current.copy(secondsLeft = left) else current
                }
                delay(tickMs)
                elapsed += tickMs
            }
            if (_state.value.phase == VoicePhase.CONFIRM) post()
        }
    }

    private suspend fun ask(parse: VoiceParse) {
        val question = parse.question ?: return
        val reading = reading()
        val point = reading?.point
        if (point == null) {
            _state.update { it.copy(phase = VoicePhase.ERROR, transcript = parse.transcript, error = "voice_location") }
            return
        }
        val cards = runCatching {
            services.drops.hereCard(point, point, radiusM = Here.RADIUS_M, limit = Here.LIMIT)
        }.getOrDefault(emptyList())
        val access = runCatching { services.access.summary(AccessPlaces.cell(point)) }.getOrNull()
        val name = runCatching { places.describe(point)?.name }.getOrNull()
        val script = HereSpeech.script(question, name, cards, access)
        speaker.speak(script)
        _state.update {
            VoiceUiState(
                phase = VoicePhase.SPEAKING,
                transcript = parse.transcript,
                question = question,
                spoken = script,
            )
        }
    }

    /** A short listen for the latest foreground fix. The map already tracks location. */
    private suspend fun reading(): LocationReading? {
        if (!location.hasPermission()) return null
        return withTimeoutOrNull(2_500) { location.fixes().first() }
    }
}
