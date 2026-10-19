package world.xhere.shared.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import world.xhere.shared.geo.LatLng

/**
 * Time Machine and "This day here". The rules are the same ones the importer
 * uses: town and state come from the region file, and a sheet is an overlay
 * only when the control points actually line up.
 */
class HistoryTest {
    @Test
    fun erasAreTheYearsThatHaveAMapOrAPhoto() {
        val sheets = listOf(sheet(1886), sheet(1891), sheet(null))
        val photos = listOf(photo(1905), photo(1886))
        assertEquals(listOf(1886, 1891, 1905), History.eras(sheets, photos))
        assertEquals(listOf(1886), History.sheetsFor(sheets, 1886).map { it.year })
        assertEquals(listOf(1905), History.photosFor(photos, 1905).map { it.year })
    }

    @Test
    fun placeTermsKeepTheStreetAndDropTheTown() {
        val terms = History.placeTerms(
            name = "West 3rd Street",
            address = "West 3rd Street, Jamestown, New York, 14701",
            town = "Jamestown",
            state = "New York",
        )
        assertEquals(listOf("West 3rd Street"), terms)
        assertTrue(
            History.placeTerms("Jamestown", "Jamestown, New York", "Jamestown", "New York").isEmpty(),
        )
        assertTrue(History.placeTerms("Street", null, "Jamestown", "New York").isEmpty())
        assertEquals(
            listOf("Hotel Jamestown"),
            History.placeTerms("Hotel Jamestown", null, "Jamestown", "New York"),
        )
    }

    @Test
    fun aNewYorkPaperMustMentionJamestownNextToTheStreet() {
        val hit = ClippingFilter.window(
            text = "The pavement on West 3rd Street in Jamestown was torn up for the new main, " +
                "and wagons were turned onto Cherry until the block was open again yesterday morning.",
            phrase = "West 3rd Street",
            town = "Jamestown",
            paperCity = "New York",
            paperState = "New York",
            regionState = "New York",
        )
        assertNotNull(hit)
        assertTrue(hit!!.contains("West 3rd Street"))
        assertTrue(hit.contains("Jamestown"))

        val northDakota = ClippingFilter.window(
            text = "West 3rd Street in Jamestown, N.D. was the scene of a long meeting of the " +
                "county board, which sat until well after dark and then adjourned.",
            phrase = "West 3rd Street",
            town = "Jamestown",
            paperCity = "New York",
            paperState = "New York",
            regionState = "New York",
        )
        assertNull(northDakota)

        val otherState = ClippingFilter.window(
            text = "West 3rd Street in Jamestown was busy from morning until the shops closed " +
                "and the evening train left the depot with a full load of passengers.",
            phrase = "West 3rd Street",
            town = "Jamestown",
            paperCity = "Jamestown",
            paperState = "North Dakota",
            regionState = "New York",
        )
        assertNull(otherState)

        val farApart = ClippingFilter.window(
            text = "Jamestown sent a delegation. " + "word ".repeat(120) +
                "West 3rd Street was mentioned in a city directory far from that note, " +
                "after many other unrelated lines of ordinary local advertising copy.",
            phrase = "West 3rd Street",
            town = "Jamestown",
            paperCity = "Buffalo",
            paperState = "New York",
            regionState = "New York",
        )
        assertNull(farApart)
    }

    @Test
    fun thirdAnd3rdMatchAndThisDateSortsFirst() {
        val text = "Work on West Third Street in Jamestown continued through the week, " +
            "with the curb reset and the walk opened to foot traffic again."
        val hit = ClippingFilter.window(
            text = text,
            phrase = "West 3rd Street",
            town = "Jamestown",
            paperCity = "New York",
            paperState = "NY",
            regionState = "New York",
        )
        assertNotNull(hit)
        val older = clipping(1890, 10, 19)
        val today = clipping(1910, 3, 2)
        val ranked = History.rankClippings(listOf(older, today, older.copy(id = "dup", pageUrl = older.pageUrl)), 3, 2)
        assertEquals(listOf("today", "older"), ranked.map { it.id })
        assertTrue(History.onThisDate(today, 3, 2))
        assertFalse(History.onThisDate(older, 3, 2))
    }

    @Test
    fun threeControlPointsMakeAMapOverlayAndTwoDoNot() {
        val points = listOf(
            ControlPoint(0.0, 0.0, -79.25, 42.10, "northwest"),
            ControlPoint(1000.0, 0.0, -79.23, 42.10, "northeast"),
            ControlPoint(0.0, 800.0, -79.25, 42.084, "southwest"),
        )
        val corners = Georef.corners(1000, 800, points)
        assertNotNull(corners)
        assertEquals(4, corners!!.size)
        assertEquals(-79.25, corners[0].longitude, 1e-6)
        assertEquals(42.10, corners[0].latitude, 1e-6)
        assertEquals(-79.23, corners[1].longitude, 1e-6)
        assertEquals(42.10, corners[1].latitude, 1e-6)
        assertEquals(-79.23, corners[2].longitude, 1e-4)
        assertEquals(42.084, corners[2].latitude, 1e-4)
        assertNull(Georef.corners(1000, 800, points.take(2)))
        assertNull(
            Georef.corners(
                1000,
                800,
                listOf(
                    ControlPoint(0.0, 0.0, -79.25, 42.10),
                    ControlPoint(10.0, 0.0, -79.24, 42.10),
                    ControlPoint(20.0, 0.0, -79.23, 42.10),
                ),
            ),
        )
        val overlay = Georef.overlay(
            HistoryGeoref(
                sheetId = "sheet",
                width = 1000,
                height = 800,
                imageUrl = "https://tile.loc.gov/example/full/1024,/0/default.jpg",
                points = points,
                attribution = History.SANBORN_ATTRIBUTION,
            ),
        )
        assertEquals("sheet", overlay?.sheetId)
        assertEquals(History.SANBORN_ATTRIBUTION, overlay?.attribution)
        assertNull(
            Georef.overlay(
                HistoryGeoref("sheet", 1000, 800, "https://tile.loc.gov/x", points.take(2), History.SANBORN_ATTRIBUTION),
            ),
        )
    }

    @Test
    fun iiifUrlsUseTheImageService() {
        val image = "https://tile.loc.gov/image-services/iiif/service:gmd:example:06011/full/pct:12.5/0/default.jpg#h=10&w=10"
        val service = Iiif.serviceBase(image)
        assertEquals("https://tile.loc.gov/image-services/iiif/service:gmd:example:06011", service)
        assertEquals("$service/info.json", Iiif.infoUrl(service!!))
        assertEquals("$service/full/1024,/0/default.jpg", Iiif.sized(service, 1024))
        assertEquals("$service/0,0,512,512/512,/0/default.jpg", Iiif.tileUrl(service, 0, 0, 512, 512, 512))
        assertEquals(1886, Iiif.yearOf("1886-03"))
        assertEquals(3, Iiif.sheetIndex("https://www.loc.gov/resource/g3804jm.g060111886/?sp=3"))
        assertNull(Iiif.serviceBase("https://www.loc.gov/item/sanborn06011_001/"))
    }

    @Test
    fun commonsLicensesAreShownOnlyWhenTheFileAllowsIt() {
        assertTrue(PhotoLicense.allowed("CC BY-SA 4.0"))
        assertTrue(PhotoLicense.allowed("No known restrictions"))
        assertTrue(PhotoLicense.allowed("Public domain"))
        assertFalse(PhotoLicense.allowed("All rights reserved"))
        assertFalse(PhotoLicense.allowed(""))
        assertEquals("CC BY-SA", PhotoLicense.commonsName("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertEquals("CC0 1.0", PhotoLicense.commonsName("https://creativecommons.org/publicdomain/zero/1.0/"))
    }

    private fun sheet(year: Int?) = HistorySheet(
        id = "s$year",
        title = "Sheet $year",
        year = year,
        dateLabel = year?.toString() ?: "",
        itemUrl = "https://www.loc.gov/item/example/",
        iiifService = "https://tile.loc.gov/image-services/iiif/service:example",
        thumbnailUrl = null,
        sheetIndex = 1,
        digitized = true,
        attribution = History.SANBORN_ATTRIBUTION,
        license = History.SANBORN_LICENSE,
    )

    private fun photo(year: Int) = HistoryPhoto(
        id = "p$year",
        source = "loc",
        title = "Photo $year",
        year = year,
        dateLabel = year.toString(),
        latitude = null,
        longitude = null,
        thumbUrl = null,
        imageUrl = null,
        pageUrl = "https://www.loc.gov/item/photo$year/",
        creator = null,
        license = History.SANBORN_LICENSE,
        attribution = History.LOC_PHOTO_ATTRIBUTION,
    )

    private fun clipping(year: Int, month: Int, day: Int) = Clipping(
        id = if (year == 1910) "today" else "older",
        terms = listOf("West 3rd Street"),
        title = "The paper",
        year = year,
        month = month,
        day = day,
        snippet = "West 3rd Street in Jamestown was opened after the crew finished the curb and the walk.",
        pageUrl = "https://www.loc.gov/resource/example/$year/",
        imageUrl = "https://tile.loc.gov/example/full/200,/0/default.jpg",
        paper = "Example",
        paperCity = "Jamestown",
        paperState = "New York",
        attribution = History.PAPER_ATTRIBUTION,
        license = History.PAPER_LICENSE,
    )
}
