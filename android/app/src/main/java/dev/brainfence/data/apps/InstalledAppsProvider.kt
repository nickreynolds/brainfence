package dev.brainfence.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * Loads the user's installed apps (excluding system services and Brainfence itself),
 * sorted by label. Used by editors that let the user pick app packages — currently
 * the blocking rule editor (apps to block) and the task editor (companion apps for
 * meditation tasks).
 */
@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                it.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                InstalledApp(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = loadIcon(pm, appInfo),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadIcon(pm: PackageManager, appInfo: ApplicationInfo): ImageBitmap? = try {
        val drawable: Drawable = pm.getApplicationIcon(appInfo)
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
