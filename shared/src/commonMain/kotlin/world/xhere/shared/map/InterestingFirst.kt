package world.xhere.shared.map

import world.xhere.shared.geo.Geo
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.MapFeature
import world.xhere.shared.places.PublicPlace

/**
 * What the default map is willing to draw.
 *
 * PLAN §0: the lens is what is known about *here*, and the interesting record
 * comes before the practical one. PLAN §4.4 keeps open-data layers on a
 * switch, separate from the category chips. Jamestown is not special here.
 * The same slugs apply in every town.
 *
 * Accessibility pins stay hidden until the person picks the Accessibility
 * category or turns the layer on. Crime, hazards, and the other practical
 * categories follow the same rule through their own layers. A Safety chip
 * still shows the Safety categories, because that chip is the request.
 */
object InterestingFirst {
    const val ACCESS = "access_place"
    const val SAFETY_GROUP = "safety_alerts"
    const val SPARSE_BELOW = 3
    const val PUBLIC_LIMIT = 12
    const val NEAR_M = 45.0
    const val SAME_PLACE_M = 80.0

    val CRIME = setOf("incident_report")
    val HAZARDS = setOf("hazard", "safety_alert")
    val PRACTICAL = setOf(ACCESS) + CRIME + HAZARDS

    private val FILL_SLUGS = setOf(
        "history_stories",
        "art_culture",
        "local_secrets",
        "nature_outdoors",
        "note",
        "historical_fact",
        "historical_marker",
        "story",
        "memorial",
        "public_art",
        "street_art",
        "music_spot",
        "hidden_gem",
        "tip",
        "viewpoint",
    )

    /**
     * [selection] null is the All chip. [accessibility], [crime], and [safety]
     * are the Layers switches. They are off until the person turns them on.
     */
    data class Lens(
        val selection: List<String>? = null,
        val accessibility: Boolean = false,
        val crime: Boolean = false,
        val safety: Boolean = false,
    )

    /** Street zoom keeps more pins. A zoomed-out map keeps fewer so it stays readable. */
    fun pinCap(zoom: Int): Int = when {
        zoom >= 16 -> 36
        zoom >= 14 -> 24
        else -> 16
    }

    fun show(feature: MapFeature, lens: Lens): Boolean {
        if (feature.isCluster) {
            val counts = feature.categoryCounts.orEmpty()
            if (counts.isEmpty()) return true
            return counts.any { (slug, count) -> count > 0 && showSlug(slug, null, lens) }
        }
        if (feature.kind == PublicPlace.KIND) return true
        return showSlug(feature.categorySlug, feature.groupSlug, lens)
    }

    fun showSlug(slug: String?, group: String?, lens: Lens): Boolean {
        if (slug == null || slug !in PRACTICAL) return true
        if (slug in lens.selection.orEmpty()) return true
        if (slug in CRIME || slug in HAZARDS) {
            if (group == SAFETY_GROUP && SAFETY_GROUP in lens.selection.orEmpty()) return true
            if (slug in CRIME && lens.crime) return true
            if (slug in HAZARDS && lens.safety) return true
            return false
        }
        return lens.accessibility
    }

    /** Higher is drawn first and kept when the cap is tight. Clusters always stay. */
    fun score(feature: MapFeature): Int {
        if (feature.isCluster) return 1_000
        if (feature.kind == PublicPlace.KIND) return 84
        val slug = feature.categorySlug
        val base = when (slug) {
            "historical_marker" -> 100
            "historical_fact", "memorial" -> 92
            "story" -> 88
            "public_art", "street_art", "music_spot" -> 80
            "hidden_gem", "tip", "viewpoint" -> 76
            "note" -> 70
            else -> 48
        }
        val voice = when (feature.source) {
            DropSource.USER, DropSource.OFFICIAL, DropSource.BUSINESS -> 12
            else -> 0
        }
        val practical = if (slug in PRACTICAL) -40 else 0
        return base + voice + practical
    }

    fun arrange(features: List<MapFeature>, lens: Lens, zoom: Int): List<MapFeature> {
        val visible = features.filter { show(it, lens) }
        val clusters = visible.filter { it.isCluster }
        val pins = visible
            .filterNot { it.isCluster }
            .sortedWith(compareByDescending<MapFeature> { score(it) }.thenBy { it.shortId.orEmpty() })
        return clusters + pins.take(pinCap(zoom))
    }

    /** A cluster means the area already has drops. Zooming out should not fetch a second set. */
    fun isSparse(features: List<MapFeature>): Boolean {
        if (features.any { it.isCluster }) return false
        val interesting = features.count { feature ->
            !feature.isCluster &&
                feature.kind != PublicPlace.KIND &&
                feature.categorySlug !in PRACTICAL
        }
        return interesting < SPARSE_BELOW
    }

    /** Wikipedia and the rest fill All, and history-shaped filters. Not a food or safety search. */
    fun shouldFill(selection: List<String>?): Boolean {
        if (selection == null) return true
        return selection.any { it in FILL_SLUGS }
    }

    /**
     * Drops already on the map win. A public place on top of the same spot,
     * or with the same name nearby, stays off so the map does not double up.
     */
    fun pickPublic(drops: List<MapFeature>, found: List<PublicPlace>, limit: Int = PUBLIC_LIMIT): List<PublicPlace> {
        val anchors = drops.filterNot { it.isCluster }
        val kept = mutableListOf<PublicPlace>()
        for (place in found) {
            if (kept.size >= limit) break
            if (!Geo.isValid(place.location)) continue
            val onADrop = anchors.any { drop -> crowded(drop.location, drop.title, place) }
            val onAnother = kept.any { other -> crowded(other.location, other.title, place) }
            if (!onADrop && !onAnother) kept += place
        }
        return kept
    }

    private fun crowded(at: world.xhere.shared.geo.LatLng, title: String?, place: PublicPlace): Boolean {
        val meters = Geo.distanceMeters(at, place.location)
        if (meters < NEAR_M) return true
        return meters < SAME_PLACE_M && titlesClose(title.orEmpty(), place.title)
    }

    internal fun titlesClose(left: String, right: String): Boolean {
        val a = left.lowercase().trim()
        val b = right.lowercase().trim()
        if (a.length < 4 || b.length < 4) return false
        return a == b || a in b || b in a
    }
}
