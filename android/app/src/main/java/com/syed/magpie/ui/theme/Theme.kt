package com.syed.magpie.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Coral,
    onPrimary = Ink,
    primaryContainer = CoralWash,
    onPrimaryContainer = Ink,
    // Primary actions are coral. The reference uses its dark tone only as a
    // slim nav bar, so a full-width dark button reads far heavier here than it
    // does there.
    secondary = Coral,
    onSecondary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CardLight,
    onSurfaceVariant = InkSoft,
    // Every container level must be set. Left unset, Material falls back to
    // its default scheme and the cards come out lavender against the cream.
    surfaceContainerLowest = CardLight,
    surfaceContainerLow = CardLight,
    surfaceContainer = CardLight,
    surfaceContainerHigh = CardLight,
    surfaceContainerHighest = CardLight,
    inverseSurface = Ink,
    inverseOnSurface = Cream,
    outline = Backdrop,
    outlineVariant = Backdrop,
    error = Danger,
    errorContainer = Color(0xFFF7DED8),
    onErrorContainer = Ink,
)

private val Dark = darkColorScheme(
    primary = Coral,
    onPrimary = Ink,
    primaryContainer = CoralDeep,
    onPrimaryContainer = NightText,
    secondary = Coral,
    onSecondary = Ink,
    background = NightBg,
    onBackground = NightText,
    surface = NightBg,
    onSurface = NightText,
    surfaceVariant = NightCard,
    onSurfaceVariant = NightTextSoft,
    surfaceContainerLowest = NightCard,
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCard,
    surfaceContainerHighest = NightCard,
    inverseSurface = NightText,
    inverseOnSurface = NightBg,
    outline = NightLine,
    outlineVariant = NightLine,
    error = Danger,
)

/** Generous radii — the mockup's cards are pill-soft, not boxy. */
val MagpieShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun MagpieTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MagpieType,
        shapes = MagpieShapes,
        content = content,
    )
}
