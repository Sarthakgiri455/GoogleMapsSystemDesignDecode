package com.example.googlemapssystemdesigndecode.map

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.googlemapssystemdesigndecode.network.TileTrafficInterceptor
import com.example.googlemapssystemdesigndecode.routing.RouteData
import com.example.googlemapssystemdesigndecode.telemetry.TelemetrySimulator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** Free, no-API-key, OpenStreetMap-derived vector tiles -- see https://openfreemap.org */
private const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE_ID = "congestion-route-source"
private const val ROUTE_LAYER_ID = "congestion-route-layer"
private const val CAMERA_ZOOM = 12.5

/**
 * Hosts a single MapLibre [MapView] inside Compose, wired to:
 *  - [TileTrafficInterceptor] for the network-spike HUD (installed before the first style loads),
 *  - a GeoJSON "routing tile" ([RouteData]) whose segments are recolored every tick from
 *    [TelemetrySimulator] without ever re-fetching geometry,
 *  - the MapLibre LocationComponent, if the caller has already secured location permission.
 */
@Composable
fun MapLibreMapScreen(
    modifier: Modifier = Modifier,
    hasLocationPermission: Boolean,
    onMapReady: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        TileTrafficInterceptor.install() // must run before MapLibre opens its first HTTP connection
        MapLibre.getInstance(context)
        MapView(context)
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        mapView.onCreate(null)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mapLibreMapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleState = remember { mutableStateOf<Style?>(null) }
    val locationActivated = remember { mutableStateOf(false) }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize()) { view ->
        view.getMapAsync { maplibreMap ->
            if (mapLibreMapState.value != null) return@getMapAsync // already configured once
            mapLibreMapState.value = maplibreMap
            onMapReady(maplibreMap)

            maplibreMap.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE_URL)) { style ->
                addCongestionRouteLayer(style)
                maplibreMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(RouteData.initialCameraTarget.second, RouteData.initialCameraTarget.first))
                    .zoom(CAMERA_ZOOM)
                    .build()
                styleState.value = style // triggers the LaunchedEffect below once the style is ready
            }
        }
    }

    // Location permission is usually granted (or denied) *after* the style has already
    // finished loading -- the permission dialog takes real user time, the style fetch
    // doesn't. Re-checking both whenever either changes avoids losing the location
    // component just because of that ordering.
    LaunchedEffect(hasLocationPermission, styleState.value) {
        val style = styleState.value ?: return@LaunchedEffect
        val maplibreMap = mapLibreMapState.value ?: return@LaunchedEffect
        if (hasLocationPermission && !locationActivated.value) {
            enableLocationComponent(context, maplibreMap, style)
            locationActivated.value = true
        }
    }

    // Live telemetry -> route recoloring. No network call happens here: only the
    // `congestion` property on the already-loaded GeoJSON source is rewritten.
    val congestionBySegment by TelemetrySimulator.congestionBySegment.collectAsState()
    LaunchedEffect(congestionBySegment) {
        val style = mapLibreMapState.value?.style ?: return@LaunchedEffect
        (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(RouteData.segmentFeatureCollectionJson(congestionBySegment))
    }
}

private fun addCongestionRouteLayer(style: Style) {
    val source = GeoJsonSource(
        ROUTE_SOURCE_ID,
        RouteData.segmentFeatureCollectionJson(emptyMap()), // starts clear; first telemetry tick recolors it
    )
    style.addSource(source)

    val congestionColorExpression = Expression.interpolate(
        Expression.linear(),
        Expression.get("congestion"),
        Expression.stop(0.0f, Expression.color(Color.parseColor("#2ecc71"))), // clear: green
        Expression.stop(0.5f, Expression.color(Color.parseColor("#f1c40f"))), // moderate: amber
        Expression.stop(1.0f, Expression.color(Color.parseColor("#e74c3c"))), // jammed: red
    )

    val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
        PropertyFactory.lineColor(congestionColorExpression),
        PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    style.addLayer(layer)
}

private fun enableLocationComponent(context: android.content.Context, maplibreMap: MapLibreMap, style: Style) {
    val locationComponent = maplibreMap.locationComponent
    val options = LocationComponentOptions.builder(context)
        .pulseEnabled(true)
        .build()
    val activationOptions = LocationComponentActivationOptions
        .builder(context, style)
        .locationComponentOptions(options)
        .useDefaultLocationEngine(true)
        .build()
    locationComponent.activateLocationComponent(activationOptions)
    locationComponent.isLocationComponentEnabled = true
}
