package com.example.googlemapssystemdesigndecode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.googlemapssystemdesigndecode.map.MapLibreMapScreen
import com.example.googlemapssystemdesigndecode.network.TrafficMonitor
import com.example.googlemapssystemdesigndecode.telemetry.TelemetrySimulator
import com.example.googlemapssystemdesigndecode.ui.MapDemoHud
import com.example.googlemapssystemdesigndecode.ui.theme.GoogleMapsSystemDesignDecodeTheme
import org.maplibre.android.maps.MapLibreMap

/**
 * Interactive companion app for the "Google Maps system design" article: renders an
 * open-source vector tile map (MapLibre + OpenFreeMap, protobuf/MVT under the hood),
 * live-measures network bytes on every pan/zoom, decodes the actual tiles fetched, and
 * simulates a live-traffic push recoloring a route -- all without touching Google's
 * proprietary client or backend. See README.md for the concept-to-code mapping.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        TrafficMonitor.ensureStarted()
        TelemetrySimulator.ensureStarted()

        setContent {
            GoogleMapsSystemDesignDecodeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    DemoScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var maplibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    Column(modifier = modifier) {
        MapLibreMapScreen(
            modifier = Modifier.fillMaxWidth().weight(MAP_WEIGHT),
            hasLocationPermission = hasLocationPermission,
            onMapReady = { maplibreMap = it },
        )
        Box(
            modifier = Modifier
                .weight(HUD_WEIGHT)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            MapDemoHud(maplibreMap = maplibreMap)
        }
    }
}

// Map gets the majority of vertical space; the HUD below it scrolls for whatever doesn't fit.
private const val MAP_WEIGHT = 3f
private const val HUD_WEIGHT = 2f
