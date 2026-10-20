package world.xhere.app.profile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Drag, pinch, and double-tap for the avatar crop. Pointers are consumed
 * only after the finger clears the touch slop, and only inside this scope,
 * so a parent sheet handle can still dismiss.
 */
internal suspend fun PointerInputScope.detectAvatarCropGestures(
    onGesture: (pan: Offset, zoomChange: Float, centroid: Offset) -> Unit,
    onDoubleTap: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val tapTimeout = viewConfiguration.longPressTimeoutMillis
    var lastTapUptime = 0L
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val secondTap = lastTapUptime != 0L && first.uptimeMillis - lastTapUptime <= doubleTapTimeout
        val downPosition = first.position
        val downTime = first.uptimeMillis
        var pastSlop = false
        var panAccum = Offset.Zero
        var zoomAccum = 1f
        var multiTouch = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) {
                lastTapUptime = 0L
                break
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                val up = event.changes.firstOrNull()
                val elapsed = (up?.uptimeMillis ?: downTime) - downTime
                val moved = (up?.position ?: downPosition) - downPosition
                val tap = !pastSlop && !multiTouch && elapsed < tapTimeout && moved.getDistance() <= touchSlop
                if (tap && secondTap) {
                    onDoubleTap()
                    lastTapUptime = 0L
                } else if (tap) {
                    lastTapUptime = up?.uptimeMillis ?: 0L
                } else {
                    lastTapUptime = 0L
                }
                break
            }
            if (pressed.size >= 2) multiTouch = true
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                zoomAccum *= if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
                panAccum += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - zoomAccum) * centroidSize
                if (zoomMotion > touchSlop || panAccum.getDistance() > touchSlop) pastSlop = true
            }
            if (pastSlop) {
                val centroid = event.calculateCentroid(useCurrent = true)
                if (centroid != Offset.Unspecified && (zoomChange != 1f || panChange != Offset.Zero)) {
                    onGesture(panChange, zoomChange, centroid)
                }
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
            }
        }
    }
}
