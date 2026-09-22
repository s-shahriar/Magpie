package com.syed.magpie.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.syed.magpie.data.Cookies

/**
 * In-app sign-in.
 *
 * The desktop tooling borrows Chrome's cookie jar; on the phone there is no
 * such jar to read, so the app owns its own WebView session. Cookies persist in
 * the app's own storage and never leave the device.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(site: Cookies.Site, onDone: () -> Unit) {
    var signedIn by remember { mutableStateOf(false) }

    BackHandler { onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to ${site.label}") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (signedIn) TextButton(onClick = onDone) { Text("Done") }
                },
            )
        },
    ) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Facebook and Google both serve a stripped page to anything
                    // that looks like an embedded browser, so present as Chrome.
                    settings.userAgentString = CHROME_MOBILE_UA
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                            signedIn = Cookies.isSignedIn(site)
                        }
                    }
                    loadUrl(site.loginUrl)
                }
            },
        )
    }
}

private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 16; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/141.0.0.0 Mobile Safari/537.36"
