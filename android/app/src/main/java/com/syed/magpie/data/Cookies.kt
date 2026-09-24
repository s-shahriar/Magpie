package com.syed.magpie.data

import android.webkit.CookieManager

/**
 * Cookie access for the sites Magpie pulls from.
 *
 * The phone has no Chrome profile to borrow from the way the desktop CLI does,
 * so the app signs in through its own WebView and reads the jar back out.
 * [CookieManager.getCookie] already returns only the cookies that apply to the
 * URL, which keeps the header small — sending every `google.com` cookie builds
 * a ~15 KB header and Drive answers HTTP 400.
 */
object Cookies {

    /** Sites the app can sign into, in the order the picker shows them. */
    enum class Site(
        val key: String,
        val label: String,
        val loginUrl: String,
        val probe: String,
        val cookieDomain: String,
    ) {
        FACEBOOK(
            "facebook",
            "Facebook",
            "https://www.facebook.com/login.php",
            "https://www.facebook.com/",
            "facebook.com",
        ),
        GDRIVE(
            "gdrive",
            "Google Drive",
            "https://accounts.google.com/ServiceLogin?service=wise",
            "https://drive.google.com/",
            "google.com",
        ),

        // The phone number + OTP form on this page is plain server-side Django,
        // so it works inside a WebView. The Google and Facebook buttons beside
        // it go through a Firebase popup, which an embedded WebView blocks.
        LIVEMCQ(
            "livemcq",
            "LiveMCQ",
            "https://livemcq.com/login/?next=/app/",
            "https://livemcq.com/",
            "livemcq.com",
        ),
        ;

        companion object {
            fun of(key: String?): Site? = entries.firstOrNull { it.key == key }
        }
    }

    fun header(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

    fun headerFor(site: Site): String = header(site.probe)

    /** Signed-in is inferred from the session cookie each site sets. */
    fun isSignedIn(site: Site): Boolean {
        val c = headerFor(site)
        return when (site) {
            Site.FACEBOOK -> c.contains("c_user=")
            Site.GDRIVE -> c.contains("SID=") || c.contains("__Secure-1PSID=")
            Site.LIVEMCQ -> c.contains("sessionid=")
        }
    }

    fun signOut(site: Site, done: () -> Unit = {}) {
        val cm = CookieManager.getInstance()
        // No per-domain clear exists; drop everything and let the other site be
        // signed in again. Rare enough not to be worth a custom store.
        cm.removeAllCookies { cm.flush(); done() }
    }

    fun flush() = CookieManager.getInstance().flush()

    /**
     * Seeds the jar from a Netscape `cookies.txt`.
     *
     * The stored database cannot simply be copied between installs — WebView
     * encrypts cookie values with a key tied to the install, so a copied file
     * is purged on the next launch. Setting them through [CookieManager]
     * sidesteps that entirely: the values are re-encrypted with *this*
     * install's key on the way in.
     *
     * Lets a desktop sign-in carry over, which is handy because Facebook's
     * passkey flow cannot run inside a WebView at all.
     *
     * @return how many cookies were accepted, per site.
     */
    fun importNetscape(text: String): Map<Site, Int> {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val counts = mutableMapOf<Site, Int>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            // "#HttpOnly_" is a real prefix, not a comment.
            val stripped = line.removePrefix("#HttpOnly_")
            if (stripped.isEmpty() || (line.startsWith("#") && stripped == line)) return@forEach

            val f = stripped.split('\t')
            if (f.size < 7) return@forEach
            val (domain, _, path, secure, _, name) = f
            val value = f[6]
            if (name.isBlank()) return@forEach

            val host = domain.removePrefix(".")
            val site = Site.entries.firstOrNull { host.endsWith(it.cookieDomain) } ?: return@forEach

            val attrs = buildString {
                append("$name=$value")
                append("; Domain=$domain")
                append("; Path=${path.ifEmpty { "/" }}")
                if (secure.equals("TRUE", ignoreCase = true)) append("; Secure")
            }
            cm.setCookie("https://$host/", attrs)
            counts[site] = (counts[site] ?: 0) + 1
        }
        cm.flush()
        return counts
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}
