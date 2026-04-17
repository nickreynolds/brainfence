package dev.brainfence.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Checks whether [BrainfenceAccessibilityService] is currently enabled.
 *
 * Uses [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] instead of
 * [android.view.accessibility.AccessibilityManager.getEnabledAccessibilityServiceList]
 * because the latter only reports services that are already bound/connected,
 * which may not be the case immediately after the user toggles the setting.
 */
object AccessibilityServiceChecker {

    fun isEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val ourComponent = ComponentName(context, BrainfenceAccessibilityService::class.java)
        return enabledServices.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == ourComponent
        }
    }
}
