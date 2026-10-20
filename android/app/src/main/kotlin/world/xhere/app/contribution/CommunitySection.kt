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
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import world.xhere.app.R
import world.xhere.app.drop.DropPhotoCodec
import world.xhere.app.safety.ReportForm
import world.xhere.app.ui.TestTags
import world.xhere.shared.contribution.ContributionCard
import world.xhere.shared.contribution.ContributionFile
import world.xhere.shared.contribution.ContributionMediaPolicy
import world.xhere.shared.contribution.ContributionRules
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
    var drafts by remember(targetKind, targetId) { mutableStateOf<List<DraftMedia>>(emptyList()) }
    var videoJob by remember(targetKind, targetId) { mutableStateOf<Job?>(null) }
    var videoUi by remember(targetKind, targetId) { mutableStateOf<VideoUi?>(null) }

    suspend fun reload(sort: ContributionSort = ui.sort, offset: Int = 0, append: Boolean = false) {
        val place = runCatching { services.contributions.place(targetKind, targetId) }.getOrNull()
        val page = services.contributions.list(targetKind, targetId, sort, offset)
        val cards = if (append) (ui.cards + page).distinctBy { it.id } else page
        val urls = signed(services, cards)
        ui = ui.copy(
            loading = false,
            anonymousAllowed = place?.anonymousAllowed != false,
            shareUrl = place?.shareUrl,
            sort = sort,
            offset = offset,
            hasMore = page.count { it.parentId == null } >= ContributionRules.PAGE_SIZE,
            cards = cards,
            urls = urls,
            error = null,
        )
    }

    fun attach(next: DraftMedia?) {
        if (next == null) return
        if (drafts.size >= ContributionRules.MAX_FILES) {
            ui = ui.copy(adding = true, error = context.getString(R.string.community_files))
            return
        }
        drafts = drafts + next
        val videos = drafts.any { it.file.mimeType.startsWith("video") }
        val images = drafts.any { it.file.mimeType.startsWith("image") }
        val kind = when {
            videos && images -> "media"
            videos -> "video"
            else -> "photo"
        }
        ui = ui.copy(adding = true, kind = kind, error = draftError(context, next))
    }

    fun beginVideo(uri: Uri) {
        videoJob?.cancel()
        videoJob = scope.launch {
            videoUi = VideoUi(uri, progress = 0f, failed = false)
            try {
                val prepared = ContributionVideo.transcode(context, uri) { fraction ->
                    videoUi = VideoUi(uri, fraction, failed = false)
                }
                attach(
                    DraftMedia(
                        file = ContributionFile(
                            bytes = prepared.bytes,
                            mimeType = prepared.mimeType,
                            width = prepared.width,
                            height = prepared.height,
                            durationS = prepared.durationS,
                            thumbnail = prepared.thumbnail,
                        ),
                        thumbnail = prepared.thumbnail,
                    ),
                )
                videoUi = null
            } catch (e: CancellationException) {
                videoUi = null
                throw e
            } catch (_: Throwable) {
                videoUi = VideoUi(uri, videoUi?.progress ?: 0f, failed = true)
            }
        }
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
        attach(readPhoto(context, uri))
    }
    val videoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        beginVideo(uri)
    }
    val cameraPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        attach(photoFromBitmap(bitmap))
    }
    val cameraVideo = rememberLauncherForActivityResult(CaptureShortVideo()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        val uri = cameraUri(context) ?: return@rememberLauncherForActivityResult
        beginVideo(uri)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.COMMUNITY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.community_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            val shareUrl = ui.shareUrl
            if (!shareUrl.isNullOrBlank()) {
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    modifier = Modifier.testTag(TestTags.COMMUNITY_SHARE),
                ) { Text(stringResource(R.string.detail_share)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.sort == ContributionSort.NEWEST,
                onClick = { scope.launch { runCatching { reload(ContributionSort.NEWEST) } } },
                label = { Text(stringResource(R.string.community_newest)) },
                modifier = Modifier.testTag(TestTags.COMMUNITY_NEWEST),
            )
            FilterChip(
                selected = ui.sort == ContributionSort.OLDEST,
                onClick = { scope.launch { runCatching { reload(ContributionSort.OLDEST) } } },
                label = { Text(stringResource(R.string.community_oldest)) },
                modifier = Modifier.testTag(TestTags.COMMUNITY_OLDEST),
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
                url = card.media.firstOrNull()?.let { media ->
                    media.thumbPath?.let { ui.urls[it] } ?: ui.urls[media.storagePath]
                },
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
                onReport = { ui = ui.copy(reporting = card.id, reportingMedia = false) },
                onReportMedia = { ui = ui.copy(reporting = card.id, reportingMedia = true) },
                onReply = { ui = ui.copy(adding = true, replyTo = card.id, kind = "text", error = null) },
                onSendReport = { reason, details ->
                    scope.launch {
                        try {
                            val mediaId = card.media.firstOrNull()?.id
                            if (ui.reportingMedia && mediaId != null) {
                                services.reports.report(ReportTarget.MEDIA, mediaId, reason, details)
                            } else {
                                services.reports.report(ReportTarget.CONTRIBUTION, card.id, reason, details)
                            }
                            ui = ui.copy(reporting = null, reportingMedia = false, error = null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            ui = ui.copy(error = context.getString(R.string.community_failed))
                        }
                    }
                },
                onCancelReport = { ui = ui.copy(reporting = null, reportingMedia = false) },
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
        if (ui.hasMore) {
            TextButton(
                onClick = { scope.launch { runCatching { reload(ui.sort, ui.offset + ContributionRules.PAGE_SIZE, append = true) } } },
                modifier = Modifier.testTag(TestTags.COMMUNITY_MORE),
            ) { Text(stringResource(R.string.community_more)) }
        }
        if (!ui.adding) {
            OutlinedButton(
                onClick = { ui = ui.copy(adding = true, kind = "text", error = null) },
                modifier = Modifier.testTag(TestTags.COMMUNITY_ADD),
            ) { Text(stringResource(R.string.community_add)) }
        } else {
            Text(
                if (ui.replyTo == null) stringResource(R.string.community_add) else stringResource(R.string.community_reply),
                style = MaterialTheme.typography.titleSmall,
            )
            if (drafts.isNotEmpty()) {
                Text(stringResource(R.string.community_attached, drafts.size), style = MaterialTheme.typography.bodyMedium)
            }
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
                    enabled = videoUi == null || videoUi?.failed == true,
                ) { Text(stringResource(R.string.community_video)) }
                TextButton(
                    onClick = { videoGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    enabled = videoUi == null || videoUi?.failed == true,
                ) { Text(stringResource(R.string.community_video_gallery)) }
            }
            val clip = videoUi
            if (clip != null) {
                Text(
                    if (clip.failed) {
                        stringResource(R.string.community_video_failed)
                    } else {
                        stringResource(R.string.community_video_progress, (clip.progress * 100).toInt())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.COMMUNITY_VIDEO_PROGRESS),
                )
                if (!clip.failed) {
                    LinearProgressIndicator(
                        progress = { clip.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (clip.failed) {
                        TextButton(
                            onClick = { beginVideo(clip.uri) },
                            modifier = Modifier.testTag(TestTags.COMMUNITY_VIDEO_RETRY),
                        ) { Text(stringResource(R.string.community_video_retry)) }
                    }
                    TextButton(
                        onClick = {
                            videoJob?.cancel()
                            videoUi = null
                        },
                        modifier = Modifier.testTag(TestTags.COMMUNITY_VIDEO_CANCEL),
                    ) { Text(stringResource(R.string.community_video_cancel)) }
                }
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
                        drafts = emptyList()
                        ui = ui.copy(adding = false, text = "", anonymous = false, busy = false, replyTo = null)
                    },
                    modifier = Modifier.testTag(TestTags.COMMUNITY_CANCEL),
                ) { Text(stringResource(R.string.community_cancel)) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            ui = ui.copy(busy = true, error = null)
                            try {
                                val files = drafts.map { part ->
                                    part.file.copy(thumbnail = part.thumbnail ?: part.file.thumbnail)
                                }
                                val videos = files.any { it.mimeType.startsWith("video") }
                                val images = files.any { it.mimeType.startsWith("image") }
                                val kind = when {
                                    files.isEmpty() -> "text"
                                    videos && images -> "media"
                                    videos -> "video"
                                    else -> "photo"
                                }
                                services.contributions.add(
                                    NewContribution(
                                        targetKind = targetKind,
                                        targetId = targetId,
                                        kind = kind,
                                        body = ui.text.trim().ifEmpty { null },
                                        anonymous = ui.anonymous && ui.anonymousAllowed,
                                        files = files,
                                        parentId = ui.replyTo,
                                    ),
                                )
                                drafts = emptyList()
                                ui = ui.copy(
                                    adding = false,
                                    text = "",
                                    anonymous = false,
                                    busy = false,
                                    kind = "text",
                                    replyTo = null,
                                )
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
    onReportMedia: () -> Unit,
    onReply: () -> Unit,
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
            .padding(start = if (card.parentId == null) 0.dp else 16.dp)
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
                if (card.editedAt != null) {
                    Text(
                        stringResource(R.string.community_edited),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TestTags.COMMUNITY_EDITED),
                    )
                }
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
            if (card.media.size > 1) {
                Text(
                    stringResource(R.string.community_attached, card.media.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (card.kind == "video" || card.kind == "photo" || card.kind == "media") {
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
            Row {
                TextButton(onClick = onReply, modifier = Modifier.testTag(TestTags.COMMUNITY_REPLY)) {
                    Text(stringResource(R.string.community_reply))
                }
                if (card.isMine) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.community_remove)) }
                } else if (!reporting) {
                    TextButton(onClick = onReport) { Text(stringResource(R.string.community_report)) }
                    if (card.media.any { it.id != null }) {
                        TextButton(onClick = onReportMedia) { Text(stringResource(R.string.community_report_photo)) }
                    }
                }
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
    val reportingMedia: Boolean = false,
    val shareUrl: String? = null,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val replyTo: Uuid? = null,
)

private data class DraftMedia(
    val file: ContributionFile,
    val thumbnail: ByteArray? = null,
    val failed: Boolean = false,
)

private data class VideoUi(
    val uri: Uri,
    val progress: Float,
    val failed: Boolean,
)

private suspend fun signed(services: XHereServices, cards: List<ContributionCard>): Map<String, String> {
    val paths = cards.flatMap { card ->
        card.media.flatMap { media -> listOfNotNull(media.thumbPath, media.storagePath) }
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
            width = encoded.width.takeIf { it > 0 } ?: bounds.outWidth.takeIf { it > 0 },
            height = encoded.height.takeIf { it > 0 } ?: bounds.outHeight.takeIf { it > 0 },
            thumbnail = encoded.thumbnail.takeIf { it.isNotEmpty() },
        ),
        thumbnail = encoded.thumbnail.takeIf { it.isNotEmpty() },
    )
}.getOrElse { DraftMedia(emptyFile(), failed = true) }

private fun photoFromBitmap(bitmap: Bitmap): DraftMedia {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    if (!bitmap.isRecycled) bitmap.recycle()
    val encoded = DropPhotoCodec.compress(out.toByteArray(), "image/jpeg")
    return DraftMedia(
        ContributionFile(
            bytes = encoded.bytes,
            mimeType = encoded.mimeType,
            width = encoded.width.takeIf { it > 0 },
            height = encoded.height.takeIf { it > 0 },
            thumbnail = encoded.thumbnail.takeIf { it.isNotEmpty() },
        ),
        thumbnail = encoded.thumbnail.takeIf { it.isNotEmpty() },
    )
}

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
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, ContributionMediaPolicy.VIDEO_MAX_SECONDS.toInt())
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}
