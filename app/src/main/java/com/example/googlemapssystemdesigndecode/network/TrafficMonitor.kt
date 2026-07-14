package com.example.googlemapssystemdesigndecode.network

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
                _isSpiking.value = (lastTwoBuckets * (1000L / BUCKET_MS) / 2) > SPIKE_THRESHOLD_BYTES_PER_SEC
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
            }
        }
    }

    /** Rough estimate of what the same viewport would have cost as raster PNG tiles.
     * We deliberately do NOT fetch a real raster tile server here: embedding tile.openstreetmap.org
     * in a public demo repo that others will clone and run violates its tile usage policy
     * (see https://operations.osmfoundation.org/policies/tiles/). Instead we multiply the
     * measured vector tile count by a cited average raster tile size -- see README for the source.
     */
    fun estimatedRasterEquivalentBytes(avgRasterTileBytes: Int = 90_000): Long =
        _stats.value.tilesFetched.toLong() * avgRasterTileBytes
}
