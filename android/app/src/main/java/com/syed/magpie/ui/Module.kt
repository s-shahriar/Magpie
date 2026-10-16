package com.syed.magpie.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Everything the first tab can open. The hub lays these out in order, so a
 * new module is one entry here plus its screen in [MagpieApp].
 *
 * Every module keeps its own library, and the Library tab switches between
 * them rather than pooling the rows — except those with nothing to keep,
 * which leave [hasLibrary] off and stay out of the Library tab.
 */
enum class Module(
    val label: String,
    val blurb: String,
    val icon: ImageVector,
    val hasLibrary: Boolean = true,
) {
    Downloader("Downloader", "Facebook & Drive", Icons.Default.Download),
    StillVideo("Still → Video", "A photo as a long story", Icons.Default.Movie),
    Subtitles("Subtitles", "Bengali hints in .srt", Icons.Default.Subtitles),
    DhakaFlix("DhakaFlix", "Films & series on the LAN", Icons.Default.LiveTv),
    Installer("Installer", "APKS, XAPK, APKM & APK", Icons.Default.InstallMobile, hasLibrary = false),
}
