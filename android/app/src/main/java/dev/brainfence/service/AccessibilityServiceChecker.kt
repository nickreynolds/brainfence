package dev.brainfence.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Checks whether [BrainfenceAccessibilityService] is currently enabled.
 */
object AccessibilityServiceChecker {

    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )
        val ourComponent = "${context.packageName}/${BrainfenceAccessibilityService::class.java.name}"
        return enabled.any { it.id == ourComponent }
    }
}
