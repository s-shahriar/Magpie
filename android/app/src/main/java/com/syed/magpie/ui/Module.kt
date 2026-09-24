package com.syed.magpie.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Everything the first tab can open. The hub lays these out in order, so a
 * new module is one entry here plus its screen in [MagpieApp].
 *
 * Every module keeps its own library, and the Library tab switches between
 * them rather than pooling the rows.
 */
enum class Module(val label: String, val blurb: String, val icon: ImageVector) {
    Downloader("Downloader", "Facebook & Drive", Icons.Default.Download),
    StillVideo("Still → Video", "A photo as a long story", Icons.Default.Movie),
    LiveMcq("LiveMCQ", "Favourites as JSON", Icons.Default.Star),
}
