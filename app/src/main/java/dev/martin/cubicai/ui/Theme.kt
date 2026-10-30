package dev.martin.cubicai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.martin.cubicai.R

/** The cube's three faces (lit top, mid left, shaded right): the brand mark and the thinking indicator. */
@Immutable
data class CubeColors(val top: Color, val left: Color, val right: Color)

val LocalCube = staticCompositionLocalOf { CubeColors(Color(0xFFFFC861), Color(0xFFF29E38), Color(0xFFC46F1C)) }

/** Angular display face whose bevelled corners echo the cut-corner shapes used across the UI. */
val Chakra = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB547),
    onPrimary = Color(0xFF1B1405),
    primaryContainer = Color(0xFF4A3510),
    onPrimaryContainer = Color(0xFFFFDDA6),
    secondary = Color(0xFFA9B4E8),
    onSecondary = Color(0xFF161B33),
    background = Color(0xFF161B33),
    onBackground = Color(0xFFE9ECF8),
    surface = Color(0xFF161B33),
    onSurface = Color(0xFFE9ECF8),
    surfaceVariant = Color(0xFF2D3765),
    onSurfaceVariant = Color(0xFFA3ABCF),
    surfaceContainerLowest = Color(0xFF11152A),
    surfaceContainerLow = Color(0xFF1A2040),
    surfaceContainer = Color(0xFF1E2546),
    surfaceContainerHigh = Color(0xFF222A4D),
    surfaceContainerHighest = Color(0xFF2D3765),
    secondaryContainer = Color(0xFF333E70),
    onSecondaryContainer = Color(0xFFE9ECF8),
    outline = Color(0xFF4A5488),
    outlineVariant = Color(0xFF2E3765),
    inverseSurface = Color(0xFFE9ECF8),
    inverseOnSurface = Color(0xFF161B33),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF4A1F2A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Light = lightColorScheme(
    primary = Color(0xFFB8650F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3B8),
    onPrimaryContainer = Color(0xFF3D2604),
    secondary = Color(0xFF3B4A8F),
    onSecondary = Color.White,
    background = Color(0xFFF3F5FB),
    onBackground = Color(0xFF1B2140),
    surface = Color(0xFFF3F5FB),
    onSurface = Color(0xFF1B2140),
    surfaceVariant = Color(0xFFE2E6F3),
    onSurfaceVariant = Color(0xFF555E86),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEDF0F9),
    surfaceContainer = Color(0xFFE8EBF6),
    surfaceContainerHigh = Color(0xFFE2E6F3),
    surfaceContainerHighest = Color(0xFFD9DEEF),
    secondaryContainer = Color(0xFFD5DBF2),
    onSecondaryContainer = Color(0xFF1B2140),
    outline = Color(0xFFB4BBD8),
    outlineVariant = Color(0xFFD3D8EA),
    inverseSurface = Color(0xFF1B2140),
    inverseOnSurface = Color(0xFFF3F5FB),
)

private val Bevels = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(14.dp),
    extraLarge = CutCornerShape(18.dp),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.chakra(weight: FontWeight = FontWeight.SemiBold) = copy(fontFamily = Chakra, fontWeight = weight)
    return base.copy(
        displayLarge = base.displayLarge.chakra(),
        displayMedium = base.displayMedium.chakra(),
        displaySmall = base.displaySmall.chakra(),
        headlineLarge = base.headlineLarge.chakra(),
        headlineMedium = base.headlineMedium.chakra(),
        headlineSmall = base.headlineSmall.chakra(),
        titleLarge = base.titleLarge.chakra(),
        titleMedium = base.titleMedium.chakra(),
        titleSmall = base.titleSmall.chakra(FontWeight.Medium),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.2.sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    )
}

@Composable
fun CubicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = typography(),
        shapes = Bevels,
        content = content,
    )
}
