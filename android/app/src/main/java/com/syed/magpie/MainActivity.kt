package com.syed.magpie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.syed.magpie.ui.MagpieApp
import com.syed.magpie.ui.LiveMcqViewModel
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.Module
import com.syed.magpie.ui.StillVideoViewModel
import com.syed.magpie.ui.theme.MagpieTheme

class MainActivity : ComponentActivity() {
    private val vm: MagpieViewModel by viewModels()
    private val still: StillVideoViewModel by viewModels()
    private val livemcq: LiveMcqViewModel by viewModels()

    /**
     * Android 13 stopped granting POST_NOTIFICATIONS with the manifest alone.
     * Without the ask, the download service still runs but its notification is
     * silently dropped: no progress, no "saved", nothing at all in the shade.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleIntent(intent)
        setContent {
            MagpieTheme {
                Surface(Modifier.fillMaxSize()) {
                    MagpieApp(
                        vm = vm,
                        still = still,
                        livemcq = livemcq,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            handleShare(intent)
            return
        }
        // Arrived from a notification: land on the queue, and pick a failed
        // job back up if that is the row that was tapped. Consumed, so a
        // rotation or a return to the app does not retry it a second time.
        if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false)) {
            vm.showLibrary()
            intent.removeExtra(EXTRA_OPEN_LIBRARY)
        }
        intent.getStringExtra(EXTRA_RETRY_JOB)?.let {
            vm.resume(it)
            intent.removeExtra(EXTRA_RETRY_JOB)
        }
    }

    /**
     * Sharing a link from Facebook or Drive drops it straight into the box;
     * sharing a photo opens it in Still → Video.
     */
    private fun handleShare(intent: Intent) {
        if (intent.type?.startsWith("image/") == true) {
            val photo = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (photo != null) {
                still.pick(photo)
                vm.openModule(Module.StillVideo)
            }
            return
        }
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        // Apps often share "caption https://…"; keep the URL.
        val url = shared.split(Regex("\\s+")).lastOrNull { it.startsWith("http") }
        if (!url.isNullOrEmpty()) {
            vm.link = url
            vm.openModule(Module.Downloader)
            vm.fetch()
        }
    }

    companion object {
        const val EXTRA_OPEN_LIBRARY = "openLibrary"
        const val EXTRA_RETRY_JOB = "retryJob"
    }
}
