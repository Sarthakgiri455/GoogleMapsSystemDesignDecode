package com.example.googlemapssystemdesigndecode.telemetry

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
 *
 * Segment keys aren't known at compile time -- [com.example.googlemapssystemdesigndecode.map.MapLibreMapScreen]
 * discovers them at runtime by querying real rendered street geometry, then calls
 * [seedSegments] once they're known.
 */
object TelemetrySimulator {

    private const val TICK_MS = 2000L
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _congestionBySegment = MutableStateFlow<Map<String, Double>>(emptyMap())
    val congestionBySegment: StateFlow<Map<String, Double>> = _congestionBySegment

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

    /** Adds any newly-discovered segment keys at a "mostly clear" default -- idempotent, never
     *  overwrites a key that's already drifting from a previous call. */
    fun seedSegments(keys: List<String>) {
        _congestionBySegment.update { current ->
            val missing = keys.filter { it !in current }
            if (missing.isEmpty()) current else current + missing.associateWith { 0.15 }
        }
    }

    /**
     * On-demand override for live demos: jumps every segment straight to [value] instead of
     * waiting on the random walk to drift there. The ticker above keeps running afterward, so
     * it resumes drifting (from the forced value) on the next tick -- this only front-loads one
     * state change, it doesn't pause or replace the simulation.
     */
    fun forceState(value: Double) {
        _congestionBySegment.update { current -> current.mapValues { value.coerceIn(0.0, 1.0) } }
    }
}
