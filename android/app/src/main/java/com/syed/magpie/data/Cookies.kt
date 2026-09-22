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
    enum class Site(val key: String, val label: String, val loginUrl: String, val probe: String) {
        FACEBOOK("facebook", "Facebook", "https://www.facebook.com/login.php", "https://www.facebook.com/"),
        GDRIVE("gdrive", "Google Drive", "https://accounts.google.com/ServiceLogin?service=wise", "https://drive.google.com/"),
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
        }
    }

    fun signOut(site: Site, done: () -> Unit = {}) {
        val cm = CookieManager.getInstance()
        // No per-domain clear exists; drop everything and let the other site be
        // signed in again. Rare enough not to be worth a custom store.
        cm.removeAllCookies { cm.flush(); done() }
    }

    fun flush() = CookieManager.getInstance().flush()
}
