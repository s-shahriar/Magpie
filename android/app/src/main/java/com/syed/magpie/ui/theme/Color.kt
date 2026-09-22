package com.syed.magpie.ui.theme

import androidx.compose.ui.graphics.Color

// Sampled from the reference mockup rather than eyeballed, so the app and the
// design agree exactly.
val Coral = Color(0xFFFFA371)        // accent: active nav, progress, CTAs
val CoralDeep = Color(0xFFE8834D)    // pressed / on-dark accent
val CoralWash = Color(0xFFFFE8DA)    // tinted container

val Cream = Color(0xFFF4F1EB)        // page background
val CardLight = Color(0xFFFDFBF9)    // raised card
val Backdrop = Color(0xFFCFC5BD)     // taupe, used for dividers and inactive tracks

val Ink = Color(0xFF282828)          // primary text, dark nav bar
val InkSoft = Color(0xFF6B655F)      // secondary text
val InkFaint = Color(0xFFA39B93)     // tertiary / hints

// Dark theme: keep the coral, invert the warm neutrals rather than going pure
// grey, so the palette still reads as the same family at night.
val NightBg = Color(0xFF17150F)
val NightCard = Color(0xFF221F19)
val NightLine = Color(0xFF3A352C)
val NightText = Color(0xFFF2EDE4)
val NightTextSoft = Color(0xFFB0A89C)

val Danger = Color(0xFFC5533F)
val Success = Color(0xFF5E8C61)
