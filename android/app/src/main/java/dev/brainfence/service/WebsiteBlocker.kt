package dev.brainfence.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the address bar of the foreground browser so `blocked_domains` can be
 * enforced through the accessibility service, the same way the macOS client
 * inspects browser tabs.
 *
 * Fails closed: while any domain is blocked, a browser whose address bar we
 * can't read is blocked as a whole. Otherwise installing another browser
 * would be a one-step bypass.
 */
object WebsiteBlocker {

    /** Supported browsers → view ids of their address bar (first match wins). */
    private val ADDRESS_BAR_IDS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "com.chrome.canary" to listOf("com.chrome.canary:id/url_bar"),
        "org.chromium.chrome" to listOf("org.chromium.chrome:id/url_bar"),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.brave.browser_beta" to listOf("com.brave.browser_beta:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
        "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field", "com.opera.browser:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text",
        ),
        "org.mozilla.firefox" to listOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title",
        ),
        "org.mozilla.firefox_beta" to listOf("org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.fenix" to listOf("org.mozilla.fenix:id/mozac_browser_toolbar_url_view"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
    )

    fun isSupportedBrowser(packageName: String): Boolean = packageName in ADDRESS_BAR_IDS

    /** Packages that open web links, i.e. browsers (supported or not). */
    fun installedBrowsers(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /**
     * The host shown in [packageName]'s address bar, or null if it can't be
     * read (no address bar in this window, or text that isn't an address).
     */
    fun currentHost(root: AccessibilityNodeInfo?, packageName: String): String? {
        root ?: return null
        val ids = ADDRESS_BAR_IDS[packageName] ?: return null
        for (id in ids) {
            val text = root.findAccessibilityNodeInfosByViewId(id)
                .firstNotNullOfOrNull { it.text?.toString()?.takeIf(String::isNotBlank) }
                ?: continue
            return hostOf(text)
        }
        return null
    }

    /** "https://Old.Reddit.com/r/all" or "old.reddit.com/r/all" → "old.reddit.com". */
    internal fun hostOf(address: String): String? {
        var s = address.trim().lowercase()
        if (s.isEmpty() || s.contains(' ')) return null // a search, not an address
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.split('/', '?', '#').first()
        s = s.substringAfterLast('@').substringBefore(':').trimEnd('.')
        return s.takeIf { '.' in it }
    }
}
