package world.xhere.app.drop

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZonedDateTime
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import world.xhere.app.location.DeviceLocation
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.form.AttributeFields
import world.xhere.shared.form.FieldKind
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Category
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.suggest.CategorySuggestion
import world.xhere.shared.suggest.SuggestionQuery

/**
 * Quick drop (PLAN §7 M5): a photo or a line of text, three category chips,
 * then post. A category with no required fields and no sensitive notice posts
 * on the chip tap, so a photo drop is shutter, then chip.
 */
class DropViewModel(
    private val services: XHereServices,
    private val capture: DropCapture,
    private val labeler: ImageLabeler,
    private val location: DeviceLocation,
) : ViewModel() {
    private val _state = MutableStateFlow(DropUiState())
    val state: StateFlow<DropUiState> = _state.asStateFlow()

    private val recent = mutableListOf<String>()
    private var photo: MediaUpload? = null
    private var labels: List<String> = emptyList()
    private var lastQuery = SuggestionQuery()

    fun onText(value: String) {
        _state.update { it.copy(text = value.take(120)) }
    }

    fun shutter(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val shot = capture.takePhoto(context)
                onPhoto(shot.bytes, shot.mimeType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update { it.copy(busy = false, error = "The camera didn't take that photo. Try again, or type a line instead.") }
            }
        }
    }

    fun onPhoto(bytes: ByteArray, mimeType: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                photo = services.media.uploadPhoto(bytes, mimeType)
                labels = labeler.labels(bytes, mimeType)
                loadSuggestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update { it.copy(busy = false, error = friendlyDropError(e, null)) }
            } catch (_: Throwable) {
                _state.update { it.copy(busy = false, error = "That photo couldn't be saved. Try another one.") }
            }
        }
    }

    fun continueWithText() {
        if (_state.value.text.isBlank() && photo == null) {
            _state.update { it.copy(error = "Add a photo or a few words first.") }
            return
        }
        viewModelScope.launch { loadSuggestions() }
    }

    fun showAllCategories() {
        _state.update { it.copy(showAll = !it.showAll) }
    }

    fun choose(slug: String) {
        val category = _state.value.categories.firstOrNull { it.slug == slug } ?: return
        val fields = AttributeFields.fromSchema(category.attributesSchema)
        val values = prefill(fields)
        val ready = !category.sensitive && requiredSatisfied(fields, values)
        _state.update {
            it.copy(
                phase = if (ready) it.phase else DropPhase.FORM,
                selectedSlug = slug,
                fields = fields,
                values = values,
                sensitive = category.sensitive,
                postingNotice = category.postingNotice,
                guidelinesChecked = false,
                error = null,
            )
        }
        if (ready) post()
    }

    fun onField(name: String, value: String) {
        _state.update { it.copy(values = it.values + (name to value), error = null) }
    }

    fun onGuidelines(checked: Boolean) {
        _state.update { it.copy(guidelinesChecked = checked, error = null) }
    }

    fun post() {
        val snapshot = _state.value
        val slug = snapshot.selectedSlug ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val here = here()
                val title = titleFor(snapshot)
                val result = services.drops.createDrop(
                    CreateDropRequest(
                        categorySlug = slug,
                        latitude = here.latitude,
                        longitude = here.longitude,
                        title = title,
                        body = bodyFor(snapshot, title),
                        attributes = AttributeFields.toAttributes(snapshot.fields, snapshot.values),
                        tags = emptyList(),
                        revealRadiusM = null,
                        expiresAt = null,
                        displayAnonymous = false,
                        media = listOfNotNull(photo),
                        acknowledgeGuidelines = snapshot.guidelinesChecked,
                    ),
                )
                remember(slug)
                runCatching { services.suggestions.log(lastQuery, snapshot.suggestions.map { it.slug }, slug) }
                _state.update {
                    it.copy(
                        phase = DropPhase.DONE,
                        busy = false,
                        success = "Posted.",
                        duplicate = duplicateNotice(result.possibleDuplicates),
                        fuzz = fuzzNotice(result.locationPrecisionM),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update {
                    it.copy(
                        phase = DropPhase.FORM,
                        busy = false,
                        error = friendlyDropError(e, slug),
                    )
                }
            } catch (_: Throwable) {
                _state.update { it.copy(phase = DropPhase.FORM, busy = false, error = "That drop couldn't be posted.") }
            }
        }
    }

    fun another() {
        photo = null
        labels = emptyList()
        _state.value = DropUiState(categories = _state.value.categories)
    }

    private suspend fun loadSuggestions() {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val taxonomy = services.taxonomy.load()
            val here = here()
            val query = SuggestionQuery(
                text = _state.value.text,
                labels = labels,
                latitude = here.latitude,
                longitude = here.longitude,
                hour = ZonedDateTime.now().hour,
                recentSlugs = recent.toList(),
            )
            lastQuery = query
            val suggestions = services.suggestions.suggest(query)
            _state.update {
                it.copy(
                    phase = DropPhase.CHOOSE,
                    busy = false,
                    categories = taxonomy.categories,
                    suggestions = suggestions,
                    error = if (suggestions.isEmpty()) "No category matched. Try a few different words." else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            _state.update { it.copy(busy = false, error = "Suggestions didn't load. Pick a category from the full list.") }
        }
    }

    private suspend fun here(): LatLng {
        if (!location.hasPermission()) return JamestownDemo.center
        return withTimeoutOrNull(250) { location.fixes().first() } ?: JamestownDemo.center
    }

    private fun titleFor(snapshot: DropUiState): String {
        val typed = snapshot.text.trim()
        if (typed.isNotEmpty()) return typed
        val label = labels.firstOrNull()?.trim().orEmpty()
        if (label.isNotEmpty()) return label
        val category = snapshot.categories.firstOrNull { it.slug == snapshot.selectedSlug }
        return category?.label ?: "Photo"
    }

    private fun bodyFor(snapshot: DropUiState, title: String): String? {
        val typed = snapshot.text.trim()
        return if (typed.isNotEmpty() && typed != title) typed else null
    }

    private fun prefill(fields: List<world.xhere.shared.form.AttributeField>): Map<String, String> {
        val now = Clock.System.now().toString()
        return buildMap {
            for (field in fields) {
                if (field.required && field.name == "occurred_at" && field.kind == FieldKind.DATE_TIME) {
                    put(field.name, now)
                }
            }
        }
    }

    private fun requiredSatisfied(
        fields: List<world.xhere.shared.form.AttributeField>,
        values: Map<String, String>,
    ): Boolean = fields.filter { it.required }.all { values[it.name]?.isNotBlank() == true }

    private fun remember(slug: String) {
        recent.remove(slug)
        recent.add(0, slug)
        while (recent.size > 5) recent.removeAt(recent.lastIndex)
    }
}

enum class DropPhase {
    CAPTURE,
    CHOOSE,
    FORM,
    DONE,
}

data class DropUiState(
    val phase: DropPhase = DropPhase.CAPTURE,
    val text: String = "",
    val suggestions: List<CategorySuggestion> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedSlug: String? = null,
    val fields: List<world.xhere.shared.form.AttributeField> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val guidelinesChecked: Boolean = false,
    val showAll: Boolean = false,
    val sensitive: Boolean = false,
    val postingNotice: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val success: String? = null,
    val duplicate: String? = null,
    val fuzz: String? = null,
)
