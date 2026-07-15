package com.example.googlemapssystemdesigndecode.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.googlemapssystemdesigndecode.network.TrafficMonitor
import com.example.googlemapssystemdesigndecode.protobuf.MvtWireDecoder
import com.example.googlemapssystemdesigndecode.routing.RouteData
import com.example.googlemapssystemdesigndecode.telemetry.TelemetrySimulator
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

private const val RESET_ZOOM = 12.5

/** Full HUD: network spike graph, live protobuf inspector, telemetry legend, camera reset. */
@Composable
fun MapDemoHud(maplibreMap: MapLibreMap?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NetworkSpikeCard()
        CameraDemoControls(maplibreMap)
        ProtobufInspectorCard()
        TelemetryLegendCard()
    }
}

@Composable
private fun NetworkSpikeCard(modifier: Modifier = Modifier) {
    val history by TrafficMonitor.throughputHistory.collectAsState()
    val stats by TrafficMonitor.stats.collectAsState()
    val spiking by TrafficMonitor.isSpiking.collectAsState()
    val estimatedRasterBytes = remember(stats.tilesFetched) { TrafficMonitor.estimatedRasterEquivalentBytes() }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Network throughput (wire bytes)", style = MaterialTheme.typography.titleSmall)
                SpikeBadge(spiking)
            }
            Sparkline(
                history = history,
                spiking = spiking,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 6.dp),
            )
            Text(
                "${stats.tilesFetched} vector tiles measured · ${formatBytes(stats.vectorBytesTotal)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "raster-PNG equivalent (estimated): ${formatBytes(estimatedRasterBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Rough estimate (flat 90KB/tile average) -- vector tiles can exceed this at " +
                    "low zoom in dense areas. The real savings: no re-fetch on restyle, " +
                    "rotate, or retina density.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpikeBadge(spiking: Boolean) {
    val color = if (spiking) Color(0xFFE74C3C) else Color(0xFF2ECC71)
    val label = if (spiking) "SPIKE" else "steady"
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Sparkline(history: List<Long>, spiking: Boolean, modifier: Modifier = Modifier) {
    val barColor = if (spiking) Color(0xFFE74C3C) else MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (history.isEmpty()) return@Canvas
        val maxVal = (history.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val slotWidth = size.width / history.size
        history.forEachIndexed { index, bytes ->
            val barHeight = (bytes / maxVal) * size.height
            drawRect(
                color = barColor,
                topLeft = Offset(index * slotWidth, size.height - barHeight),
                size = Size(slotWidth * 0.7f, barHeight),
            )
        }
    }
}

@Composable
private fun CameraDemoControls(maplibreMap: MapLibreMap?, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            DemoButton("Reset view", Modifier.fillMaxWidth()) { maplibreMap?.let(::resetToCampus) }
        }
    }
}

@Composable
private fun DemoButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

private fun resetToCampus(map: MapLibreMap) {
    map.animateCamera(
        CameraUpdateFactory.newLatLngZoom(
            LatLng(RouteData.demoAreaCenter.second, RouteData.demoAreaCenter.first),
            RESET_ZOOM,
        ),
        800,
    )
}

@Composable
private fun ProtobufInspectorCard(modifier: Modifier = Modifier) {
    val tile by TrafficMonitor.lastDecodedTile.collectAsState()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last tile decoded off the wire", style = MaterialTheme.typography.titleSmall)
            if (tile == null) {
                Text(
                    "Pan or zoom to trigger a tile fetch…",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                TileSummary(tile!!)
            }
        }
    }
}

@Composable
private fun TileSummary(tile: MvtWireDecoder.DecodedTile) {
    Text(
        "${tile.byteSize} bytes → ${tile.layers.size} layers",
        style = MaterialTheme.typography.bodySmall,
    )
    tile.layers.take(5).forEach { layer ->
        Text(
            "• ${layer.name}: ${layer.features.size} features, extent=${layer.extent}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val sample = tile.layers.firstOrNull { it.features.isNotEmpty() }?.features?.firstOrNull()
    if (sample != null) {
        Text(
            "sample feature: id=${sample.id} type=${sample.geomType} attrs=${sample.attributes}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TelemetryLegendCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Live traffic", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(Color(0xFF2ECC71), "clear")
                LegendDot(Color(0xFFF1C40F), "moderate")
                LegendDot(Color(0xFFE74C3C), "jammed")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DemoButton("Traffic jam", Modifier.weight(1f)) { TelemetrySimulator.forceState(0.95) }
                DemoButton("Clear jam", Modifier.weight(1f)) { TelemetrySimulator.forceState(0.05) }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.padding(top = 4.dp).size(10.dp),
        ) {}
        Text(" $label", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
}
