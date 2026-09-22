package com.syed.magpie.ui.screen

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.syed.magpie.BuildConfig
import com.syed.magpie.data.Cookies

/**
 * In-app sign-in.
 *
 * The desktop tooling borrows Chrome's cookie jar; on a phone there is no jar
 * to borrow, so the app owns a WebView session. Cookies stay in the app's own
 * storage and never leave the device.
 *
 * Facebook is awkward here. Its login bundle throws
 * `Cannot read properties of undefined (reading 'getElementsByTagName')` and
 * paints nothing, while the DOM itself loads fine (~166 KB, correct title).
 * Two levers are exposed rather than guessing: the `; wv` token is dropped
 * from the user agent (it marks the client as an embedded WebView), and a
 * Desktop-site toggle swaps in a desktop UA, which serves an entirely
 * different and much simpler login bundle.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(site: Cookies.Site, onDone: () -> Unit) {
    var signedIn by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Facebook's mobile login bundle throws
    // `Cannot read properties of undefined (reading 'getElementsByTagName')`
    // inside a WebView and paints nothing — verified on device, and it is not
    // the `; wv` token, which changes nothing. Its desktop bundle renders a
    // plain working form, so Facebook starts on desktop. Google's mobile page
    // is fine and is much nicer on a phone, so it does not.
    var desktop by remember { mutableStateOf(site == Cookies.Site.FACEBOOK) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var passkeys by remember { mutableStateOf(false) }

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
                    TextButton(onClick = {
                        desktop = !desktop
                        web?.let {
                            it.settings.userAgentString = uaFor(it, desktop)
                            loading = true
                            failure = null
                            it.loadUrl(site.loginUrl)
                        }
                    }) { Text(if (desktop) "Mobile" else "Desktop") }
                    if (signedIn) TextButton(onClick = onDone) { Text("Done") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        web = this
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.userAgentString = uaFor(this, desktop)

                        // Passkeys. A stock WebView has no WebAuthn at all, so
                        // the Google Password Manager sheet never appears and a
                        // passkey-only account cannot sign in here. This bridges
                        // the page's WebAuthn calls to the platform credential
                        // provider; without it the only route is a password.
                        // FOR_APP, not FOR_BROWSER: the browser level is for
                        // privileged browser apps and leaves an ordinary app
                        // with "The user agent does not support public key
                        // credentials" — which is exactly what the page threw.
                        val supported =
                            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)
                        Log.d("MagpieLogin", "WEB_AUTHENTICATION supported=$supported")
                        if (supported) {
                            runCatching {
                                WebSettingsCompat.setWebAuthenticationSupport(
                                    settings,
                                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
                                )
                                passkeys = true
                            }.onFailure {
                                Log.d("MagpieLogin", "setWebAuthenticationSupport failed: $it")
                            }
                        }
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, p: Int) {
                                loading = p < 100
                            }

                            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                                Log.d("MagpieLogin", "console: ${m?.message()}")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                // Installed before the page's own scripts run so
                                // their throws are captured with a stack.
                                if (!BuildConfig.DEBUG) return
                                view?.evaluateJavascript(
                                    """
                                    window.addEventListener('error', function (e) {
                                      console.log('MAGPIE_ERR ' + e.message +
                                        ' @' + e.filename + ':' + e.lineno +
                                        ' stack=' + (e.error && e.error.stack));
                                    }, true);
                                    window.addEventListener('unhandledrejection', function (e) {
                                      console.log('MAGPIE_REJECT ' + e.reason);
                                    });
                                    """.trimIndent(),
                                    null,
                                )
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                                signedIn = Cookies.isSignedIn(site)
                                loading = false
                                if (BuildConfig.DEBUG) {
                                    view?.evaluateJavascript(
                                        "JSON.stringify({" +
                                            "pkc: typeof window.PublicKeyCredential," +
                                            "creds: typeof navigator.credentials," +
                                            "secure: window.isSecureContext," +
                                            "host: location.host})",
                                    ) { Log.d("MagpieLogin", "webauthn: $it") }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    failure = "${error?.description ?: "Could not load"}"
                                    loading = false
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                response: WebResourceResponse?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    failure = "${site.label} returned HTTP ${response?.statusCode}"
                                    loading = false
                                }
                            }
                        }
                        loadUrl(site.loginUrl)
                    }
                },
            )
        }
    }
}

/**
 * The stock WebView agent with `; wv` removed, or a desktop Chrome agent.
 *
 * Only the token is edited in the mobile case: replacing the whole string
 * desyncs it from the `sec-ch-ua` client hints the WebView still sends itself,
 * and sites that check both then see a contradiction.
 */
private fun uaFor(view: WebView, desktop: Boolean): String = if (desktop) {
    DESKTOP_UA
} else {
    view.settings.userAgentString
        .replace("; wv)", ")")
        .replace(" wv)", ")")
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/141.0.0.0 Safari/537.36"
