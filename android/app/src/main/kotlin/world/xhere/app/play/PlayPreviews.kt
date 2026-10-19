package world.xhere.app.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.xhere.app.R
import world.xhere.app.map.CategoryChipBar
import world.xhere.app.map.FilterPresetStore
import world.xhere.app.map.FilterSelection
import world.xhere.app.map.MapAttribution
import world.xhere.app.map.NearMeSheet
import world.xhere.app.map.TeaserCard
import world.xhere.app.map.TeaserUi
import world.xhere.app.map.formatMeters
import world.xhere.app.ui.TestTags
import world.xhere.app.ui.theme.XHereTheme
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.Geo
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropStatus

/**
 * Store screens, drawn with the same Compose pieces as the app and the
 * Jamestown preview drops (PLAN §7 M9, §10 question 3). MapLibre does not
 * paint under Robolectric, so the map here is a schematic of those coordinates.
 * The chips, teaser, near-me sheet, and copy are the real ones.
 */
internal data class PlayPin(
    val title: String,
    val shortLabel: String,
    val latitude: Double,
    val longitude: Double,
    val color: Color,
)

internal object PlayJamestown {
    /** Downtown pins. Celoron and Buffalo would squash the block into a corner. */
    val downtownIds = setOf("fenton", "lunch", "truck", "concert", "hazard", "benches")

    fun pins(): List<PlayPin> {
        val store = JamestownDemo.store()
        return store.drops.values
            .filter { it.shortId in downtownIds }
            .map { drop ->
                PlayPin(
                    title = drop.title,
                    shortLabel = shortLabel(drop.shortId),
                    latitude = drop.displayLocation.latitude,
                    longitude = drop.displayLocation.longitude,
                    color = Color(android.graphics.Color.parseColor(drop.category.color ?: "#0F6E56")),
                )
            }
    }

    fun teaser(): TeaserUi {
        val store = JamestownDemo.store()
        val lunch = store.drops.values.first { it.shortId == "lunch" }
        return TeaserUi(
            shortId = lunch.shortId,
            title = lunch.title,
            categoryLabel = lunch.category.label,
            groupLabel = store.taxonomy.group(lunch.groupSlug)?.label.orEmpty(),
            icon = lunch.category.icon.orEmpty(),
            distanceLabel = "40 m",
            revealed = false,
            body = null,
            approximate = false,
        )
    }

    /** Near me from West 3rd, Buffalo excluded. Includes the Celoron lake view. */
    fun nearMe(): List<DropCard> {
        val store = JamestownDemo.store()
        return store.drops.values
            .filter { it.shortId != "buffalo" }
            .map { drop ->
                val distance = Geo.distanceMeters(JamestownDemo.center, drop.displayLocation)
                DropCard(
                    id = drop.id,
                    shortId = drop.shortId,
                    categorySlug = drop.category.slug,
                    groupSlug = drop.groupSlug,
                    title = drop.title,
                    latitude = drop.displayLocation.latitude,
                    longitude = drop.displayLocation.longitude,
                    locationPrecisionM = drop.category.locationPrecisionM,
                    distanceM = distance,
                    revealRadiusM = drop.revealRadiusM,
                    revealed = false,
                    status = DropStatus.ACTIVE,
                    isConfirmed = false,
                    confirmCount = 0,
                    denyCount = 0,
                    createdAt = drop.createdAt,
                    authorHandle = drop.authorHandle,
                    isMine = false,
                )
            }
            .sortedBy { it.distanceM }
    }

    fun groups() = JamestownDemo.store().taxonomy.groups.sortedBy { it.sort }

    private fun shortLabel(shortId: String): String = when (shortId) {
        "fenton" -> "Fenton"
        "lunch" -> "West 3rd"
        "truck" -> "Rosa Alley"
        "concert" -> "Tracy Plaza"
        "hazard" -> "Riverwalk"
        "benches" -> "Benches"
        else -> shortId
    }
}

@Composable
internal fun PlayCityMap(pins: List<PlayPin>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFFE4EFE8))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val river = Path().apply {
                moveTo(0f, h * 0.78f)
                quadraticTo(w * 0.28f, h * 0.62f, w * 0.55f, h * 0.74f)
                quadraticTo(w * 0.82f, h * 0.86f, w, h * 0.68f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(river, Color(0xFF8ED4C0))
            drawRoundRect(
                color = Color(0xFFC9E6C4),
                topLeft = Offset(w * 0.58f, h * 0.14f),
                size = Size(w * 0.3f, h * 0.2f),
                cornerRadius = CornerRadius(28f, 28f),
            )
            val street = Color.White
            for (i in 0..4) {
                val y = h * (0.16f + i * 0.12f)
                drawLine(street, Offset(w * 0.05f, y), Offset(w * 0.95f, y), strokeWidth = w * 0.007f)
            }
            for (i in 0..3) {
                val x = w * (0.16f + i * 0.22f)
                drawLine(street, Offset(x, h * 0.08f), Offset(x, h * 0.7f), strokeWidth = w * 0.007f)
            }
        }
        if (pins.isNotEmpty()) {
            val minLat = pins.minOf { it.latitude }
            val maxLat = pins.maxOf { it.latitude }
            val minLng = pins.minOf { it.longitude }
            val maxLng = pins.maxOf { it.longitude }
            pins.forEach { pin ->
                val x = ((pin.longitude - minLng) / (maxLng - minLng)).toFloat().coerceIn(0f, 1f)
                val y = (1f - ((pin.latitude - minLat) / (maxLat - minLat)).toFloat()).coerceIn(0f, 1f)
                val xFrac = 0.14f + x * 0.72f
                val yFrac = 0.18f + y * 0.42f
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * xFrac - 8.dp, y = maxHeight * yFrac - 8.dp)
                        .size(16.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .background(pin.color, CircleShape),
                    )
                }
                Text(
                    text = pin.shortLabel,
                    modifier = Modifier.offset(x = maxWidth * xFrac - 36.dp, y = maxHeight * yFrac + 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 11.sp,
                    color = Color(0xFF14221C),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PlayBottomBar(selected: String) {
    val tabs = listOf(
        Triple("map", R.string.nav_map, Icons.Filled.Place),
        Triple("explore", R.string.nav_explore, Icons.Filled.Search),
        Triple("drop", R.string.nav_drop, Icons.Filled.Add),
        Triple("profile", R.string.nav_profile, Icons.Filled.Person),
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        tabs.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = route == selected,
                onClick = {},
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
internal fun PlayMapPreview() {
    val groups = PlayJamestown.groups()
    val teaser = PlayJamestown.teaser()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PlayBottomBar("map") },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("play-map"),
        ) {
            PlayCityMap(PlayJamestown.pins(), Modifier.fillMaxSize())
            CategoryChipBar(
                groups = groups,
                presets = listOf(FilterPresetStore.WEEKEND),
                selection = FilterSelection(),
                onSelectAll = {},
                onSelectPreset = {},
                onSelectGroup = {},
                onLongPressGroup = {},
                onSave = {},
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TeaserCard(teaser = teaser, onClose = {}, onOpen = {})
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 4.dp,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.drop_fab),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = stringResource(R.string.map_near_me),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            text = "◎",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                MapAttribution()
            }
        }
    }
}

@Composable
internal fun PlayNearPreview() {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PlayBottomBar("map") },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("play-near"),
        ) {
            PlayCityMap(PlayJamestown.pins(), Modifier.fillMaxSize())
            NearMeSheet(
                cards = PlayJamestown.nearMe().take(5),
                onOpen = {},
                onDismiss = {},
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayDropPreview() {
    val store = JamestownDemo.store()
    val suggestions = listOf("eatery_tip", "food_truck", "event").map { slug ->
        store.taxonomy.category(slug)!!
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = { PlayBottomBar("drop") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("play-drop"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.drop_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.drop_body), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text(stringResource(R.string.drop_shutter)) }
                OutlinedButton(onClick = {}) { Text(stringResource(R.string.drop_pick)) }
            }
            OutlinedTextField(
                value = "Cafes and outdoor tables on West 3rd",
                onValueChange = {},
                label = { Text(stringResource(R.string.drop_text_label)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
            )
            Text(stringResource(R.string.drop_suggestions), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { category ->
                    FilterChip(
                        selected = category.slug == "eatery_tip",
                        onClick = {},
                        label = { Text(listOfNotNull(category.icon, category.label).joinToString(" ")) },
                    )
                }
            }
            Button(onClick = {}) { Text(stringResource(R.string.drop_post)) }
            Text(
                text = stringResource(R.string.drop_agreement),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PlayDetailPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("play-detail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text("🍽️", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "Eatery tip",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = "West 3rd at lunch", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.detail_author, "demo_guide"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.detail_progress, 0, 3),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.detail_fresh_never),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.detail_away, formatMeters(620.0)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.detail_walk_closer, 120),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.detail_within, 500),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(stringResource(R.string.detail_share), modifier = Modifier.padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {}) { Text(stringResource(R.string.safety_report_drop)) }
            TextButton(onClick = {}) { Text(stringResource(R.string.safety_hide_drop)) }
        }
    }
}

private val FeatureLagoon = Color(0xFF0F6E56)
private val FeatureFoam = Color(0xFFD5F5E8)
private val FeatureAmber = Color(0xFFFFE8C2)

@Composable
internal fun PlayFeatureGraphic() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FeatureLagoon)
            .testTag("play-feature"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1.05f)
                .padding(start = 48.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 76.sp,
            )
            Text(
                text = stringResource(R.string.tagline),
                color = FeatureFoam,
                fontSize = 26.sp,
                lineHeight = 32.sp,
            )
            Text(
                text = "Jamestown, New York",
                color = FeatureAmber,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(28.dp)
                .fillMaxSize(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFFE4EFE8),
        ) {
            PlayCityMap(PlayJamestown.pins(), Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun PlayIcon() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeatureLagoon)
            .testTag("play-icon"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension
            drawCircle(color = Color(0xFFD5F5E8), radius = radius * 0.22f, center = center)
            drawCircle(color = Color.White, radius = radius * 0.074f, center = center)
        }
    }
}

@Preview(name = "Map", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun PreviewPlayMap() {
    XHereTheme { PlayMapPreview() }
}

@Preview(name = "Near me", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun PreviewPlayNear() {
    XHereTheme { PlayNearPreview() }
}

@Preview(name = "Drop", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun PreviewPlayDrop() {
    XHereTheme { PlayDropPreview() }
}

@Preview(name = "Reveal", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun PreviewPlayDetail() {
    XHereTheme { PlayDetailPreview() }
}

@Preview(name = "Feature graphic", widthDp = 1024, heightDp = 500, showBackground = true)
@Composable
private fun PreviewPlayFeature() {
    PlayFeatureGraphic()
}

@Preview(name = "Icon", widthDp = 512, heightDp = 512, showBackground = true)
@Composable
private fun PreviewPlayIcon() {
    PlayIcon()
}
