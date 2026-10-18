package world.xhere.shared.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun rejectsOutOfRangeAndNonFinitePoints() {
        assertTrue(!Geo.isValid(LatLng(91.0, 0.0)))
        assertTrue(!Geo.isValid(LatLng(0.0, 181.0)))
        assertTrue(!Geo.isValid(LatLng(Double.NaN, 0.0)))
        assertTrue(Geo.isValid(LatLng(90.0, -180.0)))
    }

    @Test
    fun distanceOfAThousandthOfADegreeOfLatitudeIsAbout111Meters() {
        val distance = Geo.distanceMeters(LatLng(40.7, -74.0), LatLng(40.701, -74.0))
        assertTrue(abs(distance - 111.32) < 1.0, "was $distance")
    }

    @Test
    fun fuzzMatchesTheDatabaseGrid() {
        val nyc = Geo.fuzz(LatLng(40.7128, -74.0060), 100)
        assertClose(40.71280991735537, nyc.latitude)
        assertClose(-74.0057252678305, nyc.longitude)

        val sydney = Geo.fuzz(LatLng(-33.8688, 151.2093), 100)
        assertClose(-33.86857707509881, sydney.latitude)
        assertClose(151.20954285875953, sydney.longitude)

        val wrapped = Geo.fuzz(LatLng(89.99995, 179.999), 1000)
        assertClose(89.99730506647502, wrapped.latitude)
        assertClose(-179.88860941430113, wrapped.longitude)
    }

    @Test
    fun fuzzIsANoOpAtZeroPrecisionAndIdempotent() {
        val point = LatLng(40.7128, -74.0060)
        assertEquals(point, Geo.fuzz(point, 0))
        val once = Geo.fuzz(point, 100)
        assertEquals(once, Geo.fuzz(once, 100))
    }

    @Test
    fun revealAndConfirmRanges() {
        assertTrue(Geo.isRevealed(100.0, revealRadiusM = 100))
        assertTrue(!Geo.isRevealed(100.1, revealRadiusM = 100))
        assertTrue(Geo.isRevealed(10_000.0, revealRadiusM = 10, isMine = true))
        assertEquals(50, Geo.confirmationRangeM(10))
        assertEquals(200, Geo.confirmationRangeM(200))
        assertTrue(Geo.isWithinConfirmationRange(50.0, revealRadiusM = 10))
        assertTrue(!Geo.isWithinConfirmationRange(50.1, revealRadiusM = 10))
    }

    @Test
    fun boundingBoxRejectsTheAntimeridianAndEmptySpans() {
        val box = Geo.boundingBox(LatLng(40.7, -74.0), radiusM = 1_000.0)
        assertTrue(Geo.isValidBoundingBox(box))
        assertTrue(box.minLatitude < 40.7 && box.maxLatitude > 40.7)
        val crossed = BoundingBox(minLongitude = 170.0, minLatitude = 0.0, maxLongitude = -170.0, maxLatitude = 1.0)
        assertTrue(!Geo.isValidBoundingBox(crossed))
        assertTrue(!Geo.isValidRadius(0))
        assertTrue(Geo.isValidRadius(50_000))
        assertTrue(!Geo.isValidZoom(23))
        assertEquals(360.0 / 1024.0 / 8.0, Geo.viewCellSizeDegrees(10))
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected was $actual")
    }
}
