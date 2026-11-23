package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = CopperAmber,
    onPrimary = ObsidianBg,
    primaryContainer = CopperContainer,
    onPrimaryContainer = CopperAmberBright,
    secondary = PrecisionTeal,
    onSecondary = ObsidianBg,
    secondaryContainer = PrecisionTealContainer,
    onSecondaryContainer = ColorTokens.TealBright,
    tertiary = SignalEmerald,
    onTertiary = ObsidianBg,
    background = ObsidianBg,
    onBackground = IvoryText,
    surface = SlateSurface,
    onSurface = IvoryText,
    surfaceVariant = RackCard,
    onSurfaceVariant = TitaniumText,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = RackCard,
    surfaceContainerHigh = RackElevated,
    outline = HairlineBorder,
    outlineVariant = ColorTokens.SubtleBorder,
    error = ClipCrimson,
    onError = IvoryText,
    errorContainer = ClipContainer,
    onErrorContainer = ColorTokens.CrimsonBright
)

private object ColorTokens {
    val TealBright = androidx.compose.ui.graphics.Color(0xFF7DE2DD)
    val CrimsonBright = androidx.compose.ui.graphics.Color(0xFFFFB4AB)
    val SubtleBorder = androidx.compose.ui.graphics.Color(0xFF222731)
}

val ToneForgeShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun ToneForgeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = ToneForgeTypography,
        shapes = ToneForgeShapes,
        content = content
    )
}
