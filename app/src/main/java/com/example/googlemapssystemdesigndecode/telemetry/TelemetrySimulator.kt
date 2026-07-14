package com.example.googlemapssystemdesigndecode.telemetry

import com.example.googlemapssystemdesigndecode.routing.RouteData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Stand-in for a live-traffic push channel. In production this is a server -> client
 * WebSocket (or gRPC stream) delivering per-segment congestion deltas, aggregated from
 * other vehicles' probe/GPS telemetry. The important architectural point this demo
 * makes: the client never re-requests routing *geometry* for a traffic update, it only
 * ever re-styles the segment it already has using a fresh `congestion` value. Swap this
 * object's [ensureStarted] body for a real `okhttp3.WebSocketListener` and nothing else
 * in the app (RouteData, the map layer, the HUD) has to change.
 */
object TelemetrySimulator {

    private const val TICK_MS = 2000L
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _congestionBySegment = MutableStateFlow(
        RouteData.segmentIds.associateWith { 0.15 }, // start mostly clear
    )
    val congestionBySegment: StateFlow<Map<Int, Double>> = _congestionBySegment

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                _congestionBySegment.update { current ->
                    current.mapValues { (_, prev) ->
                        // Smooth random walk so segments drift between clear/moderate/jammed
                        // rather than flicker -- closer to how real congestion evolves.
                        (prev + Random.nextDouble(-0.25, 0.25)).coerceIn(0.0, 1.0)
                    }
                }
            }
        }
    }
}
