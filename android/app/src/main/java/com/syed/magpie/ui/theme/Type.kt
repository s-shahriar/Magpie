package com.syed.magpie.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.syed.magpie.R

/**
 * Plus Jakarta Sans — the geometric, slightly rounded grotesque the mockup uses.
 * Bundled rather than downloadable so the app renders identically offline, which
 * is rather the point of it.
 */
val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

private fun s(
    weight: FontWeight,
    size: Int,
    line: Int,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Jakarta,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val MagpieType = Typography(
    // The mockup's headings are tight and heavy; negative tracking matters.
    displaySmall = s(FontWeight.Bold, 32, 38, -0.8),
    headlineLarge = s(FontWeight.Bold, 28, 34, -0.6),
    headlineMedium = s(FontWeight.Bold, 24, 30, -0.5),
    headlineSmall = s(FontWeight.SemiBold, 20, 26, -0.3),
    titleLarge = s(FontWeight.SemiBold, 18, 24, -0.2),
    titleMedium = s(FontWeight.SemiBold, 16, 22),
    titleSmall = s(FontWeight.Medium, 14, 20),
    bodyLarge = s(FontWeight.Normal, 16, 24),
    bodyMedium = s(FontWeight.Normal, 14, 20),
    bodySmall = s(FontWeight.Normal, 12, 16),
    labelLarge = s(FontWeight.SemiBold, 14, 18),
    labelMedium = s(FontWeight.Medium, 12, 16, 0.2),
    labelSmall = s(FontWeight.Medium, 11, 14, 0.3),
)
