package net.vash.ft8cbdroid.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Color inspiration: XZ Group's charcoal, ivory, ochre and olive palette. */
object RadioColors {
    val background = Color(0xFF111311)
    val panel = Color(0xFF171A17)
    val ink = Color(0xFFE3E4DA)
    val muted = Color(0xFFA0A59B)
    val line = Color(0xFF343A31)
    val gold = Color(0xFFC9B76C)
    val olive = Color(0xFFA2B292)
}

private val colors = darkColorScheme(
    primary = RadioColors.gold, onPrimary = RadioColors.background,
    primaryContainer = Color(0xFF393522), onPrimaryContainer = RadioColors.ink,
    secondary = RadioColors.olive, onSecondary = RadioColors.background,
    secondaryContainer = Color(0xFF2A3425), onSecondaryContainer = RadioColors.ink,
    tertiary = RadioColors.ink,
    background = RadioColors.background, onBackground = RadioColors.ink,
    surface = RadioColors.panel, onSurface = RadioColors.ink,
    surfaceVariant = Color(0xFF222720), onSurfaceVariant = RadioColors.muted,
    outline = RadioColors.line, outlineVariant = RadioColors.line,
    error = Color(0xFFE6A295), onError = RadioColors.background,
    surfaceContainer = RadioColors.panel,
    surfaceContainerHigh = Color(0xFF222720),
)
private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
)

@Composable
fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        content = content)
}
