package world.xhere.shared.demo

import world.xhere.shared.geo.LatLng
import world.xhere.shared.history.Clipping
import world.xhere.shared.history.ControlPoint
import world.xhere.shared.history.History
import world.xhere.shared.history.HistoryGeoref
import world.xhere.shared.history.HistoryPhoto
import world.xhere.shared.history.HistoryPlace
import world.xhere.shared.history.HistorySheet

/**
 * In-memory history for UI tests. The sheet image is a real Library of Congress
 * address so the viewer has something to open. The corners are a rectangle
 * around the demo pin, not surveyed control points. The Jamestown SQL seed
 * does not load this alignment.
 */
object HistoryDemo {
    const val REGION_ID = "us-ny-jamestown"
    const val SHEET_ID = "sanborn06011_001-0002"
    const val OVERLAY_NOTE =
        "Demo alignment for the app. These corners are a rectangle around the demo pin, not surveyed control points."

    private val service =
        "https://tile.loc.gov/image-services/iiif/service:gmd:gmd380m:g3804m:g3804jm:g3804jm_g060111886:06011_1886-0002"

    val place = HistoryPlace(
        regionId = REGION_ID,
        town = "Jamestown",
        state = "New York",
        sheets = listOf(
            HistorySheet(
                id = SHEET_ID,
                title = "Jamestown, March 1886, sheet 2",
                year = 1886,
                dateLabel = "1886-03",
                itemUrl = "https://www.loc.gov/resource/g3804jm.g3804jm_g060111886/?sp=3",
                iiifService = service,
                thumbnailUrl = "$service/full/200,/0/default.jpg",
                sheetIndex = 2,
                digitized = true,
                attribution = History.SANBORN_ATTRIBUTION,
                license = History.SANBORN_LICENSE,
            ),
        ),
        photos = listOf(
            HistoryPhoto(
                id = "loc-third-street",
                source = "loc",
                title = "Third Street, Jamestown, N.Y.",
                year = 1905,
                dateLabel = "1905",
                latitude = null,
                longitude = null,
                thumbUrl = null,
                imageUrl = null,
                pageUrl = "https://www.loc.gov/item/example-third-street/",
                creator = null,
                license = "No known restrictions",
                attribution = History.LOC_PHOTO_ATTRIBUTION,
            ),
        ),
        georef = listOf(
            HistoryGeoref(
                sheetId = SHEET_ID,
                width = 1000,
                height = 800,
                imageUrl = "$service/full/1024,/0/default.jpg",
                points = listOf(
                    ControlPoint(0.0, 0.0, -79.25, 42.10, "northwest"),
                    ControlPoint(1000.0, 0.0, -79.23, 42.10, "northeast"),
                    ControlPoint(0.0, 800.0, -79.25, 42.084, "southwest"),
                ),
                attribution = OVERLAY_NOTE,
            ),
        ),
    )

    val clippings = listOf(
        Clipping(
            id = "paper-west-3rd",
            terms = listOf("West 3rd Street"),
            title = "West 3rd Street pavement",
            year = 1898,
            month = 6,
            day = 14,
            snippet = "The pavement on West 3rd Street in Jamestown was torn up for the new main, " +
                "and wagons were turned onto Cherry until the block was open again.",
            pageUrl = "https://www.loc.gov/resource/example/1898-06-14/",
            imageUrl = "https://tile.loc.gov/example/full/200,/0/default.jpg",
            paper = "Jamestown Journal",
            paperCity = "Jamestown",
            paperState = "New York",
            attribution = History.PAPER_ATTRIBUTION,
            license = History.PAPER_LICENSE,
        ),
    )

    fun contains(point: LatLng): Boolean =
        point.longitude in -79.32..-79.18 && point.latitude in 42.05..42.14
}
