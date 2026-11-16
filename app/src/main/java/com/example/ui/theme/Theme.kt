package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MyraaDarkColorScheme = darkColorScheme(
    primary = NeonPurple,
    onPrimary = TextPrimary,
    primaryContainer = NeonPurpleContainer,
    onPrimaryContainer = OnNeonPurpleContainer,
    secondary = CyberBlue,
    onSecondary = ObsidianBg,
    secondaryContainer = CyberBlueContainer,
    onSecondaryContainer = OnCyberBlueContainer,
    tertiary = ElectricTeal,
    onTertiary = ObsidianBg,
    tertiaryContainer = ElectricTealContainer,
    onTertiaryContainer = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = CosmicSurface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = CosmicSurface,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceCardElevated,
    outline = OutlineSubtle,
    outlineVariant = OutlineGlow,
    error = CrimsonError,
    onError = ObsidianBg,
    errorContainer = CrimsonContainer,
    onErrorContainer = TextPrimary
)

val MyraaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyraaSuperBrainTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MyraaDarkColorScheme,
        typography = MyraaTypography,
        shapes = MyraaShapes,
        content = content
    )
}
