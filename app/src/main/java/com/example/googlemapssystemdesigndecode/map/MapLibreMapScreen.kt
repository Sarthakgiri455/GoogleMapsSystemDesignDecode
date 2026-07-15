package com.example.googlemapssystemdesigndecode.map

import android.graphics.Color
import android.graphics.RectF
import android.util.Log
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
import org.maplibre.geojson.LineString
import kotlin.math.hypot

/** Free, no-API-key, OpenStreetMap-derived vector tiles -- see https://openfreemap.org */
private const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE_ID = "congestion-route-source"
private const val ROUTE_LAYER_ID = "congestion-route-layer"
private const val CAMERA_ZOOM = 12.5

// How many of the real, queried street segments to recolor -- capped so the overlay highlights
// a readable handful of streets rather than every alley in the viewport.
private const val MAX_REAL_SEGMENTS = 10

/**
 * Hosts a single MapLibre [MapView] inside Compose, wired to:
 *  - [TileTrafficInterceptor] for the network-spike HUD (installed before the first style loads),
 *  - a handful of *real* street segments, queried off the rendered basemap once the style loads
 *    (see [queryRealRoadSegments]) and recolored every tick from [TelemetrySimulator] without
 *    ever re-fetching geometry,
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
        MapLibre.getInstance(context)
        TileTrafficInterceptor.install() // must run before MapLibre opens its first HTTP connection
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
    val realSegmentsState = remember { mutableStateOf<List<RouteData.RealSegment>>(emptyList()) }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize()) { view ->
        view.getMapAsync { maplibreMap ->
            if (mapLibreMapState.value != null) return@getMapAsync // already configured once
            mapLibreMapState.value = maplibreMap
            onMapReady(maplibreMap)

            maplibreMap.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE_URL)) { style ->
                addCongestionRouteLayer(style)
                maplibreMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(RouteData.demoAreaCenter.second, RouteData.demoAreaCenter.first))
                    .zoom(CAMERA_ZOOM)
                    .build()
                styleState.value = style // triggers the LaunchedEffect below once the style is ready

                // queryRenderedFeatures only sees what's actually on screen, so real street
                // discovery has to wait for an actual idle render at the new camera position --
                // not a fixed delay, which is either wastefully long or a race on a slow device.
                lateinit var idleListener: MapView.OnDidBecomeIdleListener
                idleListener = MapView.OnDidBecomeIdleListener {
                    if (realSegmentsState.value.isEmpty()) {
                        val segments = queryRealRoadSegments(maplibreMap, view.width, view.height)
                        if (segments.isNotEmpty()) {
                            realSegmentsState.value = segments
                            TelemetrySimulator.seedSegments(segments.map { it.key })
                        }
                    }
                    view.removeOnDidBecomeIdleListener(idleListener)
                }
                view.addOnDidBecomeIdleListener(idleListener)
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

    // Live telemetry -> real-street recoloring. No network call happens here: only the
    // `congestion` property on the already-loaded GeoJSON source is rewritten.
    val congestionBySegment by TelemetrySimulator.congestionBySegment.collectAsState()
    LaunchedEffect(congestionBySegment, realSegmentsState.value) {
        val style = mapLibreMapState.value?.style ?: return@LaunchedEffect
        val segments = realSegmentsState.value
        if (segments.isEmpty()) return@LaunchedEffect
        (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(RouteData.segmentFeatureCollectionJson(segments, congestionBySegment))
    }
}

// OpenMapTiles "transportation" layer's `class` values that are actual roads -- excludes
// waterways (river/canal, also LineStrings in this layer) and rail, which real traffic
// congestion doesn't apply to.
private val ROAD_CLASSES = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor")

/**
 * Finds real street geometry to recolor by querying whatever's *actually rendered* in the
 * current viewport -- these are the same [org.maplibre.geojson.Feature] objects, with the same
 * coordinates, that OpenFreeMap's vector tiles describe. Longest segments first, capped at
 * [MAX_REAL_SEGMENTS], so the overlay highlights a few readable streets rather than every alley
 * fragment in view.
 */
private fun queryRealRoadSegments(maplibreMap: MapLibreMap, viewWidth: Int, viewHeight: Int): List<RouteData.RealSegment> {
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val segments = maplibreMap.queryRenderedFeatures(viewRect)
        .mapNotNull { feature -> (feature.geometry() as? LineString)?.let { line -> line to feature.getStringProperty("class") } }
        .filter { (_, roadClass) -> roadClass in ROAD_CLASSES }
        .map { (line, roadClass) -> line.coordinates().map { it.longitude() to it.latitude() } to roadClass }
        .distinctBy { it.first } // tile buffering can return the same feature from two adjacent tiles
        .sortedByDescending { (coordinates, _) -> pathLength(coordinates) }
        .take(MAX_REAL_SEGMENTS)
        .mapIndexed { index, (coordinates, roadClass) -> RouteData.RealSegment("seg-$index", coordinates, roadClass) }
    Log.d("RealRoadQuery", "found ${segments.size} real road segments: ${segments.map { it.roadClass }}")
    return segments
}

private fun pathLength(coordinates: List<Pair<Double, Double>>): Double {
    var total = 0.0
    for (i in 0 until coordinates.size - 1) {
        val (x1, y1) = coordinates[i]
        val (x2, y2) = coordinates[i + 1]
        total += hypot(x2 - x1, y2 - y1)
    }
    return total
}

private fun addCongestionRouteLayer(style: Style) {
    val source = GeoJsonSource(
        ROUTE_SOURCE_ID,
        // Starts empty; populated once queryRealRoadSegments() finds real streets to recolor.
        RouteData.segmentFeatureCollectionJson(emptyList(), emptyMap()),
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
    runCatching {
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
}
