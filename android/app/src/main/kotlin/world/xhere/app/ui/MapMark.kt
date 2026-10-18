package world.xhere.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A quiet "you are here" mark. No stock illustration, no map yet (that is M4). */
@Composable
internal fun MapMark(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(88.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(color = tint.copy(alpha = 0.12f), radius = radius)
        drawCircle(color = tint.copy(alpha = 0.22f), radius = radius / 1.7f)
        drawCircle(color = tint, radius = radius / 6f)
    }
}
