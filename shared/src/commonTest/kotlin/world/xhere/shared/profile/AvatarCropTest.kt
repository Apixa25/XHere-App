package world.xhere.shared.profile

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarCropTest {
    @Test
    fun coverFitCentersTheShortSideOnTheCircle() {
        val wide = AvatarCrop.centered(400, 200)
        assertEquals(AvatarCrop.MIN_ZOOM, 1f)
        assertEquals(200f, wide.side, 0.001f)
        assertEquals(100f, wide.left, 0.001f)
        assertEquals(0f, wide.top, 0.001f)

        val tall = AvatarCrop.centered(200, 500)
        assertEquals(200f, tall.side, 0.001f)
        assertEquals(0f, tall.left, 0.001f)
        assertEquals(150f, tall.top, 0.001f)
    }

    @Test
    fun minimumZoomIsCoverFitSoTheCircleCannotShowEmptyEdges() {
        val (zoom, rect) = AvatarCrop.gesture(
            srcW = 400,
            srcH = 200,
            viewSize = 200f,
            zoom = 1f,
            left = 100f,
            top = 0f,
            panX = 0f,
            panY = 40f,
            zoomChange = 0.2f,
            focalX = 0.5f,
            focalY = 0.5f,
        )
        assertEquals(AvatarCrop.MIN_ZOOM, zoom, 0.001f)
        assertEquals(200f, rect.side, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertTrue(rect.left >= 0f && rect.right <= 400f)
    }

    @Test
    fun draggingMovesTheSquareAndClampsItInsideThePhoto() {
        val (zoom, rect) = AvatarCrop.gesture(
            srcW = 400,
            srcH = 200,
            viewSize = 200f,
            zoom = 1f,
            left = 100f,
            top = 0f,
            panX = -50f,
            panY = 0f,
            zoomChange = 1f,
            focalX = 0.5f,
            focalY = 0.5f,
        )
        assertEquals(1f, zoom, 0.001f)
        assertEquals(150f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)

        val shoved = AvatarCrop.gesture(
            srcW = 400,
            srcH = 200,
            viewSize = 200f,
            zoom = 1f,
            left = rect.left,
            top = rect.top,
            panX = -10_000f,
            panY = -10_000f,
            zoomChange = 1f,
            focalX = 0f,
            focalY = 0f,
        )
        assertEquals(200f, shoved.second.left, 0.001f)
        assertEquals(0f, shoved.second.top, 0.001f)
        assertEquals(400f, shoved.second.right, 0.001f)
    }

    @Test
    fun pinchKeepsTheFocalPointAndCannotPassTheZoomLimits() {
        val (zoom, rect) = AvatarCrop.gesture(
            srcW = 800,
            srcH = 400,
            viewSize = 200f,
            zoom = 1f,
            left = 200f,
            top = 0f,
            panX = 0f,
            panY = 0f,
            zoomChange = 2f,
            focalX = 0f,
            focalY = 0.5f,
        )
        assertEquals(2f, zoom, 0.001f)
        assertEquals(200f, rect.side, 0.001f)
        assertEquals(200f, rect.left, 0.001f)
        assertEquals(100f, rect.top, 0.001f)

        val capped = AvatarCrop.gesture(
            srcW = 800,
            srcH = 400,
            viewSize = 200f,
            zoom = zoom,
            left = rect.left,
            top = rect.top,
            panX = 0f,
            panY = 0f,
            zoomChange = 10f,
            focalX = 0.5f,
            focalY = 0.5f,
        )
        assertEquals(AvatarCrop.MAX_ZOOM, capped.first, 0.001f)
        assertEquals(100f, capped.second.side, 0.001f)
    }

    @Test
    fun sliderZoomKeepsTheCenterOfTheCircle() {
        val start = AvatarCrop.centered(400, 200)
        val (zoom, rect) = AvatarCrop.zoomAboutCenter(
            srcW = 400,
            srcH = 200,
            zoom = 1f,
            newZoom = 2f,
            left = start.left,
            top = start.top,
        )
        assertEquals(2f, zoom, 0.001f)
        assertEquals(100f, rect.side, 0.001f)
        assertEquals(150f, rect.left, 0.001f)
        assertEquals(50f, rect.top, 0.001f)
        val centerX = rect.left + rect.side / 2f
        val centerY = rect.top + rect.side / 2f
        assertEquals(200f, centerX, 0.001f)
        assertEquals(100f, centerY, 0.001f)
    }

    @Test
    fun placementMapsTheSourceSquareOntoTheWindow() {
        val place = AvatarCrop.placement(
            srcW = 400,
            srcH = 200,
            viewSize = 300f,
            zoom = 2f,
            left = 50f,
            top = 10f,
        )
        assertEquals(2f, place.scale, 0.001f)
        assertEquals(600f, place.layoutWidth, 0.001f)
        assertEquals(300f, place.layoutHeight, 0.001f)
        val cover = 300f / 200f
        val left = 50f * cover * place.scale + place.translationX
        val right = (50f + 100f) * cover * place.scale + place.translationX
        val top = 10f * cover * place.scale + place.translationY
        val bottom = (10f + 100f) * cover * place.scale + place.translationY
        assertEquals(0f, left, 0.01f)
        assertEquals(300f, right, 0.01f)
        assertEquals(0f, top, 0.01f)
        assertEquals(300f, bottom, 0.01f)
    }

    @Test
    fun everyPanAndZoomKeepsTheSquareInsideThePhoto() {
        val sizes = listOf(1 to 1, 2 to 1, 1 to 3, 5 to 4, 640 to 480, 480 to 640, 1600 to 900)
        val zooms = floatArrayOf(0.2f, 1f, 1.7f, 4f, 12f)
        val pans = floatArrayOf(-1_000_000f, -40f, 0f, 40f, 1_000_000f)
        for ((w, h) in sizes) {
            for (zoomChange in zooms) {
                for (panX in pans) {
                    for (panY in pans) {
                        val (zoom, rect) = AvatarCrop.gesture(
                            srcW = w,
                            srcH = h,
                            viewSize = 280f,
                            zoom = 1.4f,
                            left = 0f,
                            top = 0f,
                            panX = panX,
                            panY = panY,
                            zoomChange = zoomChange,
                            focalX = 0.25f,
                            focalY = 0.8f,
                        )
                        val short = min(w, h).toFloat()
                        assertTrue(zoom in AvatarCrop.MIN_ZOOM..AvatarCrop.MAX_ZOOM, "zoom $zoom")
                        assertEquals(short / zoom, rect.side, 0.05f)
                        assertTrue(rect.left >= -0.01f, "left ${rect.left}")
                        assertTrue(rect.top >= -0.01f, "top ${rect.top}")
                        assertTrue(rect.right <= w + 0.05f, "right ${rect.right} of $w")
                        assertTrue(rect.bottom <= h + 0.05f, "bottom ${rect.bottom} of $h")
                    }
                }
            }
        }
    }

    @Test
    fun doubleTapResetIsCoverFitAndCentered() {
        val reset = AvatarCrop.centered(640, 480)
        assertEquals(480f, reset.side, 0.001f)
        assertEquals(80f, reset.left, 0.001f)
        assertEquals(0f, reset.top, 0.001f)
        val again = AvatarCrop.centered(640, 480, zoom = 99f)
        assertEquals(480f / AvatarCrop.MAX_ZOOM, again.side, 0.001f)
    }
}
