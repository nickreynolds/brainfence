package dev.brainfence.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.ln

/**
 * An osmdroid map for picking a location: tap to drop the pin, with the
 * geofence radius drawn around it. The FAB jumps to (and selects) the
 * device's current location.
 *
 * When no location is selected yet, the map centers on the device's last
 * known position (if location permission is granted), else a world view.
 */
@SuppressLint("ClickableViewAccessibility", "MissingPermission")
@Composable
fun LocationPickerMap(
    latitude: Double?,
    longitude: Double?,
    radiusMeters: Int,
    onLocationSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val fillArgb = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f).toArgb()
    val currentOnSelect by rememberUpdatedState(onLocationSelected)

    // Ensure osmdroid has a user-agent (required)
    remember {
        Configuration.getInstance().userAgentValue = context.packageName
        true
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var centeredOnce by remember { mutableStateOf(latitude != null && longitude != null) }

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    // With no selection yet, start the map at the device's location.
    LaunchedEffect(Unit) {
        if (centeredOnce || !hasLocationPermission()) return@LaunchedEffect
        LocationServices.getFusedLocationProviderClient(context).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null && !centeredOnce) {
                    centeredOnce = true
                    mapViewRef?.controller?.setZoom(15.0)
                    mapViewRef?.controller?.setCenter(GeoPoint(loc.latitude, loc.longitude))
                }
            }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    // Map gestures must not scroll the surrounding list
                    setOnTouchListener { v, _ ->
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    if (latitude != null && longitude != null) {
                        controller.setZoom(zoomForRadius(radiusMeters.toFloat()).toDouble())
                        controller.setCenter(GeoPoint(latitude, longitude))
                    } else {
                        controller.setZoom(3.0)
                    }
                    overlays.add(
                        MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                currentOnSelect(p.latitude, p.longitude)
                                return true
                            }

                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        })
                    )
                    mapViewRef = this
                }
            },
            update = { mapView ->
                // Redraw the selection; the MapEventsOverlay is kept as-is
                mapView.overlays.removeAll { it is Marker || it is Polygon }
                if (latitude != null && longitude != null) {
                    val center = GeoPoint(latitude, longitude)
                    val circle = Polygon(mapView).apply {
                        points = Polygon.pointsAsCircle(center, radiusMeters.toDouble())
                        fillPaint.color = fillArgb
                        outlinePaint.color = primaryArgb
                        outlinePaint.strokeWidth = 4f
                    }
                    mapView.overlays.add(circle)
                    val marker = Marker(mapView).apply {
                        position = center
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(marker)
                }
                mapView.invalidate()
            },
        )

        SmallFloatingActionButton(
            onClick = {
                if (!hasLocationPermission()) return@SmallFloatingActionButton
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            centeredOnce = true
                            currentOnSelect(loc.latitude, loc.longitude)
                            mapViewRef?.controller?.let { controller ->
                                if ((mapViewRef?.zoomLevelDouble ?: 0.0) < 14.0) {
                                    controller.setZoom(15.0)
                                }
                                controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                            }
                        }
                    }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Use current location")
        }
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
