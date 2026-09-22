package com.syed.magpie.ui.screen

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.syed.magpie.data.formatBytes
import uniffi.magpie_core.StreamFacts
import uniffi.magpie_core.describeStream

/** A rendition seen going past on the wire. */
data class Captured(
    val url: String,
    val facts: StreamFacts,
) {
    val approxBytes: Long?
        get() = if (facts.bitrate > 0uL && facts.durationSecs > 0uL) {
            (facts.bitrate.toLong() / 8) * facts.durationSecs.toLong()
        } else {
            null
        }
}

/**
 * Captures Facebook stream URLs from the player's own traffic.
 *
 * Reading the page is no longer possible: Facebook stopped shipping the DASH
 * manifest in the HTML and now fetches video data through a runtime GraphQL
 * call, so there is nothing in the markup to parse. What has not changed is
 * that the player must eventually request the media itself — so the post is
 * opened in a WebView with the existing session and every request is examined.
 *
 * This is the Network-tab method, done by the app instead of by hand.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    postUrl: String,
    onPicked: (List<Captured>) -> Unit,
    onDone: () -> Unit,
) {
    val seen = remember { mutableStateListOf<Captured>() }
    var loading by remember { mutableStateOf(true) }

    BackHandler { onDone() }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(if (seen.isEmpty()) "Play the video" else "${seen.size} streams found") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (seen.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = { onPicked(seen.toList()) }) {
                            Text("Use these")
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (seen.isEmpty()) {
                Text(
                    "Tap play on the video below. Magpie picks the stream up as it starts.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(seen) { c ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${c.facts.label}  ·  ${c.approxBytes?.let { formatBytes(it) } ?: "?"}" +
                                    "  ·  ${if (c.facts.isAudio) "audio" else "video"}",
                                Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv)", ")").replace(" wv)", ")")

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null
                                if (looksLikeMedia(url)) {
                                    describeStream(url)?.let { facts ->
                                        // One row per rendition, not per segment.
                                        view?.post {
                                            if (seen.none { it.facts.tag == facts.tag }) {
                                                Log.d("MagpieCapture", "captured ${facts.tag}")
                                                seen += Captured(url, facts)
                                            }
                                        }
                                    }
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                        }
                        loadUrl(postUrl)
                    }
                },
            )
        }
    }
}

/** Media lives on the video CDN and carries the `efg` descriptor. */
private fun looksLikeMedia(url: String): Boolean =
    url.contains("fbcdn.net") && url.contains("efg=") &&
        (url.contains("/v/t2/") || url.contains(".mp4") || url.contains("bytestart"))
