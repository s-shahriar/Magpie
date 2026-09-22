package com.syed.magpie.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    secondary = Ink,
    onSecondary = Cream,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CardLight,
    onSurfaceVariant = InkSoft,
    surfaceContainer = CardLight,
    surfaceContainerHigh = CardLight,
    outline = Backdrop,
    outlineVariant = Backdrop,
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Coral,
    onPrimary = Ink,
    primaryContainer = CoralDeep,
    onPrimaryContainer = NightText,
    secondary = NightText,
    onSecondary = NightBg,
    background = NightBg,
    onBackground = NightText,
    surface = NightBg,
    onSurface = NightText,
    surfaceVariant = NightCard,
    onSurfaceVariant = NightTextSoft,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCard,
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
