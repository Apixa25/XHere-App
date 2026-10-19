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
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.form.AttributeFields
import world.xhere.shared.profile.AuthorNames
import world.xhere.shared.form.FieldKind
import world.xhere.shared.geo.LatLng
import world.xhere.shared.here.Here
import world.xhere.shared.live.RightNowDuration
import world.xhere.shared.model.Category
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.safety.SafetyDirectory
import world.xhere.shared.safety.SafetyFlow
import world.xhere.shared.safety.SafetyResources
import world.xhere.shared.suggest.CategorySuggestion
import world.xhere.shared.suggest.SnapDraft
import world.xhere.shared.suggest.SnapSource
import world.xhere.shared.suggest.SuggestionQuery

/**
 * Quick drop (PLAN §7 M5): a photo or a line of text, three category chips,
 * then post. A category with no required fields and no sensitive notice posts
 * on the chip tap for a text drop. A photo suggestion fills the form and waits
 * for Post. It never posts by itself.
 */
class DropViewModel(
    private val services: XHereServices,
    private val capture: DropCapture,
    private val labeler: ImageLabeler,
    private val location: DeviceLocation,
    private val suggester: SnapSuggester,
) : ViewModel() {
    private val _state = MutableStateFlow(DropUiState())
    val state: StateFlow<DropUiState> = _state.asStateFlow()

    private val recent = mutableListOf<String>()
    private var photo: MediaUpload? = null
    private var labels: List<String> = emptyList()
    private var preview: ByteArray? = null
    private var lastQuery = SuggestionQuery()
    private var filters: List<ContentFilter> = emptyList()
    private var tier: UserTier = UserTier.NEW
    private var lastHere: LatLng? = null
    /** Set when the Here Card or AR asked to drop at a spot. GPS is not used. */
    private var pinned: LatLng? = null
    /** Null for a map pin. A Geospatial pose keeps its accuracy and device-fix bit. */
    private var pinnedSignal: LocationSignal? = null

    init {
        viewModelScope.launch {
            val profile = runCatching { services.profiles.currentProfile() }.getOrNull() ?: return@launch
            tier = profile.tier
            _state.update {
                it.copy(
                    needsName = AuthorNames.needsChoice(profile.displayName, profile.handle),
                    nameDraft = profile.displayName.orEmpty(),
                    handleDraft = if (AuthorNames.needsChoice(profile.displayName, profile.handle)) "" else profile.handle,
                )
            }
        }
    }

    fun pin(spot: LatLng, label: String?, signal: LocationSignal? = null) {
        pinned = spot
        pinnedSignal = signal
        lastHere = spot
        _state.update { it.copy(pinnedLabel = Here.pinLabel(label, spot)) }
    }

    fun clearPin() {
        pinned = null
        pinnedSignal = null
        _state.update { it.copy(pinnedLabel = null) }
    }

    fun onNameDraft(value: String) {
        _state.update { it.copy(nameDraft = value.take(50), nameFailed = false) }
    }

    fun onHandleDraft(value: String) {
        _state.update { it.copy(handleDraft = value.take(24), nameFailed = false) }
    }

    fun saveName() {
        val snapshot = _state.value
        if (snapshot.savingName || snapshot.nameDraft.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(savingName = true, nameFailed = false) }
            try {
                val saved = services.profiles.setPublicName(snapshot.nameDraft, snapshot.handleDraft.trim().ifBlank { null })
                _state.update {
                    it.copy(
                        savingName = false,
                        needsName = AuthorNames.needsChoice(saved.displayName, saved.handle),
                        nameDraft = saved.displayName.orEmpty(),
                        handleDraft = saved.handle,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(savingName = false, nameFailed = true) }
            }
        }
    }

    fun onText(value: String) {
        _state.update {
            val text = value.take(120)
            it.copy(text = text, safety = it.safety?.copy(filterMessage = filterMessage(it.selectedSlug, it.categories, text)))
        }
    }

    fun noteCameraDenied() {
        _state.update { it.copy(cameraDenied = true) }
    }

    fun shutter(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, cameraDenied = false) }
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
                preview = bytes
                val taxonomy = services.taxonomy.load()
                filters = taxonomy.filters
                runCatching { services.profiles.currentProfile().tier }.onSuccess { tier = it }
                val outcome = suggester.suggest(bytes, mimeType, taxonomy.categories)
                labels = outcome.labels.ifEmpty { labeler.labels(bytes, mimeType) }
                val draft = outcome.draft
                val category = draft?.let { picked -> taxonomy.categories.firstOrNull { it.slug == picked.categorySlug && it.active } }
                if (draft != null && category != null) {
                    applySnap(draft, category, taxonomy.categories)
                } else {
                    loadSuggestions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update { it.copy(busy = false, error = friendlyDropError(e, null)) }
            } catch (_: Throwable) {
                _state.update { it.copy(busy = false, error = "That photo couldn't be saved. Try another one.") }
            }
        }
    }

    fun onDescription(value: String) {
        _state.update { it.copy(description = value.take(280), error = null) }
    }

    fun onAltText(value: String) {
        _state.update { it.copy(altText = value.take(300), error = null) }
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
        val holdForPost = _state.value.snap != null
        _state.update {
            val ttl = if (slug == "right_now") it.ttl ?: RightNowDuration.FOUR_HOURS else it.ttl
            it.copy(
                phase = if (ready && !holdForPost) it.phase else DropPhase.FORM,
                selectedSlug = slug,
                fields = fields,
                values = values,
                ttl = ttl,
                sensitive = category.sensitive,
                postingNotice = category.postingNotice,
                guidelinesChecked = false,
                error = null,
                safety = safetyPanel(category, it.categories, it.text),
            )
        }
        if (ready && !holdForPost) post()
    }

    private fun safetyPanel(category: Category, categories: List<Category>, text: String): SafetyPanel? {
        if (!SafetyFlow.appliesTo(category)) return null
        val resources = SafetyDirectory.forLocation(lastHere ?: JamestownDemo.center)
        return SafetyPanel(
            held = SafetyFlow.willBeHeld(category, tier),
            fuzzM = category.locationPrecisionM,
            available = SafetyFlow.availableAt(category, resources),
            filterMessage = filterMessage(category.slug, categories, text),
            resources = resources,
        )
    }

    private fun filterMessage(slug: String?, categories: List<Category>, text: String): String? {
        val category = categories.firstOrNull { it.slug == slug } ?: return null
        if (!SafetyFlow.appliesTo(category)) return null
        return SafetyFlow.filterHit(category, filters, listOf(text))?.message
    }

    fun onField(name: String, value: String) {
        _state.update { it.copy(values = it.values + (name to value), error = null) }
    }

    fun onGuidelines(checked: Boolean) {
        _state.update { it.copy(guidelinesChecked = checked, error = null) }
    }

    /** Null is the category's usual lifetime. Right now always keeps a preset. */
    fun onDuration(id: String?) {
        if (_state.value.selectedSlug == "right_now" && id == null) return
        _state.update { it.copy(ttl = id?.let(RightNowDuration::fromId)) }
    }

    fun post() {
        val snapshot = _state.value
        val slug = snapshot.selectedSlug ?: return
        if (snapshot.safety?.canPost == false) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val here = here()
                val title = titleFor(snapshot)
                val result = services.drops.createDrop(
                    CreateDropRequest(
                        categorySlug = slug,
                        latitude = here.point.latitude,
                        longitude = here.point.longitude,
                        title = title,
                        body = bodyFor(snapshot, title),
                        attributes = AttributeFields.toAttributes(snapshot.fields, snapshot.values),
                        tags = emptyList(),
                        revealRadiusM = null,
                        expiresAt = expiresFor(slug, snapshot.ttl),
                        displayAnonymous = false,
                        media = listOfNotNull(photo?.copy(altText = altFor(snapshot))),
                        acknowledgeGuidelines = snapshot.guidelinesChecked,
                        signal = here.signal,
                    ),
                )
                remember(slug)
                runCatching { services.suggestions.log(lastQuery, snapshot.suggestions.map { it.slug }, slug) }
                val held = result.status == DropStatus.PENDING_REVIEW
                _state.update {
                    it.copy(
                        phase = DropPhase.DONE,
                        busy = false,
                        success = if (held) "Sent for review." else "Posted.",
                        duplicate = duplicateNotice(result.possibleDuplicates),
                        fuzz = fuzzNotice(result.locationPrecisionM),
                        error = null,
                        held = held,
                        reducedTrust = !held && !result.countsForCredit,
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
        preview = null
        _state.value = DropUiState(
            categories = _state.value.categories,
            pinnedLabel = _state.value.pinnedLabel,
        )
    }

    private fun applySnap(draft: SnapDraft, category: Category, categories: List<Category>) {
        val fields = AttributeFields.fromSchema(category.attributesSchema)
        val values = prefill(fields)
        _state.update { current ->
            current.copy(
                phase = DropPhase.FORM,
                busy = false,
                text = current.text.trim().ifEmpty { draft.title },
                description = draft.description,
                altText = draft.altText,
                previewBytes = preview,
                snap = SnapUi(source = draft.source, suggestedSlug = draft.categorySlug),
                categories = categories,
                suggestions = listOf(CategorySuggestion(category.slug, category.label, 1.0)),
                selectedSlug = category.slug,
                fields = fields,
                values = values,
                sensitive = category.sensitive,
                postingNotice = category.postingNotice,
                guidelinesChecked = false,
                error = null,
                safety = safetyPanel(category, categories, current.text.trim().ifEmpty { draft.title }),
            )
        }
    }

    private suspend fun loadSuggestions() {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val taxonomy = services.taxonomy.load()
            filters = taxonomy.filters
            runCatching { services.profiles.currentProfile().tier }.onSuccess { tier = it }
            val here = here()
            lastHere = here.point
            val query = SuggestionQuery(
                text = _state.value.text,
                labels = labels,
                latitude = here.point.latitude,
                longitude = here.point.longitude,
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

    private suspend fun here(): HerePoint {
        pinned?.let { return HerePoint(it, pinnedSignal ?: LocationSignal(deviceFix = false)) }
        if (!location.hasPermission()) return HerePoint(JamestownDemo.center, LocationSignal(deviceFix = false))
        val reading = withTimeoutOrNull(250) { location.fixes().first() }
        return if (reading == null) {
            HerePoint(JamestownDemo.center, LocationSignal(deviceFix = false))
        } else {
            HerePoint(reading.point, reading.signal)
        }
    }

    private fun expiresFor(slug: String, ttl: RightNowDuration?): kotlin.time.Instant? {
        val preset = if (slug == "right_now") ttl ?: RightNowDuration.FOUR_HOURS else ttl
        return preset?.let { Clock.System.now() + it.duration }
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
        val described = snapshot.description.trim()
        if (described.isNotEmpty() && described != title) return described
        val typed = snapshot.text.trim()
        return if (typed.isNotEmpty() && typed != title) typed else null
    }

    private fun altFor(snapshot: DropUiState): String? =
        snapshot.altText.trim().take(300).ifBlank { null }

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

private data class HerePoint(val point: LatLng, val signal: LocationSignal)

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
    /** Null means the category's usual lifetime. */
    val ttl: RightNowDuration? = null,
    val fields: List<world.xhere.shared.form.AttributeField> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val guidelinesChecked: Boolean = false,
    val showAll: Boolean = false,
    val sensitive: Boolean = false,
    val postingNotice: String? = null,
    val busy: Boolean = false,
    val cameraDenied: Boolean = false,
    val error: String? = null,
    val success: String? = null,
    val duplicate: String? = null,
    val fuzz: String? = null,
    /** Set while a Safety category is chosen (PLAN §4.3, M7). */
    val safety: SafetyPanel? = null,
    /** The last post went to pre-moderation. */
    val held: Boolean = false,
    /** Posted, but the location check said it should not earn credit. */
    val reducedTrust: Boolean = false,
    /** Place name and coordinates when the composer was opened from a Here Card. */
    val pinnedLabel: String? = null,
    /** Set after a photo suggestion. The form stays editable and does not post itself. */
    val snap: SnapUi? = null,
    val description: String = "",
    val altText: String = "",
    val previewBytes: ByteArray? = null,
    val needsName: Boolean = false,
    val nameDraft: String = "",
    val handleDraft: String = "",
    val savingName: Boolean = false,
    val nameFailed: Boolean = false,
)

/** A suggestion sitting in the composer. [suggestedSlug] is the category it picked. */
data class SnapUi(
    val source: SnapSource,
    val suggestedSlug: String,
)

/**
 * The Safety steps before posting: the reminder comes from the category's
 * posting notice; this adds the disclaimer, whether a moderator sees it first,
 * the location blur, a live filter check, and who to call.
 */
data class SafetyPanel(
    val held: Boolean,
    val fuzzM: Int,
    val available: Boolean,
    val filterMessage: String?,
    val resources: SafetyResources,
) {
    val canPost: Boolean get() = available && filterMessage == null
}
