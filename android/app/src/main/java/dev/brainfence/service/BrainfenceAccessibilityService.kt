package dev.brainfence.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.brainfence.domain.blocking.BlockingState
import dev.brainfence.ui.blocking.BlockingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monitors foreground app changes and launches the blocking overlay when the
 * user opens an app that is currently blocked, or a blocked website in a
 * browser (see [WebsiteBlocker]).
 */
class BrainfenceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BrainfenceA11y"

        /** Minimum gap between address-bar reads for content-change events. */
        private const val CONTENT_CHECK_INTERVAL_MS = 500L

        /** How often the installed-browser list is refreshed. */
        private const val BROWSER_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

        private val _foregroundApp = MutableStateFlow<String?>(null)
        /** The package name of the current foreground app. Used by MeditationTimerManager for companion app detection. */
        val foregroundApp: StateFlow<String?> = _foregroundApp.asStateFlow()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var blockingState: BlockingState = BlockingState(emptySet(), emptySet(), emptyMap())
    private var blockingActivityShown = false
    /** What the overlay was last shown for: "app:<pkg>", "domain:<domain>" or "browser:<pkg>". */
    private var lastBlockedKey: String? = null
    private var browsers: Set<String> = emptySet()
    private var browsersRefreshedAt = 0L
    private var lastContentCheckAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        refreshBrowsers()
        observeBlockingState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        // Ignore system UI
        if (packageName == "com.android.systemui") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(packageName)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Navigating within a browser changes content, not the window.
                if (packageName != _foregroundApp.value) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastContentCheckAt < CONTENT_CHECK_INTERVAL_MS) return
                lastContentCheckAt = now
                checkWebsite(packageName)
            }
        }
    }

    private fun onWindowChanged(packageName: String) {
        // Emit foreground app for companion app detection (before filtering our own package)
        Log.d(TAG, "Foreground app changed: $packageName")
        _foregroundApp.value = packageName

        // Ignore our own app for blocking purposes
        if (packageName == this.packageName) {
            blockingActivityShown = true
            return
        }

        if (packageName in blockingState.blockedApps) {
            Log.d(TAG, "Blocked app detected: $packageName")
            launchBlockingOverlay("app:$packageName") {
                putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, packageName)
            }
            return
        }
        if (!checkWebsite(packageName)) {
            // User navigated to a non-blocked app; clear tracking
            blockingActivityShown = false
            lastBlockedKey = null
        }
    }

    /**
     * Shows the overlay if [packageName] is a browser on a blocked site, or a
     * browser that can't be checked while sites are blocked. Returns whether
     * it blocked.
     */
    private fun checkWebsite(packageName: String): Boolean {
        if (blockingState.blockedDomains.isEmpty()) return false
        if (SystemClock.elapsedRealtime() - browsersRefreshedAt > BROWSER_REFRESH_INTERVAL_MS) refreshBrowsers()

        if (WebsiteBlocker.isSupportedBrowser(packageName)) {
            val host = WebsiteBlocker.currentHost(rootInActiveWindow, packageName) ?: return false
            val domain = blockingState.blockedDomainFor(host)
            if (domain == null) {
                // A different, allowed site: re-arm so the blocked one triggers again.
                if (lastBlockedKey?.startsWith("domain:") == true) {
                    blockingActivityShown = false
                    lastBlockedKey = null
                }
                return false
            }
            Log.d(TAG, "Blocked website detected: $host in $packageName")
            launchBlockingOverlay("domain:$domain") {
                putExtra(BlockingActivity.EXTRA_BLOCKED_DOMAIN, domain)
                putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, packageName)
            }
            return true
        }
        if (packageName in browsers) {
            Log.d(TAG, "Unsupported browser while websites are blocked: $packageName")
            launchBlockingOverlay("browser:$packageName") {
                putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, packageName)
                putExtra(BlockingActivity.EXTRA_UNSUPPORTED_BROWSER, true)
            }
            return true
        }
        return false
    }

    private fun refreshBrowsers() {
        browsers = try {
            WebsiteBlocker.installedBrowsers(this) - packageName
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list browsers", e)
            browsers
        }
        browsersRefreshedAt = SystemClock.elapsedRealtime()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeBlockingState() {
        scope.launch {
            BrainfenceService.blockingState.collect { state ->
                blockingState = state

                // If the overlay is shown for something that's no longer blocked, reset tracking
                val key = lastBlockedKey ?: return@collect
                val stillBlocked = when {
                    key.startsWith("app:") -> key.removePrefix("app:") in state.blockedApps
                    key.startsWith("domain:") -> key.removePrefix("domain:") in state.blockedDomains
                    else -> state.blockedDomains.isNotEmpty()
                }
                if (!stillBlocked) {
                    blockingActivityShown = false
                    lastBlockedKey = null
                }
            }
        }
    }

    private fun launchBlockingOverlay(key: String, extras: Intent.() -> Unit) {
        if (blockingActivityShown && lastBlockedKey == key) return
        lastBlockedKey = key
        blockingActivityShown = true

        val intent = Intent(this, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            extras()
        }
        startActivity(intent)
    }
}
