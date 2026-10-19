package world.xhere.app.profile

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.ProfileDraft
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.profile.ProfileFields

enum class HandleStatus { IDLE, INVALID, CHECKING, AVAILABLE, TAKEN }

enum class EditProfileError { NAME, HANDLE, TAKEN, BIO, HOME, WEBSITE, AVATAR, SAVE }

data class EditProfileUi(
    val ready: Boolean = false,
    val displayName: String = "",
    val handle: String = "",
    val bio: String = "",
    val homeArea: String = "",
    val website: String = "",
    val avatarUrl: String? = null,
    val cropping: Boolean = false,
    val zoom: Float = 1f,
    val frame: Int = 0,
    val handleStatus: HandleStatus = HandleStatus.IDLE,
    val saving: Boolean = false,
    val error: EditProfileError? = null,
    val saved: Boolean = false,
)

class EditProfileViewModel(
    private val services: XHereServices,
) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileUi())
    val state: StateFlow<EditProfileUi> = _state.asStateFlow()
    private var savedPath: String = ""
    private var removePicture: Boolean = false
    private var pending: AvatarCodec.Encoded? = null
    private var cropSource: Bitmap? = null
    private var approved: Bitmap? = null
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            val profile = runCatching { services.profiles.currentProfile() }.getOrNull()
            if (profile == null) {
                _state.update { it.copy(ready = true, error = EditProfileError.SAVE) }
                return@launch
            }
            savedPath = profile.avatarPath.orEmpty()
            _state.value = EditProfileUi(
                ready = true,
                displayName = profile.displayName.orEmpty(),
                handle = profile.handle,
                bio = profile.bio.orEmpty(),
                homeArea = profile.homeArea.orEmpty(),
                website = profile.website.orEmpty(),
                avatarUrl = services.media.avatarUrl(profile.avatarPath),
                handleStatus = HandleStatus.AVAILABLE,
            )
        }
    }

    fun onDisplayName(value: String) {
        _state.update { it.copy(displayName = value.take(ProfileFields.NAME_MAX), error = null) }
    }

    fun onHandle(value: String) {
        val cleaned = value.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(ProfileFields.HANDLE_MAX)
        _state.update { it.copy(handle = cleaned, error = null, handleStatus = HandleStatus.CHECKING) }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            delay(250)
            val status = when {
                ProfileFields.cleanHandle(cleaned) == null -> HandleStatus.INVALID
                services.profiles.handleAvailable(cleaned) -> HandleStatus.AVAILABLE
                else -> HandleStatus.TAKEN
            }
            _state.update { current ->
                if (current.handle == cleaned) current.copy(handleStatus = status) else current
            }
        }
    }

    fun onBio(value: String) {
        _state.update { it.copy(bio = value.take(ProfileFields.BIO_MAX), error = null) }
    }

    fun onHome(value: String) {
        _state.update { it.copy(homeArea = value.take(ProfileFields.HOME_MAX), error = null) }
    }

    fun onWebsite(value: String) {
        _state.update { it.copy(website = value.take(ProfileFields.WEBSITE_MAX), error = null) }
    }

    fun onZoom(value: Float) {
        _state.update { it.copy(zoom = value.coerceIn(1f, 4f), frame = it.frame + 1) }
    }

    fun startCrop(bitmap: Bitmap) {
        cropSource = bitmap
        _state.update { it.copy(cropping = true, zoom = 1f, frame = it.frame + 1, error = null) }
    }

    fun startCrop(bytes: ByteArray) {
        val bitmap = AvatarCodec.decode(bytes) ?: run {
            _state.update { it.copy(error = EditProfileError.AVATAR) }
            return
        }
        startCrop(bitmap)
    }

    fun cancelCrop() {
        cropSource = null
        _state.update { it.copy(cropping = false) }
    }

    fun confirmCrop() {
        val source = cropSource ?: return
        val framed = AvatarCodec.circle(source, _state.value.zoom)
        val encoded = AvatarCodec.compress(framed)
        if (encoded.bytes.size > ProfileFields.AVATAR_MAX_BYTES) {
            _state.update { it.copy(error = EditProfileError.AVATAR, cropping = false) }
            cropSource = null
            return
        }
        pending = encoded
        approved = framed
        removePicture = false
        cropSource = null
        _state.update { it.copy(cropping = false, avatarUrl = null, frame = it.frame + 1) }
    }

    fun framedBitmap(): Bitmap? {
        val source = cropSource ?: return null
        return AvatarCodec.circle(source, _state.value.zoom)
    }

    fun approvedBitmap(): Bitmap? = approved

    fun removeAvatar() {
        pending = null
        approved = null
        removePicture = true
        savedPath = ""
        _state.update { it.copy(avatarUrl = null, frame = it.frame + 1) }
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.saving || snapshot.cropping) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val path = when {
                    pending != null -> services.media.uploadAvatar(pending!!.bytes, pending!!.mimeType)
                    removePicture -> ""
                    else -> savedPath
                }
                val saved = services.profiles.updateProfile(
                    ProfileDraft(
                        displayName = snapshot.displayName,
                        handle = snapshot.handle,
                        bio = snapshot.bio.replace('\n', ' '),
                        homeArea = snapshot.homeArea,
                        website = snapshot.website,
                        avatarPath = path,
                    ),
                )
                savedPath = saved.avatarPath.orEmpty()
                pending = null
                removePicture = false
                _state.update { it.copy(saving = false, saved = true, avatarUrl = services.media.avatarUrl(saved.avatarPath)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XHereApiException) {
                _state.update { it.copy(saving = false, error = errorFor(e.code)) }
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, error = EditProfileError.SAVE) }
            }
        }
    }

    private fun errorFor(code: String): EditProfileError = when (code) {
        "invalid_display_name" -> EditProfileError.NAME
        "invalid_handle" -> EditProfileError.HANDLE
        "handle_taken" -> EditProfileError.TAKEN
        "invalid_bio" -> EditProfileError.BIO
        "invalid_home_area" -> EditProfileError.HOME
        "invalid_website" -> EditProfileError.WEBSITE
        "invalid_avatar", "unsupported_type" -> EditProfileError.AVATAR
        else -> EditProfileError.SAVE
    }
}
