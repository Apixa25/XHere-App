package world.xhere.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fixed brand colors, not dynamic color. The look is a light map surface,
 * one lagoon primary, and one amber accent for the drop action.
 * Waze-simple: high contrast, few colors, large targets.
 */
private val Lagoon = Color(0xFF0F6E56)
private val LagoonDeep = Color(0xFF053528)
private val LagoonSoft = Color(0xFFD5F5E8)
private val Foam = Color(0xFF7DDCB8)
private val Amber = Color(0xFFC47B12)
private val AmberSoft = Color(0xFFFFE8C2)
private val Ink = Color(0xFF14221C)
private val Paper = Color(0xFFF4F7F5)
private val Night = Color(0xFF0E1613)
private val NightInk = Color(0xFFE6F2EC)
private val Error = Color(0xFFB42318)

private val LightColors = lightColorScheme(
    primary = Lagoon,
    onPrimary = Color.White,
    primaryContainer = LagoonSoft,
    onPrimaryContainer = LagoonDeep,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = AmberSoft,
    onSecondaryContainer = Color(0xFF3D2A04),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF3E524A),
    outline = Color(0xFF6E7F78),
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    tertiary = Color(0xFF6B4E16),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E6D0),
    onTertiaryContainer = Color(0xFF2B2110),
)

private val DarkColors = darkColorScheme(
    primary = Foam,
    onPrimary = LagoonDeep,
    primaryContainer = Color(0xFF0C4A3A),
    onPrimaryContainer = LagoonSoft,
    secondary = Color(0xFFF0C27A),
    onSecondary = Color(0xFF3D2A04),
    secondaryContainer = Color(0xFF5C3E0A),
    onSecondaryContainer = AmberSoft,
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF1A2622),
    onSurfaceVariant = Color(0xFFB7C9C1),
    outline = Color(0xFF7E9188),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFE7C89A),
    onTertiary = Color(0xFF3D2E12),
    tertiaryContainer = Color(0xFF4A3B22),
    onTertiaryContainer = Color(0xFFF6E7D0),
)

private val XHereType = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

private val XHereShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun XHereTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = XHereType,
        shapes = XHereShapes,
        content = content,
    )
}
