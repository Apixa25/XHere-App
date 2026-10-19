package world.xhere.app.profile

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.ui.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    viewModel: EditProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        viewModel.startCrop(bytes)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) viewModel.startCrop(bitmap)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(null)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.ready) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp)
                .testTag(TestTags.SCREEN_EDIT_PROFILE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.cropping) {
                CropStep(state = state, viewModel = viewModel)
            } else {
                val local = remember(state.frame) { viewModel.approvedBitmap() }
                AvatarPreview(url = state.avatarUrl, bitmap = local, label = state.displayName.ifBlank { state.handle })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.testTag(TestTags.PROFILE_AVATAR_GALLERY),
                    ) { Text(stringResource(R.string.profile_avatar_gallery)) }
                    OutlinedButton(
                        onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.testTag(TestTags.PROFILE_AVATAR_CAMERA),
                    ) { Text(stringResource(R.string.profile_avatar_camera)) }
                }
                if (state.avatarUrl != null || local != null) {
                    TextButton(onClick = viewModel::removeAvatar, modifier = Modifier.testTag(TestTags.PROFILE_AVATAR_REMOVE)) {
                        Text(stringResource(R.string.profile_avatar_remove))
                    }
                }
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onDisplayName,
                    label = { Text(stringResource(R.string.profile_name_display)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_EDIT_NAME),
                )
                OutlinedTextField(
                    value = state.handle,
                    onValueChange = viewModel::onHandle,
                    label = { Text(stringResource(R.string.profile_name_handle)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_EDIT_HANDLE),
                )
                Text(
                    text = handleStatusText(state.handleStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.PROFILE_HANDLE_STATUS),
                )
                OutlinedTextField(
                    value = state.bio,
                    onValueChange = viewModel::onBio,
                    label = { Text(stringResource(R.string.profile_bio)) },
                    supportingText = { Text("${state.bio.length}/${160}") },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_EDIT_BIO),
                )
                OutlinedTextField(
                    value = state.homeArea,
                    onValueChange = viewModel::onHome,
                    label = { Text(stringResource(R.string.profile_home)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_EDIT_HOME),
                )
                OutlinedTextField(
                    value = state.website,
                    onValueChange = viewModel::onWebsite,
                    label = { Text(stringResource(R.string.profile_website)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_EDIT_WEBSITE),
                )
                state.error?.let { error ->
                    Text(
                        text = errorText(error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TestTags.PROFILE_EDIT_ERROR),
                    )
                }
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    modifier = Modifier.testTag(TestTags.PROFILE_EDIT_SAVE),
                ) {
                    Text(stringResource(if (state.saving) R.string.profile_edit_saving else R.string.profile_edit_save))
                }
            }
        }
    }
}

@Composable
private fun CropStep(state: EditProfileUi, viewModel: EditProfileViewModel) {
    val frame = remember(state.frame, state.zoom) { viewModel.framedBitmap() }
    Text(stringResource(R.string.profile_avatar_crop), style = MaterialTheme.typography.titleMedium)
    if (frame != null) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = stringResource(R.string.profile_avatar_crop),
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .testTag(TestTags.PROFILE_AVATAR_CROP),
        )
    }
    Slider(
        value = state.zoom,
        onValueChange = viewModel::onZoom,
        valueRange = 1f..4f,
        modifier = Modifier.testTag(TestTags.PROFILE_AVATAR_ZOOM),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = viewModel::cancelCrop) { Text(stringResource(R.string.detail_edit_cancel)) }
        Button(onClick = viewModel::confirmCrop, modifier = Modifier.testTag(TestTags.PROFILE_AVATAR_USE)) {
            Text(stringResource(R.string.profile_avatar_use))
        }
    }
}

@Composable
private fun AvatarPreview(url: String?, bitmap: Bitmap?, label: String) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .testTag(TestTags.PROFILE_AVATAR),
        )
    } else {
        ProfileAvatar(url = url, label = label.ifBlank { "?" }, size = 96.dp, tag = TestTags.PROFILE_AVATAR)
    }
}

@Composable
private fun handleStatusText(status: HandleStatus): String = when (status) {
    HandleStatus.IDLE -> ""
    HandleStatus.INVALID -> stringResource(R.string.profile_handle_invalid)
    HandleStatus.CHECKING -> stringResource(R.string.profile_handle_checking)
    HandleStatus.AVAILABLE -> stringResource(R.string.profile_handle_available)
    HandleStatus.TAKEN -> stringResource(R.string.profile_handle_taken)
}

@Composable
private fun errorText(error: EditProfileError): String = when (error) {
    EditProfileError.NAME -> stringResource(R.string.profile_error_name)
    EditProfileError.HANDLE -> stringResource(R.string.profile_handle_invalid)
    EditProfileError.TAKEN -> stringResource(R.string.profile_handle_taken)
    EditProfileError.BIO -> stringResource(R.string.profile_error_bio)
    EditProfileError.HOME -> stringResource(R.string.profile_error_home)
    EditProfileError.WEBSITE -> stringResource(R.string.profile_error_website)
    EditProfileError.AVATAR -> stringResource(R.string.profile_error_avatar)
    EditProfileError.SAVE -> stringResource(R.string.profile_edit_failed)
}
