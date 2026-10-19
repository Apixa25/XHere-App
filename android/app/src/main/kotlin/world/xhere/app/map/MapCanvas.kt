package world.xhere.app.map

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.MapFeature

/**
 * The map surface. Production draws MapLibre. Tests, and a device whose native
 * renderer cannot start, draw the same features as text so discovery still works.
 */
interface MapCanvas {
    @Composable
    fun Content(
        state: MapUiState,
        onCameraIdle: (CameraFrame) -> Unit,
        onUserPanned: () -> Unit,
        onFeatureClick: (MapFeature) -> Unit,
        onMapClick: (LatLng) -> Unit,
        onMapLongClick: (LatLng) -> Unit,
        modifier: Modifier,
    )
}

/**
 * No tiles. Emits a viewport from the camera token, then lists the features that
 * came back. Token 0 is downtown Jamestown, which is also the default when the
 * phone has no location fix.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProgrammaticMap(
    state: MapUiState,
    onCameraIdle: (CameraFrame) -> Unit,
    onFeatureClick: (MapFeature) -> Unit,
    onMapClick: (LatLng) -> Unit,
    onMapLongClick: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.camera.token) {
        onCameraIdle(frameFor(state.camera))
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = if (state.campaigns.isNotEmpty()) 132.dp else 72.dp, bottom = 220.dp)
            .testTag(TestTags.MAP_MARKERS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.map_tap_here),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(TestTags.MAP_TAP_SPOT)
                .combinedClickable(
                    onClick = { onMapClick(state.camera.point) },
                    onLongClick = { onMapLongClick(state.camera.point) },
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        state.features.forEach { feature ->
            val records = if (feature.source == world.xhere.shared.model.DropSource.OPEN_DATA) "  · public record" else ""
            val caption = if (feature.isCluster || feature.title.isNullOrBlank()) {
                markerLabel(feature, state.taxonomy)
            } else {
                markerLabel(feature, state.taxonomy) + "  " + feature.title + records
            }
            Text(
                text = caption,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag(markerTag(feature))
                    .clickable { onFeatureClick(feature) },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

internal fun markerTag(feature: MapFeature): String {
    val shortId = feature.shortId
    return if (feature.isCluster || shortId == null) {
        TestTags.MARKER_CLUSTER
    } else {
        TestTags.marker(shortId)
    }
}

private fun frameFor(camera: CameraTarget): CameraFrame {
    if (camera.token == 0) {
        return CameraFrame(JamestownDemo.downtown, JamestownDemo.STREET_ZOOM)
    }
    val zoom = camera.zoom.roundToInt().coerceIn(Geo.MIN_ZOOM, Geo.MAX_ZOOM)
    val radius = when {
        zoom >= 15 -> 700.0
        zoom >= 12 -> 2_500.0
        else -> 8_000.0
    }
    return CameraFrame(Geo.boundingBox(LatLng(camera.latitude, camera.longitude), radius), zoom)
}
