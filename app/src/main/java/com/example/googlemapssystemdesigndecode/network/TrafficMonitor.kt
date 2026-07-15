package com.example.googlemapssystemdesigndecode.network

import android.util.Log
import com.example.googlemapssystemdesigndecode.protobuf.MvtWireDecoder
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Live view of every byte MapLibre pulls off the network, sampled off a
 * [TileTrafficInterceptor] registered as an OkHttp *network* interceptor (so byte
 * counts reflect the compressed wire size, matching what Android Studio's Network
 * Inspector would show -- not the decompressed payload).
 *
 * This is the data source for the HUD: a throughput sparkline (bucketed every
 * [BUCKET_MS]) that visibly spikes on a big viewport jump and stays flat on a
 * small pan, plus the most recently decoded MVT tile for the protobuf inspector.
 */
object TrafficMonitor {

    private const val BUCKET_MS = 250L
    private const val HISTORY_SIZE = 60 // 60 * 250ms = 15s rolling window
    private const val SPIKE_THRESHOLD_BYTES_PER_SEC = 60_000L // tune to taste

    // Real tile bursts finish in well under a second, so the raw detector below (based on
    // only the last 500ms of traffic) flips back to "steady" almost immediately -- too fast
    // to see, let alone screenshot. This holds the visible SPIKE state for a few seconds after
    // the last moment the threshold was actually exceeded, like peak-hold on a VU meter --
    // detection stays instantaneous, only the display lingers.
    private const val SPIKE_HOLD_MS = 3_000L
    private var lastSpikeAtMs = 0L

    data class SessionStats(
        val tilesFetched: Int = 0,
        val vectorBytesTotal: Long = 0L,
        val otherBytesTotal: Long = 0L, // style.json, sprites, glyphs, fonts
        val lastTileUrl: String? = null,
        val lastTileBytes: Int = 0,
    )

    private val pendingBytesThisBucket = AtomicLong(0L)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _throughputHistory = MutableStateFlow(List(HISTORY_SIZE) { 0L })
    val throughputHistory: StateFlow<List<Long>> = _throughputHistory

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats

    private val _lastDecodedTile = MutableStateFlow<MvtWireDecoder.DecodedTile?>(null)
    val lastDecodedTile: StateFlow<MvtWireDecoder.DecodedTile?> = _lastDecodedTile

    /** Bytes/sec spike detector: true when the last ~0.5s moved more data than [SPIKE_THRESHOLD_BYTES_PER_SEC]. */
    val isSpiking: StateFlow<Boolean> get() = _isSpiking
    private val _isSpiking = MutableStateFlow(false)

    /** Starts the bucketing ticker exactly once; safe to call from every Composable/Activity onCreate. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                delay(BUCKET_MS)
                val bytesThisBucket = pendingBytesThisBucket.getAndSet(0L)
                _throughputHistory.update { history -> (history.drop(1) + bytesThisBucket) }
                val lastTwoBuckets = _throughputHistory.value.takeLast(2).sum()
                val ratePerSec = lastTwoBuckets * (1000L / BUCKET_MS) / 2
                val now = System.currentTimeMillis()
                if (ratePerSec > SPIKE_THRESHOLD_BYTES_PER_SEC) lastSpikeAtMs = now
                _isSpiking.value = (now - lastSpikeAtMs) < SPIKE_HOLD_MS
            }
        }
    }

    /** Called from [TileTrafficInterceptor] for every response MapLibre's OkHttpClient sees. */
    fun onNetworkResponse(url: String, byteCount: Int, isVectorTile: Boolean, rawTileBytes: ByteArray?) {
        pendingBytesThisBucket.addAndGet(byteCount.toLong())

        _stats.update { s ->
            if (isVectorTile) {
                s.copy(
                    tilesFetched = s.tilesFetched + 1,
                    vectorBytesTotal = s.vectorBytesTotal + byteCount,
                    lastTileUrl = url,
                    lastTileBytes = byteCount,
                )
            } else {
                s.copy(otherBytesTotal = s.otherBytesTotal + byteCount)
            }
        }

        if (isVectorTile && rawTileBytes != null) {
            scope.launch {
                runCatching { MvtWireDecoder.decode(rawTileBytes) }
                    .onSuccess { _lastDecodedTile.value = it }
                    .onFailure { Log.w("MvtWireDecoder", "decode failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }
}
