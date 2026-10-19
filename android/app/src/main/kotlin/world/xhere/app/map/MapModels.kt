package world.xhere.app.map

import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.here.Here
import world.xhere.shared.here.isPublicRecord
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.Walk
import world.xhere.shared.walks.WalkGuide

/** What the map last asked the repository for. Tests read this to prove the RPC arguments. */
data class ViewportQuery(
    val box: BoundingBox,
    val zoom: Int,
    val categories: List<String>?,
) {
    /** Stable text for UI tests. Not shown to people. */
    fun describe(): String {
        val cats = categories?.joinToString(",") ?: "*"
        return "${box.minLatitude}|${box.minLongitude}|${box.maxLatitude}|${box.maxLongitude}|$zoom|$cats"
    }
}

/** Stable marker text for UI tests: `shortId:group` or `cluster:count`. */
internal fun describeFeatures(features: List<MapFeature>): String = features.joinToString(";") { feature ->
    if (feature.isCluster || feature.shortId == null) {
        "cluster:${feature.pointCount}"
    } else {
        "${feature.shortId}:${feature.groupSlug.orEmpty()}"
    }
}

/** Stable near-me text for UI tests: `title|meters`, nearest first. */
internal fun describeNearMe(cards: List<DropCard>): String = cards.joinToString(";") { card ->
    "${card.title}|${card.distanceM?.toInt() ?: ""}"
}

data class CameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val token: Int,
) {
    val point: LatLng get() = LatLng(latitude, longitude)

    companion object {
        fun jamestown(): CameraTarget = CameraTarget(
            latitude = JamestownDemo.center.latitude,
            longitude = JamestownDemo.center.longitude,
            zoom = JamestownDemo.STREET_ZOOM.toDouble(),
            token = 0,
        )
    }
}

data class CameraFrame(
    val box: BoundingBox,
    val zoom: Int,
)

/**
 * Null [slugs] is every category. A group chip sends that group's slug.
 * A sub-category sheet sends category slugs. Presets send group slugs.
 * The repository passes the list straight through to `drops_in_view` / `drops_nearby`.
 */
data class FilterSelection(
    val slugs: List<String>? = null,
    val activeGroup: String? = null,
    val activePreset: String? = null,
)

data class TeaserUi(
    val shortId: String,
    val title: String,
    val categoryLabel: String,
    val groupLabel: String,
    val icon: String,
    val distanceLabel: String?,
    val revealed: Boolean,
    val body: String?,
    val approximate: Boolean,
    val publicRecord: Boolean = false,
    val attribution: String? = null,
    val viewCount: Long = 0,
    val confirmCount: Int = 0,
)

/**
 * One place on the map. [spot] is the tap. [drops] are already filtered by
 * reveal rules: a locked user drop has a null body.
 */
data class HereSheet(
    val spot: LatLng,
    val loading: Boolean = false,
    val place: PlaceLabel? = null,
    val drops: List<DropCard> = emptyList(),
    val failed: Boolean = false,
)

/**
 * Stable text for UI tests. `place|category:shortId:revealed:user` with
 * groups separated by `;` and rows in a group by `,`. `loading` until the read returns.
 */
internal fun describeHere(sheet: HereSheet): String {
    if (sheet.loading) return "loading"
    val name = sheet.place?.name?.trim().orEmpty().ifEmpty { "none" }
    val groups = Here.groups(sheet.drops).joinToString(";") { group ->
        group.drops.mapIndexed { index, card ->
            val kind = if (card.isPublicRecord()) "open_data" else "user"
            val row = "${card.shortId}:${card.revealed}:$kind"
            if (index == 0) "${group.categorySlug}:$row" else row
        }.joinToString(",")
    }
    return "$name|$groups"
}

enum class MapNotice {
    LOAD_FAILED,
    TEASER_FAILED,
    NEAR_FAILED,
    LOCATION_OFF,
    PRESET_NAME,
    WALK_FAILED,
}

data class MapUiState(
    val loading: Boolean = false,
    val taxonomy: Taxonomy? = null,
    val groups: List<CategoryGroup> = emptyList(),
    val features: List<MapFeature> = emptyList(),
    val selection: FilterSelection = FilterSelection(),
    val presets: List<FilterPreset> = emptyList(),
    val camera: CameraTarget = CameraTarget.jamestown(),
    val myLocation: LatLng? = null,
    val userPanned: Boolean = false,
    val followNextFix: Boolean = false,
    val teaser: TeaserUi? = null,
    val teaserLoading: Boolean = false,
    val nearMe: List<DropCard>? = null,
    val here: HereSheet? = null,
    val subcategoryGroup: String? = null,
    val savingPreset: Boolean = false,
    val notice: MapNotice? = null,
    val lastQuery: ViewportQuery? = null,
    /** The walk for this map, when the camera is inside its region or it is already underway. */
    val walk: Walk? = null,
    /** Quests the signed-in person has not dismissed. Empty until `my_quests` returns. */
    val quests: List<QuestProgress> = emptyList(),
    val celebrateWalk: Boolean = false,
    val celebrateQuest: Boolean = false,
)

/** Straight line through the stops, plus a guide from the person to the next one. */
internal fun describeRoute(state: MapUiState): String {
    val walk = state.walk ?: return "none"
    val points = WalkGuide.route(walk)
    if (points.size < 2) return "none"
    val route = points.joinToString(";") { "${it.longitude},${it.latitude}" }
    val here = state.myLocation
    val next = WalkGuide.nextStop(walk)
    if (walk.started && !walk.completed && here != null && next != null) {
        return "route:$route|guide:${here.longitude},${here.latitude};${next.lng},${next.lat}"
    }
    return "route:$route"
}

/** The guide line drawn while a walk is underway. Empty once the walk is finished. */
internal fun guideLine(state: MapUiState): List<LatLng> {
    val walk = state.walk ?: return emptyList()
    val here = state.myLocation ?: return emptyList()
    if (!walk.started || walk.completed) return emptyList()
    val next = WalkGuide.nextStop(walk) ?: return emptyList()
    return listOf(here, next.location)
}

internal fun markerLabel(feature: MapFeature, taxonomy: Taxonomy?): String {
    if (!feature.isCluster) {
        return iconFor(taxonomy, feature.categorySlug, feature.groupSlug)
    }
    val icons = feature.categoryCounts.orEmpty().entries
        .sortedByDescending { it.value }
        .take(3)
        .joinToString("") { (slug, _) -> iconFor(taxonomy, slug, null) }
    return if (icons.isEmpty()) feature.pointCount.toString() else "${feature.pointCount}\n$icons"
}

internal fun iconFor(taxonomy: Taxonomy?, categorySlug: String?, groupSlug: String?): String {
    val category = categorySlug?.let { taxonomy?.category(it) }
    category?.icon?.let { return it }
    val group = when {
        groupSlug != null -> taxonomy?.group(groupSlug)
        category != null -> taxonomy?.groups?.firstOrNull { it.id == category.groupId }
        else -> null
    }
    return group?.icon ?: "📍"
}

internal fun DropCard.toTeaser(taxonomy: Taxonomy?, distanceM: Double?): TeaserUi {
    val category = taxonomy?.category(categorySlug)
    val group = taxonomy?.group(groupSlug)
    return TeaserUi(
        shortId = shortId,
        title = title,
        categoryLabel = category?.label ?: categorySlug,
        groupLabel = group?.label ?: groupSlug,
        icon = category?.icon ?: group?.icon ?: "📍",
        distanceLabel = distanceM?.let(::formatMeters),
        revealed = revealed || source == DropSource.OPEN_DATA,
        body = if (revealed || source == DropSource.OPEN_DATA) body else null,
        approximate = locationPrecisionM > 0,
        publicRecord = source == DropSource.OPEN_DATA,
        attribution = attribution,
        viewCount = viewCount,
        confirmCount = confirmCount,
    )
}

internal fun formatMeters(meters: Double): String {
    val rounded = meters.toInt()
    if (rounded < 1000) return "$rounded m"
    val tenths = (rounded / 100.0).toInt() / 10.0
    val text = if (tenths == tenths.toInt().toDouble()) tenths.toInt().toString() else tenths.toString()
    return "$text km"
}
