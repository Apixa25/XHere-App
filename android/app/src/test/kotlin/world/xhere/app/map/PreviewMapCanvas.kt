package world.xhere.app.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.MapFeature

/**
 * Test stand-in for MapLibre. The native renderer does not load under Robolectric.
 * Pan and zoom buttons emit the same camera-idle events a gesture would.
 */
class PreviewMapCanvas : MapCanvas {
    @Composable
    override fun Content(
        state: MapUiState,
        onCameraIdle: (CameraFrame) -> Unit,
        onUserPanned: () -> Unit,
        onFeatureClick: (MapFeature) -> Unit,
        onMapClick: (LatLng) -> Unit,
        onMapLongClick: (LatLng) -> Unit,
        modifier: Modifier,
    ) {
        Box(modifier) {
            ProgrammaticMap(state, onCameraIdle, onFeatureClick, onMapClick, onMapLongClick)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 120.dp),
            ) {
                TextButton(
                    onClick = {
                        onUserPanned()
                        onCameraIdle(CameraFrame(JamestownDemo.buffalo, JamestownDemo.STREET_ZOOM))
                    },
                    modifier = Modifier.testTag(TestTags.MAP_PAN_AWAY),
                ) {
                    Text("Pan away")
                }
                TextButton(
                    onClick = {
                        onUserPanned()
                        onCameraIdle(CameraFrame(JamestownDemo.downtown, JamestownDemo.CLUSTER_ZOOM))
                    },
                    modifier = Modifier.testTag(TestTags.MAP_ZOOM_OUT),
                ) {
                    Text("Zoom out")
                }
                TextButton(
                    onClick = { onMapLongClick(JamestownDemo.library) },
                    modifier = Modifier.testTag(TestTags.MAP_LONG_PRESS),
                ) {
                    Text("Long press")
                }
            }
        }
    }
}
