package dev.brainfence.ui.tasks

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.brainfence.domain.model.Task
import dev.brainfence.service.GpsConfig
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.ln

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsTaskScreen(
    task: Task,
    gpsConfig: GpsConfig,
    isTracking: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val center = GeoPoint(gpsConfig.lat, gpsConfig.lng)
    val zoom = zoomForRadius(gpsConfig.radiusM)

    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val fillArgb = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f).toArgb()

    // Ensure osmdroid has a user-agent (required)
    remember {
        Configuration.getInstance().userAgentValue = context.packageName
        true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // osmdroid MapView wrapped in AndroidView
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(zoom.toDouble())
                        controller.setCenter(center)

                        // Geofence circle
                        val circle = Polygon(this).apply {
                            points = Polygon.pointsAsCircle(center, gpsConfig.radiusM.toDouble())
                            fillPaint.color = fillArgb
                            outlinePaint.color = primaryArgb
                            outlinePaint.strokeWidth = 4f
                        }
                        overlays.add(circle)

                        // Center marker
                        val marker = Marker(this).apply {
                            position = center
                            title = task.title
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        overlays.add(marker)
                    }
                },
            )

            GeofenceInfoCard(
                task = task,
                gpsConfig = gpsConfig,
                isTracking = isTracking,
            )
        }
    }
}

@Composable
private fun GeofenceInfoCard(
    task: Task,
    gpsConfig: GpsConfig,
    isTracking: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Status row
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint, label) = when {
                    task.completedToday -> Triple(
                        Icons.Default.CheckCircle,
                        MaterialTheme.colorScheme.primary,
                        "Completed today",
                    )
                    isTracking -> Triple(
                        Icons.Default.GpsFixed,
                        MaterialTheme.colorScheme.tertiary,
                        "Tracking location\u2026",
                    )
                    else -> Triple(
                        Icons.Default.LocationOn,
                        MaterialTheme.colorScheme.outline,
                        "Waiting for location permission",
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Mode description
            val modeDescription = when (gpsConfig.mode) {
                "enter" -> "Arrive at this location to complete"
                "leave" -> "Leave this location to complete"
                else -> "GPS verification required"
            }
            InfoRow(label = "Mode", value = modeDescription)

            // Radius
            InfoRow(label = "Radius", value = "${gpsConfig.radiusM.toInt()}m")

            // Min duration (enter mode only)
            if (gpsConfig.mode == "enter" && gpsConfig.minDurationM > 0) {
                InfoRow(label = "Min. stay", value = "${gpsConfig.minDurationM} min")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Calculate an appropriate zoom level so the geofence circle fits nicely in view.
 * At zoom 15, ~500m radius fits well. Adjusts logarithmically.
 */
private fun zoomForRadius(radiusM: Float): Float {
    val zoom = 15.0 - ln((radiusM / 500.0).coerceAtLeast(0.01)) / ln(2.0)
    return zoom.toFloat().coerceIn(10f, 20f)
}
