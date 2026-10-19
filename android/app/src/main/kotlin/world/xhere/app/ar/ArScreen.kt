package world.xhere.app.ar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import world.xhere.app.R
import world.xhere.app.map.MarkerIcons
import world.xhere.app.voice.VoiceSession
import world.xhere.app.voice.VoiceSurface
import world.xhere.app.ui.TestTags
import world.xhere.app.ui.hapticClick
import world.xhere.app.ui.openAppSettings
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationSignal

/**
 * Camera labels for nearby drops (PLAN §3 AR view). The Google notice is
 * shown before the session starts. Locked bodies are not in [ArUiState].
 */
@Composable
fun ArScreen(
    onBack: () -> Unit,
    onOpenDrop: (String) -> Unit,
    onDropHere: (LatLng, String, LocationSignal) -> Unit,
    viewModel: ArViewModel = koinViewModel(),
    runtime: ArRuntime = koinInject(),
    voice: VoiceSession = koinInject(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var accepted by rememberSaveable { mutableStateOf(false) }
    if (!accepted) {
        PrivacyNotice(
            onContinue = { accepted = true },
            onBack = onBack,
        )
        return
    }
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(ArPermissions.granted(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        asked = true
        granted = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!ArPermissions.granted(context)) launcher.launch(ArPermissions.requested)
    }
    LaunchedEffect(granted) {
        if (granted) viewModel.begin()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.end() }
    }
    DisposableEffect(accepted, voice) {
        if (accepted) voice.watch(VoiceSurface.AR)
        onDispose { voice.unwatch(VoiceSurface.AR) }
    }
    val heard by voice.nearby.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.AR_SCREEN)) {
        if (granted) {
            runtime.Camera()
            ui.labels.forEach { placed ->
                LabelChip(
                    placed = placed,
                    onOpen = { onOpenDrop(placed.label.shortId) },
                )
            }
            if (ui.canDrop) {
                Button(
                    onClick = hapticClick {
                        val request = viewModel.dropRequest() ?: return@hapticClick
                        onDropHere(request.spot, request.label, request.signal)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .testTag(TestTags.AR_DROP),
                ) {
                    Text(stringResource(R.string.ar_drop_here))
                }
            }
            StatusLine(ui.status, Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 16.dp))
            if (heard.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 96.dp, start = 16.dp, end = 16.dp)
                        .testTag(TestTags.THINGS_AR),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    heard.take(3).forEach { item ->
                        Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.55f)) {
                            Text(
                                text = item.thing.title,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else if (asked) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.ar_camera_denied),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = { launcher.launch(ArPermissions.requested) },
                    modifier = Modifier.testTag(TestTags.AR_RETRY),
                ) {
                    Text(stringResource(R.string.ar_camera_retry))
                }
                TextButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.testTag(TestTags.AR_SETTINGS),
                ) {
                    Text(stringResource(R.string.ar_camera_settings))
                }
            }
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).testTag(TestTags.AR_CLOSE),
        ) {
            Text(stringResource(R.string.ar_close))
        }
    }
}

@Composable
private fun PrivacyNotice(onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TestTags.AR_PRIVACY),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.ar_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.ar_privacy_arcore), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.ar_privacy_sensors), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.ar_privacy_xhere), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { openUrl(context, PRIVACY_URL) }) {
            Text(stringResource(R.string.ar_privacy_policy))
        }
        TextButton(onClick = { openUrl(context, LEARN_MORE_URL) }) {
            Text(stringResource(R.string.ar_privacy_learn))
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.testTag(TestTags.AR_CONTINUE),
        ) {
            Text(stringResource(R.string.ar_continue))
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag(TestTags.AR_CLOSE)) {
            Text(stringResource(R.string.ar_close))
        }
    }
}

@Composable
private fun LabelChip(placed: PlacedLabel, onOpen: () -> Unit) {
    val label = placed.label
    val parsed = MarkerIcons.parseHex(label.colorHex) ?: AndroidColor.parseColor("#0F6E56")
    val tint = Color(parsed)
    Column(
        modifier = Modifier
            .offset { IntOffset(placed.x.roundToInt(), placed.y.roundToInt()) }
            .graphicsLayer {
                scaleX = label.scale
                scaleY = label.scale
                alpha = label.alpha
            }
            .widthIn(max = 220.dp)
            .testTag(TestTags.arLabel(label.shortId))
            .background(tint.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label.title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val body = label.body
        if (body != null) {
            Text(
                text = body,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TestTags.arBody(label.shortId)),
            )
        } else if (label.withheld) {
            Text(
                text = stringResource(R.string.ar_walk_closer),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (label.publicRecord) {
                Text(
                    text = stringResource(R.string.here_public),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = stringResource(R.string.ar_distance, label.distanceM.roundToInt()),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun StatusLine(status: ArStatus, modifier: Modifier) {
    val text = when (status) {
        ArStatus.TRACKING -> null
        ArStatus.WAITING -> stringResource(R.string.ar_waiting)
        ArStatus.COARSE_LOCATION -> stringResource(R.string.ar_coarse)
        ArStatus.NOT_AUTHORIZED -> stringResource(R.string.ar_unauthorized)
        ArStatus.UNAVAILABLE -> stringResource(R.string.ar_unavailable)
        ArStatus.FAILED -> stringResource(R.string.ar_failed)
    } ?: return
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ArGuidanceDialog(
    advice: ArAdvice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ar_title)) },
        text = {
            Text(
                text = stringResource(advice.explanation()),
                modifier = Modifier.testTag(TestTags.AR_GUIDANCE),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TestTags.AR_GUIDANCE_ACTION),
            ) {
                Text(stringResource(advice.actionLabel()))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ar_close)) }
        },
    )
}

internal fun ArAdvice.explanation(): Int = when (this) {
    ArAdvice.OPEN -> R.string.ar_title
    ArAdvice.NEEDS_INSTALL -> R.string.ar_needs_install
    ArAdvice.INSTALL_DECLINED -> R.string.ar_install_declined
    ArAdvice.NEEDS_PERMISSION -> R.string.ar_camera_denied
    ArAdvice.NOT_HERE -> R.string.ar_unavailable
    ArAdvice.SERVICES_MISSING -> R.string.ar_services_missing
    ArAdvice.NOT_AUTHORIZED -> R.string.ar_unauthorized
    ArAdvice.STILL_CHECKING -> R.string.ar_still_checking
    ArAdvice.WAITING_FOR_FIX -> R.string.ar_waiting_fix
}

internal fun ArAdvice.actionLabel(): Int = when (this) {
    ArAdvice.NEEDS_PERMISSION -> R.string.ar_camera_settings
    ArAdvice.NEEDS_INSTALL, ArAdvice.INSTALL_DECLINED, ArAdvice.STILL_CHECKING, ArAdvice.WAITING_FOR_FIX -> R.string.ar_try_again
    else -> R.string.ar_ok
}

internal fun Context.findActivity(): android.app.Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

internal object ArPermissions {
    val requested = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context): Boolean =
        requested.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}

private const val PRIVACY_URL = "https://policies.google.com/privacy"
private const val LEARN_MORE_URL = "https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data"
