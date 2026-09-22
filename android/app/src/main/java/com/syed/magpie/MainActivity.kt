package com.syed.magpie

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.syed.magpie.ui.MagpieApp
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.theme.MagpieTheme

class MainActivity : ComponentActivity() {
    private val vm: MagpieViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            MagpieTheme {
                Surface(Modifier.fillMaxSize()) {
                    MagpieApp(
                        vm = vm,
                    )
                }
            }
        }
    }

    /** Sharing a link from Facebook or Drive drops it straight into the box. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        // Apps often share "caption https://…"; keep the URL.
        val url = shared.split(Regex("\\s+")).lastOrNull { it.startsWith("http") }
        if (!url.isNullOrEmpty()) {
            vm.link = url
            vm.fetch()
        }
    }
}
