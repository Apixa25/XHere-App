package world.xhere.app.contribution

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import world.xhere.app.R
import world.xhere.app.drop.DropPhotoCodec
import world.xhere.app.safety.ReportForm
import world.xhere.app.ui.TestTags
import world.xhere.shared.contribution.ContributionCard
import world.xhere.shared.contribution.ContributionFile
import world.xhere.shared.contribution.ContributionSort
import world.xhere.shared.contribution.NewContribution
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportTarget

/**
 * Community notes, photos, and short videos on one place (PLAN §0, PLAN §3).
 * Newest first, with a Live mark for the last day. Low scores fold away.
 * The form sits in the sheet, and Cancel closes it.
 */
@Composable
fun CommunitySection(
    targetKind: String,
    targetId: Uuid,
    services: XHereServices,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember(targetKind, targetId) { mutableStateOf(CommunityUi()) }
    var draft by remember(targetKind, targetId) { mutableStateOf<DraftMedia?>(null) }

    suspend fun reload(sort: ContributionSort = ui.sort) {
        val place = runCatching { services.contributions.place(targetKind, targetId) }.getOrNull()
        val cards = services.contributions.list(targetKind, targetId, sort)
        val urls = signed(services, cards)
        ui = ui.copy(
            loading = false,
            anonymousAllowed = place?.anonymousAllowed != false,
            sort = sort,
            cards = cards,
            urls = urls,
            error = null,
        )
    }

    LaunchedEffect(targetKind, targetId) {
        ui = ui.copy(loading = true)
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ui = ui.copy(loading = false, error = context.getString(R.string.community_failed))
        }
    }

    val photoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        draft = readPhoto(context, uri)
        ui = ui.copy(adding = true, kind = "photo", error = draftError(context, draft))
    }
    val videoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        draft = readVideo(context, uri)
        ui = ui.copy(adding = true, kind = "video", error = draftError(context, draft))
    }
    val cameraPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        draft = photoFromBitmap(bitmap)
        ui = ui.copy(adding = true, kind = "photo", error = null)
    }
    val cameraVideo = rememberLauncherForActivityResult(CaptureShortVideo()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        val uri = cameraUri(context) ?: return@rememberLauncherForActivityResult
        draft = readVideo(context, uri)
        ui = ui.copy(adding = true, kind = "video", error = draftError(context, draft))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.COMMUNITY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.community_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.sort == ContributionSort.NEWEST,
                onClick = { scope.launch { runCatching { reload(ContributionSort.NEWEST) } } },
                label = { Text(stringResource(R.string.community_newest)) },
                modifier = Modifier.testTag(TestTags.COMMUNITY_NEWEST),
            )
            FilterChip(
                selected = ui.sort == ContributionSort.TOP,
                onClick = { scope.launch { runCatching { reload(ContributionSort.TOP) } } },
                label = { Text(stringResource(R.string.community_top)) },
                modifier = Modifier.testTag(TestTags.COMMUNITY_TOP),
            )
        }
        if (ui.loading) {
            Text(stringResource(R.string.community_loading), style = MaterialTheme.typography.bodyMedium)
        }
        ui.cards.forEach { card ->
            ContributionRow(
                card = card,
                url = card.media.firstOrNull()?.let { ui.urls[it.thumbPath ?: it.storagePath] },
                opened = card.id in ui.openCollapsed,
                reporting = ui.reporting == card.id,
                onVote = { value ->
                    scope.launch {
                        try {
                            val next = if (card.myVote == value) 0 else value
                            services.contributions.vote(card.id, next)
                            reload()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            ui = ui.copy(error = context.getString(R.string.community_failed))
                        }
                    }
                },
                onOpen = { ui = ui.copy(openCollapsed = ui.openCollapsed + card.id) },
                onReport = { ui = ui.copy(reporting = card.id) },
                onSendReport = { reason, details ->
                    scope.launch {
                        try {
                            services.reports.report(ReportTarget.CONTRIBUTION, card.id, reason, details)
                            ui = ui.copy(reporting = null, error = null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            ui = ui.copy(error = context.getString(R.string.community_failed))
                        }
                    }
                },
                onCancelReport = { ui = ui.copy(reporting = null) },
                onRemove = {
                    scope.launch {
                        try {
                            services.contributions.delete(card.id)
                            reload()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            ui = ui.copy(error = context.getString(R.string.community_failed))
                        }
                    }
                },
            )
        }
        if (!ui.adding) {
            OutlinedButton(
                onClick = { ui = ui.copy(adding = true, kind = "text", error = null) },
                modifier = Modifier.testTag(TestTags.COMMUNITY_ADD),
            ) { Text(stringResource(R.string.community_add)) }
        } else {
            Text(stringResource(R.string.community_add), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { cameraPhoto.launch(null) }) { Text(stringResource(R.string.community_camera)) }
                TextButton(
                    onClick = { photoGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) { Text(stringResource(R.string.community_gallery)) }
                TextButton(
                    onClick = {
                        val uri = cameraUri(context) ?: return@TextButton
                        cameraVideo.launch(uri)
                    },
                ) { Text(stringResource(R.string.community_video)) }
                TextButton(
                    onClick = { videoGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                ) { Text(stringResource(R.string.community_video_gallery)) }
            }
            androidx.compose.material3.OutlinedTextField(
                value = ui.text,
                onValueChange = { ui = ui.copy(text = it.take(500)) },
                label = { Text(stringResource(R.string.community_text)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.COMMUNITY_TEXT),
            )
            if (ui.anonymousAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = ui.anonymous,
                        onCheckedChange = { ui = ui.copy(anonymous = it) },
                        modifier = Modifier.testTag(TestTags.COMMUNITY_ANON),
                    )
                    Text(stringResource(R.string.community_anonymous))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        draft = null
                        ui = ui.copy(adding = false, text = "", anonymous = false, busy = false)
                    },
                    modifier = Modifier.testTag(TestTags.COMMUNITY_CANCEL),
                ) { Text(stringResource(R.string.community_cancel)) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            ui = ui.copy(busy = true, error = null)
                            try {
                                services.contributions.add(
                                    NewContribution(
                                        targetKind = targetKind,
                                        targetId = targetId,
                                        kind = ui.kind,
                                        body = ui.text.trim().ifEmpty { null },
                                        anonymous = ui.anonymous && ui.anonymousAllowed,
                                        file = if (ui.kind == "text") null else draft?.file,
                                        thumbnail = if (ui.kind == "video") draft?.thumbnail else null,
                                    ),
                                )
                                draft = null
                                ui = ui.copy(adding = false, text = "", anonymous = false, busy = false, kind = "text")
                                reload()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: XHereApiException) {
                                ui = ui.copy(busy = false, error = e.detail ?: context.getString(R.string.community_failed))
                            } catch (_: Throwable) {
                                ui = ui.copy(busy = false, error = context.getString(R.string.community_failed))
                            }
                        }
                    },
                    enabled = !ui.busy,
                    modifier = Modifier.testTag(TestTags.COMMUNITY_SEND),
                ) { Text(stringResource(R.string.community_send)) }
            }
        }
        ui.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ContributionRow(
    card: ContributionCard,
    url: String?,
    opened: Boolean,
    reporting: Boolean,
    onVote: (Int) -> Unit,
    onOpen: () -> Unit,
    onReport: () -> Unit,
    onSendReport: (ReportReason, String?) -> Unit,
    onCancelReport: () -> Unit,
    onRemove: () -> Unit,
) {
    if (card.collapsed && !opened) {
        TextButton(onClick = onOpen, modifier = Modifier.testTag(TestTags.communityCard(card.id))) {
            Text(stringResource(R.string.community_collapsed))
        }
        return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.communityCard(card.id)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val who = card.authorHandle?.let { stringResource(R.string.community_by, it) }
                    ?: stringResource(R.string.detail_neighbor)
                Text(who, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (card.live) {
                    Text(
                        stringResource(R.string.community_live),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.testTag(TestTags.COMMUNITY_LIVE),
                    )
                }
            }
            card.body?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (card.kind == "video" || card.kind == "photo") {
                Text(
                    text = if (card.kind == "video") stringResource(R.string.community_video_label) else stringResource(R.string.detail_photo),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!url.isNullOrBlank()) {
                    AsyncImage(
                        model = url,
                        contentDescription = card.body ?: stringResource(R.string.detail_photo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                }
            }
            if (card.kind != "text" && !card.isMine) {
                Row {
                    TextButton(onClick = { onVote(1) }) {
                        Text(stringResource(R.string.community_up, card.upCount))
                    }
                    TextButton(onClick = { onVote(-1) }) {
                        Text(stringResource(R.string.community_down, card.downCount))
                    }
                }
            }
            if (card.isMine) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.community_remove)) }
            } else if (!reporting) {
                TextButton(onClick = onReport) { Text(stringResource(R.string.community_report)) }
            }
            if (reporting) {
                ReportForm(
                    title = stringResource(R.string.community_report),
                    showAlsoHide = false,
                    sending = false,
                    onSend = { reason, details, _ -> onSendReport(reason, details) },
                    onDismiss = onCancelReport,
                )
            }
        }
    }
}

private data class CommunityUi(
    val loading: Boolean = true,
    val anonymousAllowed: Boolean = true,
    val sort: ContributionSort = ContributionSort.NEWEST,
    val cards: List<ContributionCard> = emptyList(),
    val urls: Map<String, String> = emptyMap(),
    val adding: Boolean = false,
    val kind: String = "text",
    val text: String = "",
    val anonymous: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val openCollapsed: Set<Uuid> = emptySet(),
    val reporting: Uuid? = null,
)

private data class DraftMedia(
    val file: ContributionFile,
    val thumbnail: ByteArray? = null,
    val failed: Boolean = false,
)

private suspend fun signed(services: XHereServices, cards: List<ContributionCard>): Map<String, String> {
    val paths = cards.flatMap { card ->
        card.media.flatMap { media -> listOfNotNull(media.thumbPath ?: media.storagePath) }
    }.distinct()
    return paths.mapNotNull { path ->
        val url = runCatching { services.media.photoUrl("contribution-media", path) }.getOrNull()
        if (url.isNullOrBlank()) null else path to url
    }.toMap()
}

private fun draftError(context: Context, draft: DraftMedia?): String? =
    if (draft?.failed == true) context.getString(R.string.community_video_cap) else null

private fun readPhoto(context: Context, uri: Uri): DraftMedia = runCatching {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return DraftMedia(emptyFile(), failed = true)
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val encoded = DropPhotoCodec.compress(raw, mime)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, bounds)
    DraftMedia(
        ContributionFile(
            bytes = encoded.bytes,
            mimeType = encoded.mimeType,
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
        ),
    )
}.getOrElse { DraftMedia(emptyFile(), failed = true) }

private fun photoFromBitmap(bitmap: Bitmap): DraftMedia {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    val encoded = DropPhotoCodec.compress(out.toByteArray(), "image/jpeg")
    return DraftMedia(
        ContributionFile(
            bytes = encoded.bytes,
            mimeType = encoded.mimeType,
            width = bitmap.width,
            height = bitmap.height,
        ),
    )
}

private fun readVideo(context: Context, uri: Uri): DraftMedia = runCatching {
    val prepared = ContributionVideo.prepare(context, uri)
    DraftMedia(
        file = ContributionFile(
            bytes = prepared.bytes,
            mimeType = prepared.mimeType,
            width = prepared.width,
            height = prepared.height,
            durationS = prepared.durationS,
        ),
        thumbnail = prepared.thumbnail,
    )
}.getOrElse { DraftMedia(emptyFile(), failed = true) }

private fun emptyFile() = ContributionFile(ByteArray(0), "image/jpeg")

private fun cameraUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "clip.mp4")
    FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
}.getOrNull()

private class CaptureShortVideo : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}
