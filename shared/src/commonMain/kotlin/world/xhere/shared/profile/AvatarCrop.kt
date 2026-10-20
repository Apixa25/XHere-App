package world.xhere.shared.profile

import kotlin.math.min

/**
 * Maps pan and zoom onto the square behind a fixed circular avatar mask.
 *
 * Zoom `1` is cover-fit: the shorter side of the photo spans the circle, so
 * the circle is full and there is no empty edge. Larger zoom moves in, up to
 * [MAX_ZOOM]. Pan is the top-left of that square in source pixels and is
 * clamped so the square never leaves the photo.
 *
 * The circle is inscribed in this square. Saving the square is the crop.
 */
object AvatarCrop {
    const val MIN_ZOOM: Float = 1f
    const val MAX_ZOOM: Float = 4f

    data class SourceRect(
        val left: Float,
        val top: Float,
        val side: Float,
    ) {
        val right: Float get() = left + side
        val bottom: Float get() = top + side
    }

    /**
     * Where to draw the photo in a square window of [viewSize] pixels.
     * Layout size is the cover-fit size. [scale] is the extra pinch on top,
     * applied from the top-left. Translation is in view pixels.
     */
    data class Placement(
        val layoutWidth: Float,
        val layoutHeight: Float,
        val scale: Float,
        val translationX: Float,
        val translationY: Float,
    )

    fun side(srcW: Int, srcH: Int, zoom: Float): Float {
        val short = min(srcW, srcH).coerceAtLeast(1).toFloat()
        return short / zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Cover-fit, with the photo centered in the circle. */
    fun centered(srcW: Int, srcH: Int, zoom: Float = MIN_ZOOM): SourceRect {
        val s = side(srcW, srcH, zoom)
        return clamp(srcW, srcH, zoom, (srcW - s) / 2f, (srcH - s) / 2f)
    }

    fun clamp(srcW: Int, srcH: Int, zoom: Float, left: Float, top: Float): SourceRect {
        val s = side(srcW, srcH, zoom)
        val maxLeft = (srcW - s).coerceAtLeast(0f)
        val maxTop = (srcH - s).coerceAtLeast(0f)
        val safeLeft = if (left.isFinite()) left else maxLeft / 2f
        val safeTop = if (top.isFinite()) top else maxTop / 2f
        return SourceRect(
            left = safeLeft.coerceIn(0f, maxLeft),
            top = safeTop.coerceIn(0f, maxTop),
            side = s,
        )
    }

    /**
     * Pinch and drag. [panX] and [panY] move the photo in view pixels
     * (positive is right and down). [zoomChange] is the pinch multiplier.
     * [focalX] and [focalY] are the pinch centroid as fractions of the window.
     * [viewSize] uses the same pixels as the pan.
     */
    fun gesture(
        srcW: Int,
        srcH: Int,
        viewSize: Float,
        zoom: Float,
        left: Float,
        top: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
        focalX: Float,
        focalY: Float,
    ): Pair<Float, SourceRect> {
        val oldZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = zoomChange.takeIf { it.isFinite() && it > 0f } ?: 1f
        val newZoom = (oldZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val oldSide = side(srcW, srcH, oldZoom)
        val newSide = side(srcW, srcH, newZoom)
        val fx = focalX.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        val fy = focalY.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        val anchorX = left + fx * oldSide
        val anchorY = top + fy * oldSide
        var newLeft = anchorX - fx * newSide
        var newTop = anchorY - fy * newSide
        if (viewSize.isFinite() && viewSize > 0f) {
            val scale = viewSize / newSide
            val dx = if (panX.isFinite()) panX else 0f
            val dy = if (panY.isFinite()) panY else 0f
            newLeft -= dx / scale
            newTop -= dy / scale
        }
        return newZoom to clamp(srcW, srcH, newZoom, newLeft, newTop)
    }

    /** Slider zoom. The source point in the middle of the circle stays put. */
    fun zoomAboutCenter(
        srcW: Int,
        srcH: Int,
        zoom: Float,
        newZoom: Float,
        left: Float,
        top: Float,
    ): Pair<Float, SourceRect> {
        val oldZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val target = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val change = if (oldZoom == 0f) 1f else target / oldZoom
        return gesture(
            srcW = srcW,
            srcH = srcH,
            viewSize = 1f,
            zoom = oldZoom,
            left = left,
            top = top,
            panX = 0f,
            panY = 0f,
            zoomChange = change,
            focalX = 0.5f,
            focalY = 0.5f,
        )
    }

    fun placement(
        srcW: Int,
        srcH: Int,
        viewSize: Float,
        zoom: Float,
        left: Float,
        top: Float,
    ): Placement {
        val rect = clamp(srcW, srcH, zoom, left, top)
        val short = min(srcW, srcH).coerceAtLeast(1).toFloat()
        val cover = if (viewSize.isFinite() && viewSize > 0f) viewSize / short else 1f
        val scale = short / rect.side
        return Placement(
            layoutWidth = srcW * cover,
            layoutHeight = srcH * cover,
            scale = scale,
            translationX = -rect.left * cover * scale,
            translationY = -rect.top * cover * scale,
        )
    }
}
