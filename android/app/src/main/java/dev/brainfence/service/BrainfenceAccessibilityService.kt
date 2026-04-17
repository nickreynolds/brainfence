package dev.brainfence.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.brainfence.ui.blocking.BlockingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Monitors foreground app changes and launches the blocking overlay
 * when the user opens an app that is currently blocked.
 */
class BrainfenceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BrainfenceA11y"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentBlockedApps: Set<String> = emptySet()
    private var blockingActivityShown = false
    private var lastBlockedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        observeBlockingState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app and system UI
        if (packageName == this.packageName) return
        if (packageName == "com.android.systemui") return

        // If this is our blocking activity coming to front, track it
        if (packageName == this.packageName) {
            blockingActivityShown = true
            return
        }

        if (packageName in currentBlockedApps) {
            Log.d(TAG, "Blocked app detected: $packageName")
            if (!blockingActivityShown || lastBlockedPackage != packageName) {
                launchBlockingOverlay(packageName)
            }
        } else {
            // User navigated to a non-blocked app; clear tracking
            blockingActivityShown = false
            lastBlockedPackage = null
        }
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
                currentBlockedApps = state.blockedApps

                // If the blocking overlay is shown but the app is no longer blocked, dismiss it
                if (lastBlockedPackage != null && lastBlockedPackage !in state.blockedApps) {
                    blockingActivityShown = false
                    lastBlockedPackage = null
                }
            }
        }
    }

    private fun launchBlockingOverlay(packageName: String) {
        lastBlockedPackage = packageName
        blockingActivityShown = true

        val intent = Intent(this, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, packageName)
        }
        startActivity(intent)
    }
}
