package com.syed.magpie

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
}
