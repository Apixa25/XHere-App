package world.xhere.app.map

import android.graphics.Color
import android.graphics.RectF
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.Taxonomy

/**
 * MapLibre Native with an OpenFreeMap style. No API key.
 * OpenFreeMap asks for attribution; the style's own attribution control stays on,
 * and [MapScreen] draws the visible OSM / OpenMapTiles / OpenFreeMap line as well.
 *
 * If the native library cannot load (unit tests, a broken device), the same
 * features render as a plain list so discovery still runs against the repository.
 */
class MapLibreMapCanvas : MapCanvas {
    @Composable
    override fun Content(
        state: MapUiState,
        onCameraIdle: (CameraFrame) -> Unit,
        onUserPanned: () -> Unit,
        onFeatureClick: (MapFeature) -> Unit,
        modifier: Modifier,
    ) {
        val context = LocalContext.current
        val native = remember {
            runCatching {
                MapLibre.getInstance(context)
                Class.forName("org.maplibre.android.maps.MapView")
                true
            }.getOrDefault(false)
        }
        if (!native) {
            ProgrammaticMap(state, onCameraIdle, onFeatureClick, modifier)
            return
        }
        NativeMap(state, onCameraIdle, onUserPanned, onFeatureClick, modifier)
    }
}

private object OpenFreeMap {
    const val LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val DARK = "https://tiles.openfreemap.org/styles/dark"
}

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var styleUrl: String? = null
    var appliedToken: Int = 0
    var features: List<MapFeature> = emptyList()
    var location: LatLng? = null
    var taxonomy: Taxonomy? = null
    var view: MapView? = null
}

@Composable
private fun NativeMap(
    state: MapUiState,
    onCameraIdle: (CameraFrame) -> Unit,
    onUserPanned: () -> Unit,
    onFeatureClick: (MapFeature) -> Unit,
    modifier: Modifier,
) {
    val dark = isSystemInDarkTheme()
    val styleUrl = if (dark) OpenFreeMap.DARK else OpenFreeMap.LIBERTY
    val holder = remember { MapHolder() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            val view = holder.view ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                holder.view = this
                onCreate(null)
                onStart()
                onResume()
                getMapAsync { map ->
                    holder.map = map
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = true
                    val start = state.camera
                    map.cameraPosition = CameraPosition.Builder()
                        .target(MapLatLng(start.latitude, start.longitude))
                        .zoom(start.zoom)
                        .build()
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            onUserPanned()
                        }
                    }
                    map.addOnCameraIdleListener {
                        val bounds = map.projection.visibleRegion.latLngBounds
                        val west = bounds.longitudeWest
                        val east = bounds.longitudeEast
                        val south = bounds.latitudeSouth
                        val north = bounds.latitudeNorth
                        if (west >= east || south >= north) return@addOnCameraIdleListener
                        val zoom = map.cameraPosition.zoom.roundToInt().coerceIn(Geo.MIN_ZOOM, Geo.MAX_ZOOM)
                        onCameraIdle(CameraFrame(BoundingBox(west, south, east, north), zoom))
                    }
                    map.addOnMapClickListener { latLng ->
                        val screen = map.projection.toScreenLocation(latLng)
                        val slop = 28f * context.resources.displayMetrics.density
                        val rect = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
                        val key = map.queryRenderedFeatures(rect, DROP_LAYER)
                            .firstOrNull()
                            ?.getStringProperty("key")
                            ?: return@addOnMapClickListener false
                        val feature = holder.features.firstOrNull { featureKey(it) == key }
                            ?: return@addOnMapClickListener false
                        onFeatureClick(feature)
                        true
                    }
                    map.setStyle(styleUrl) { style ->
                        holder.style = style
                        holder.styleUrl = styleUrl
                        render(style, holder.features, holder.location, holder.taxonomy)
                    }
                }
            }
        },
        update = { mapView ->
            holder.view = mapView
            holder.features = state.features
            holder.location = state.myLocation
            holder.taxonomy = state.taxonomy
            val map = holder.map ?: return@AndroidView
            if (state.camera.token != holder.appliedToken && state.camera.token > 0) {
                holder.appliedToken = state.camera.token
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        MapLatLng(state.camera.latitude, state.camera.longitude),
                        state.camera.zoom,
                    ),
                )
            }
            val loaded = holder.style
            if (loaded != null && holder.styleUrl == styleUrl) {
                render(loaded, state.features, state.myLocation, state.taxonomy)
            } else if (holder.styleUrl != null && holder.styleUrl != styleUrl) {
                map.setStyle(styleUrl) { style ->
                    holder.style = style
                    holder.styleUrl = styleUrl
                    render(style, state.features, state.myLocation, state.taxonomy)
                }
            }
        },
        onRelease = { mapView ->
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            holder.view = null
            holder.map = null
            holder.style = null
        },
    )
}

private fun render(style: Style, features: List<MapFeature>, location: LatLng?, taxonomy: Taxonomy?) {
    features.map { iconKey(it, taxonomy) }.distinct().forEach { key ->
        val id = MarkerIcons.idFor(key)
        if (style.getImage(id) == null) {
            val sample = features.first { iconKey(it, taxonomy) == key }
            val label = markerLabel(sample, taxonomy)
            style.addImage(
                id,
                MarkerIcons.bitmap(
                    label,
                    MarkerIcons.strokeColor(sample, taxonomy),
                    publicRecord = sample.source == world.xhere.shared.model.DropSource.OPEN_DATA,
                ),
            )
        }
    }
    val drops = collectionJson(features, taxonomy)
    val existing = style.getSource(DROP_SOURCE) as? GeoJsonSource
    if (existing == null) {
        style.addSource(GeoJsonSource(DROP_SOURCE, drops))
        style.addLayer(
            SymbolLayer(DROP_LAYER, DROP_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconSize(0.85f),
            ),
        )
    } else {
        existing.setGeoJson(drops)
    }
    val me = if (location == null) {
        EMPTY
    } else {
        pointCollection(location.longitude, location.latitude)
    }
    val meSource = style.getSource(ME_SOURCE) as? GeoJsonSource
    if (meSource == null) {
        style.addSource(GeoJsonSource(ME_SOURCE, me))
        style.addLayer(
            CircleLayer(ME_HALO, ME_SOURCE).withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(Color.parseColor("#330F6E56")),
                PropertyFactory.circleStrokeWidth(0f),
            ),
        )
        style.addLayer(
            CircleLayer(ME_DOT, ME_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Color.parseColor("#0F6E56")),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            ),
        )
    } else {
        meSource.setGeoJson(me)
    }
}

private fun iconKey(feature: MapFeature, taxonomy: Taxonomy?): String {
    val label = markerLabel(feature, taxonomy)
    return if (feature.source == world.xhere.shared.model.DropSource.OPEN_DATA) "records:$label" else label
}

internal fun featureKey(feature: MapFeature): String =
    feature.shortId ?: "c-${feature.latitude}-${feature.longitude}-${feature.pointCount}"

private fun collectionJson(features: List<MapFeature>, taxonomy: Taxonomy?): String {
    val body = features.joinToString(",") { feature ->
        val icon = MarkerIcons.idFor(iconKey(feature, taxonomy))
        val key = featureKey(feature)
        """{"type":"Feature","properties":{"icon":"$icon","key":"$key"},"geometry":{"type":"Point","coordinates":[${feature.longitude},${feature.latitude}]}}"""
    }
    return """{"type":"FeatureCollection","features":[$body]}"""
}

private fun pointCollection(longitude: Double, latitude: Double): String =
    """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[$longitude,$latitude]}}]}"""

private const val DROP_SOURCE = "xhere-drops"
private const val DROP_LAYER = "xhere-drops-symbols"
private const val ME_SOURCE = "xhere-me"
private const val ME_HALO = "xhere-me-halo"
private const val ME_DOT = "xhere-me-dot"
private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
