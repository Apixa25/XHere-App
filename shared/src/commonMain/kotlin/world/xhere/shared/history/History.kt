package world.xhere.shared.history

import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.geo.LatLng

/**
 * Time Machine and "This day here" (PLAN §3 history layer, research §4.2).
 *
 * A region file names the town, the state, and which free collections to ask.
 * The phone reads a cache. It does not call loc.gov. Sanborn volumes are the
 * eras for the town. A sheet is drawn on the map only when someone has stored
 * control points for it. Newspaper lines are public-domain OCR, cut down so a
 * bare street word or a different town with the same name does not show up.
 */
object History {
    const val SANBORN_ATTRIBUTION: String =
        "Library of Congress, Geography and Map Division, Sanborn Maps Collection. Public domain."
    const val SANBORN_LICENSE: String = "Public domain"
    const val PAPER_ATTRIBUTION: String =
        "Chronicling America, Library of Congress. Public domain."
    const val PAPER_LICENSE: String = "Public domain"
    const val LOC_PHOTO_ATTRIBUTION: String =
        "Library of Congress. Public domain or no known restrictions. Open the item page for the rights statement."

    /** Photos with a point are kept when the tap is inside this walk. */
    const val PHOTO_RADIUS_M: Int = 800

    /** How many paper lines the Here Card shows. */
    const val CLIPPING_LIMIT: Int = 3

    const val MIN_CONTROL_POINTS: Int = 3

    fun eras(sheets: List<HistorySheet>, photos: List<HistoryPhoto>): List<Int> {
        val years = LinkedHashSet<Int>()
        sheets.mapNotNullTo(years) { it.year }
        photos.mapNotNullTo(years) { it.year }
        return years.sorted()
    }

    fun sheetsFor(sheets: List<HistorySheet>, year: Int?): List<HistorySheet> {
        val rows = if (year == null) sheets else sheets.filter { it.year == year }
        return rows.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.sheetIndex ?: Int.MAX_VALUE }, { it.title }))
    }

    fun photosFor(photos: List<HistoryPhoto>, year: Int?): List<HistoryPhoto> {
        val rows = if (year == null) photos else photos.filter { it.year == year }
        return rows.sortedBy { it.title }
    }

    /**
     * The phrase to look up in the papers. The town and the state are the
     * scope, not the search. A lone "Street" or the town name by itself is
     * too broad. A building name that happens to contain the town is kept.
     */
    fun placeTerms(name: String?, address: String?, town: String, state: String): List<String> {
        val townKey = normalize(town)
        val stateKey = normalize(state)
        val candidates = listOfNotNull(name, streetLine(address))
        val terms = LinkedHashSet<String>()
        for (raw in candidates) {
            val cleaned = raw.trim().replace(Regex("\\s+"), " ")
            if (cleaned.length < 3 || cleaned.length > 80) continue
            val key = normalize(cleaned)
            if (key.isEmpty() || key == townKey || key == stateKey) continue
            if (key in GENERIC) continue
            if (cleaned.all { it.isDigit() || it == '-' || it == ' ' }) continue
            val tokens = key.split(' ').filter { it.isNotEmpty() }
            val useful = tokens.filter { it !in GENERIC && it != townKey && it != stateKey }
            if (useful.isEmpty()) continue
            if (useful.size == 1 && useful[0].length < 4 && !useful[0].any { it.isDigit() }) continue
            terms.add(cleaned)
        }
        return terms.toList()
    }

    /**
     * On this calendar day first, then newer papers. [month] and [day] are
     * the local date the person is standing there, not a stored trail.
     */
    fun rankClippings(rows: List<Clipping>, month: Int, day: Int): List<Clipping> {
        return rows
            .distinctBy { it.pageUrl }
            .sortedWith(
                compareByDescending<Clipping> { it.month == month && it.day == day }
                    .thenByDescending { it.year }
                    .thenBy { it.paper },
            )
            .take(CLIPPING_LIMIT)
    }

    fun onThisDate(clipping: Clipping, month: Int, day: Int): Boolean =
        clipping.month == month && clipping.day == day

    internal fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun streetLine(address: String?): String? {
        val first = address?.split(',')?.firstOrNull()?.trim().orEmpty()
        return first.ifEmpty { null }
    }

    private val GENERIC = setOf(
        "street", "st", "road", "rd", "avenue", "ave", "boulevard", "blvd",
        "drive", "dr", "lane", "ln", "place", "court", "way", "park",
        "the", "and", "of", "new", "york", "ny", "usa", "united", "states",
    )
}

/**
 * One Sanborn volume or one sheet inside it. A volume with no image service
 * is listed so the year still appears. The viewer needs [iiifService].
 */
@Serializable
data class HistorySheet(
    val id: String,
    val title: String,
    val year: Int? = null,
    @SerialName("date_label") val dateLabel: String = "",
    @SerialName("item_url") val itemUrl: String,
    @SerialName("iiif_service") val iiifService: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("sheet_index") val sheetIndex: Int? = null,
    val digitized: Boolean = false,
    val attribution: String,
    val license: String,
)

@Serializable
data class HistoryPhoto(
    val id: String,
    val source: String,
    val title: String,
    val year: Int? = null,
    @SerialName("date_label") val dateLabel: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("page_url") val pageUrl: String,
    val creator: String? = null,
    val license: String,
    val attribution: String,
)

@Serializable
data class ControlPoint(
    @SerialName("pixel_x") val pixelX: Double,
    @SerialName("pixel_y") val pixelY: Double,
    val longitude: Double,
    val latitude: Double,
    val label: String = "",
)

/**
 * A sheet that can be drawn on the map. [points] are native image pixels,
 * origin at the top left, and WGS84. Fewer than three points is not an overlay.
 */
@Serializable
data class HistoryGeoref(
    @SerialName("sheet_id") val sheetId: String,
    val width: Int,
    val height: Int,
    @SerialName("image_url") val imageUrl: String,
    val points: List<ControlPoint> = emptyList(),
    val attribution: String,
)

/** Four image corners, MapLibre order: top-left, top-right, bottom-right, bottom-left. */
data class HistoryOverlay(
    val sheetId: String,
    val imageUrl: String,
    val corners: List<LatLng>,
    val attribution: String,
)

@Serializable
data class Clipping(
    val id: String,
    val terms: List<String> = emptyList(),
    val title: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val snippet: String,
    @SerialName("page_url") val pageUrl: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val paper: String,
    @SerialName("paper_city") val paperCity: String,
    @SerialName("paper_state") val paperState: String,
    val attribution: String,
    val license: String,
) {
    val dateLabel: String
        get() = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

@Serializable
data class HistoryPlace(
    @SerialName("region_id") val regionId: String? = null,
    val town: String = "",
    val state: String = "",
    val sheets: List<HistorySheet> = emptyList(),
    val photos: List<HistoryPhoto> = emptyList(),
    val georef: List<HistoryGeoref> = emptyList(),
) {
    val hasAnything: Boolean get() = sheets.isNotEmpty() || photos.isNotEmpty()

    fun georefFor(sheetId: String): HistoryGeoref? =
        georef.firstOrNull { it.sheetId == sheetId && it.points.size >= History.MIN_CONTROL_POINTS }
}

/**
 * Keeps a paper line only when it is about this town and this place.
 * A paper from another state is dropped even if the town name matches
 * (Jamestown, North Dakota is not Jamestown, New York). A paper printed in
 * another city of the same state has to mention the town next to the place.
 */
object ClippingFilter {
    const val WINDOW: Int = 400
    const val MIN_LETTERS: Int = 40

    fun window(
        text: String,
        phrase: String,
        town: String,
        paperCity: String,
        paperState: String,
        regionState: String,
    ): String? {
        if (!samePlace(paperState, regionState)) return null
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length < MIN_LETTERS) return null
        val letters = flat.count { it.isLetter() }
        if (letters < MIN_LETTERS || letters.toDouble() / flat.length < 0.55) return null
        val phraseAt = findSpan(flat, phrase) ?: return null
        val localPaper = samePlace(paperCity, town)
        if (!localPaper) {
            val townAt = findSpan(flat, town) ?: return null
            if (abs(townAt - phraseAt) > WINDOW) return null
            val around = flat.substring(
                (minOf(townAt, phraseAt) - 80).coerceAtLeast(0),
                (maxOf(townAt, phraseAt) + phrase.length + 80).coerceAtMost(flat.length),
            )
            if (otherJamestown(around, regionState)) return null
        }
        val start = (phraseAt - 90).coerceAtLeast(0)
        val end = (phraseAt + phrase.length + 160).coerceAtMost(flat.length)
        var snippet = flat.substring(start, end).trim()
        if (start > 0) snippet = "…$snippet"
        if (end < flat.length) snippet = "$snippet…"
        return snippet
    }

    fun matches(clipping: Clipping, terms: List<String>, town: String, state: String): Boolean {
        if (!samePlace(clipping.paperState, state)) return false
        if (terms.isEmpty()) return false
        val wanted = terms.map { History.normalize(it) }.toSet()
        val have = clipping.terms.map { History.normalize(it) }.toSet()
        if (wanted.none { it in have }) return false
        return window(
            text = clipping.snippet,
            phrase = terms.first { History.normalize(it) in have },
            town = town,
            paperCity = clipping.paperCity,
            paperState = clipping.paperState,
            regionState = state,
        ) != null
    }

    private fun samePlace(left: String, right: String): Boolean {
        val a = History.normalize(left)
        val b = History.normalize(right)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a == stateAbbrev(b) || b == stateAbbrev(a)
    }

    private fun stateAbbrev(name: String): String = when (name) {
        "new york" -> "ny"
        "north dakota" -> "nd"
        "pennsylvania" -> "pa"
        else -> name
    }

    private fun otherJamestown(window: String, regionState: String): Boolean {
        if (History.normalize(regionState) != "new york") return false
        val key = History.normalize(window)
        return "north dakota" in key || " n d " in " $key " || " nd " in " $key "
    }

    /** Index in [text] of [phrase], also accepting 3rd/third. */
    private fun findSpan(text: String, phrase: String): Int? {
        val needle = History.normalize(phrase)
        if (needle.isEmpty()) return null
        val forms = listOf(needle, ordinalWords(needle)).distinct()
        for (form in forms) {
            val pattern = Regex(form.split(' ').joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
            val found = pattern.find(text)
            if (found != null) return found.range.first
        }
        return null
    }

    private fun ordinalWords(phrase: String): String =
        phrase.split(' ').joinToString(" ") { token ->
            ORDINALS[token] ?: token
        }

    private val ORDINALS = mapOf(
        "1st" to "first",
        "2nd" to "second",
        "3rd" to "third",
        "4th" to "fourth",
        "5th" to "fifth",
        "6th" to "sixth",
        "7th" to "seventh",
        "8th" to "eighth",
        "9th" to "ninth",
        "first" to "1st",
        "second" to "2nd",
        "third" to "3rd",
        "fourth" to "4th",
        "fifth" to "5th",
        "sixth" to "6th",
        "seventh" to "7th",
        "eighth" to "8th",
        "ninth" to "9th",
    )
}

/** IIIF Image API 2 URLs. The viewer and the map overlay both use these. */
object Iiif {
    fun serviceBase(imageUrl: String): String? {
        val cut = imageUrl.substringBefore('#').substringBefore("/full/")
        if (!cut.startsWith("https://") || cut == imageUrl.substringBefore('#')) return null
        return cut.trimEnd('/')
    }

    fun infoUrl(service: String): String = "${service.trimEnd('/')}/info.json"

    fun sized(service: String, maxWidth: Int): String {
        val width = maxWidth.coerceIn(64, 4096)
        return "${service.trimEnd('/')}/full/$width,/0/default.jpg"
    }

    /**
     * One tile. [x], [y], [width], and [height] are pixels of the full image.
     * [size] is the pixel width requested back (IIIF `w,`).
     */
    fun tileUrl(service: String, x: Int, y: Int, width: Int, height: Int, size: Int): String {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val s = size.coerceIn(1, 4096)
        return "${service.trimEnd('/')}/$x,$y,$w,$h/$s,/0/default.jpg"
    }

    fun yearOf(dateLabel: String?): Int? {
        if (dateLabel.isNullOrBlank()) return null
        val match = Regex("(1[7-9]\\d{2}|20\\d{2})").find(dateLabel) ?: return null
        return match.value.toInt()
    }

    fun sheetIndex(pageUrl: String?): Int? {
        if (pageUrl.isNullOrBlank()) return null
        val match = Regex("[?&]sp=(\\d+)").find(pageUrl) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}

/**
 * Turns control points into the four corners MapLibre's image source expects.
 * Three points use an affine fit. Four or more use a homography. A bad fit
 * returns null so the sheet stays in the viewer and off the map.
 */
object Georef {
    private const val MAX_MEAN_RESIDUAL_M = 80.0

    fun overlay(sheet: HistoryGeoref): HistoryOverlay? {
        if (sheet.points.size < History.MIN_CONTROL_POINTS) return null
        if (sheet.width < 2 || sheet.height < 2) return null
        if (!sheet.imageUrl.startsWith("https://")) return null
        val corners = corners(sheet.width, sheet.height, sheet.points) ?: return null
        return HistoryOverlay(
            sheetId = sheet.sheetId,
            imageUrl = sheet.imageUrl,
            corners = corners,
            attribution = sheet.attribution,
        )
    }

    fun corners(width: Int, height: Int, points: List<ControlPoint>): List<LatLng>? {
        if (points.size < History.MIN_CONTROL_POINTS) return null
        if (points.any { it.pixelX < -1 || it.pixelY < -1 || it.pixelX > width + 1 || it.pixelY > height + 1 }) {
            return null
        }
        if (!spreadOut(points)) return null
        val project = if (points.size >= 4) homography(points) else affine(points)
        project ?: return null
        val samples = points.map { point ->
            val guessed = project(point.pixelX, point.pixelY) ?: return null
            world.xhere.shared.geo.Geo.distanceMeters(
                LatLng(point.latitude, point.longitude),
                guessed,
            )
        }
        if (samples.average() > MAX_MEAN_RESIDUAL_M) return null
        val tl = project(0.0, 0.0) ?: return null
        val tr = project(width.toDouble(), 0.0) ?: return null
        val br = project(width.toDouble(), height.toDouble()) ?: return null
        val bl = project(0.0, height.toDouble()) ?: return null
        val corners = listOf(tl, tr, br, bl)
        if (corners.any { !world.xhere.shared.geo.Geo.isValid(it) }) return null
        val span = world.xhere.shared.geo.Geo.distanceMeters(tl, br)
        if (span < 30.0 || span > 8_000.0) return null
        return corners
    }

    private fun spreadOut(points: List<ControlPoint>): Boolean {
        val xs = points.map { it.pixelX }
        val ys = points.map { it.pixelY }
        return (xs.max() - xs.min()) >= 8.0 && (ys.max() - ys.min()) >= 8.0
    }

    private fun affine(points: List<ControlPoint>): ((Double, Double) -> LatLng?)? {
        val n = points.size
        val ata = Array(6) { DoubleArray(6) }
        val atb = DoubleArray(6)
        for (point in points) {
            val rowX = doubleArrayOf(point.pixelX, point.pixelY, 1.0, 0.0, 0.0, 0.0)
            val rowY = doubleArrayOf(0.0, 0.0, 0.0, point.pixelX, point.pixelY, 1.0)
            accumulate(ata, atb, rowX, point.longitude)
            accumulate(ata, atb, rowY, point.latitude)
        }
        if (n < 3) return null
        val solved = solve(ata, atb) ?: return null
        return { x, y ->
            val lng = solved[0] * x + solved[1] * y + solved[2]
            val lat = solved[3] * x + solved[4] * y + solved[5]
            LatLng(lat, lng)
        }
    }

    /**
     * Direct linear transform. Pixel (x, y) to (lng, lat). The last
     * homography cell is fixed at 1.
     */
    private fun homography(points: List<ControlPoint>): ((Double, Double) -> LatLng?)? {
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (point in points) {
            val x = point.pixelX
            val y = point.pixelY
            val lng = point.longitude
            val lat = point.latitude
            val rowLng = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -lng * x, -lng * y)
            val rowLat = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -lat * x, -lat * y)
            accumulate(ata, atb, rowLng, lng)
            accumulate(ata, atb, rowLat, lat)
        }
        val solved = solve(ata, atb) ?: return null
        return { x, y ->
            val denom = solved[6] * x + solved[7] * y + 1.0
            if (abs(denom) < 1e-12) null
            else LatLng(
                (solved[3] * x + solved[4] * y + solved[5]) / denom,
                (solved[0] * x + solved[1] * y + solved[2]) / denom,
            )
        }
    }

    private fun accumulate(ata: Array<DoubleArray>, atb: DoubleArray, row: DoubleArray, target: Double) {
        for (i in row.indices) {
            atb[i] += row[i] * target
            for (j in row.indices) ata[i][j] += row[i] * row[j]
        }
    }

    private fun solve(matrixIn: Array<DoubleArray>, vectorIn: DoubleArray): DoubleArray? {
        val n = vectorIn.size
        val matrix = Array(n) { i -> matrixIn[i].copyOf() }
        val vector = vectorIn.copyOf()
        for (col in 0 until n) {
            var pivot = col
            var best = abs(matrix[col][col])
            for (row in col + 1 until n) {
                val value = abs(matrix[row][col])
                if (value > best) {
                    best = value
                    pivot = row
                }
            }
            if (best < 1e-12) return null
            if (pivot != col) {
                val tmp = matrix[col]
                matrix[col] = matrix[pivot]
                matrix[pivot] = tmp
                val b = vector[col]
                vector[col] = vector[pivot]
                vector[pivot] = b
            }
            val div = matrix[col][col]
            for (j in col until n) matrix[col][j] /= div
            vector[col] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val factor = matrix[row][col]
                if (factor == 0.0) continue
                for (j in col until n) matrix[row][j] -= factor * matrix[col][j]
                vector[row] -= factor * vector[col]
            }
        }
        return vector
    }
}

/** Commons and LoC licenses the app may show. Anything else is left out. */
object PhotoLicense {
    fun allowed(license: String): Boolean {
        val key = license.lowercase()
        if (key.isBlank() || "all rights reserved" in key) return false
        return "public domain" in key ||
            "no known" in key ||
            "cc0" in key ||
            "pdm" in key ||
            "cc by" in key ||
            "cc-by" in key
    }

    fun commonsName(licenseUrlOrName: String): String {
        val key = licenseUrlOrName.lowercase()
        return when {
            "publicdomain/zero" in key || key.contains("cc0") -> "CC0 1.0"
            "publicdomain/mark" in key || "pdm" == key -> "Public Domain Mark"
            "by-sa" in key -> "CC BY-SA"
            "by-nc-sa" in key -> "CC BY-NC-SA"
            "by-nc-nd" in key -> "CC BY-NC-ND"
            "by-nc" in key -> "CC BY-NC"
            "by-nd" in key -> "CC BY-ND"
            "/by/" in key || key.endsWith("by") || "cc by" in key -> "CC BY"
            else -> licenseUrlOrName.trim().ifEmpty { "see the file page" }
        }
    }
}
