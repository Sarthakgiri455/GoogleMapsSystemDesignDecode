package com.example.googlemapssystemdesigndecode.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.zip.GZIPInputStream

/**
 * Registered as an OkHttp *network* interceptor (see [install]) so it observes
 * responses before OkHttp's BridgeInterceptor transparently gzip-decompresses
 * them -- i.e. the byte counts here match actual wire bytes, the same number
 * Android Studio's Network Inspector would report.
 *
 * Every response is peeked (not consumed) so MapLibre's native tile loader still
 * gets an intact body to parse.
 */
class TileTrafficInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()
        val isVectorTile = isVectorTileResponse(url, response)

        val peekedBytes: ByteArray? = runCatching {
            response.peekBody(MAX_PEEK_BYTES).bytes()
        }.getOrNull()

        // Wire-byte count intentionally reflects what's actually on the wire (pre-decompression) --
        // see the class doc. The MVT decoder, on the other hand, needs the decompressed protobuf
        // bytes, since the vector-tile-spec schema knows nothing about gzip framing.
        val byteCount = peekedBytes?.size
            ?: response.body?.contentLength()?.takeIf { it >= 0 }?.toInt()
            ?: 0

        val decodableTileBytes = if (isVectorTile && peekedBytes != null) {
            if (response.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) {
                runCatching { gunzip(peekedBytes) }.getOrNull()
            } else {
                peekedBytes
            }
        } else {
            null
        }

        TrafficMonitor.onNetworkResponse(
            url = url,
            byteCount = byteCount,
            isVectorTile = isVectorTile,
            rawTileBytes = decodableTileBytes,
        )

        return response
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /**
     * True MVT tiles only -- matches the standard slippy-map `.../{z}/{x}/{y}.pbf` path shape.
     * Excludes other `.pbf`-suffixed resources the same style also fetches (e.g. font glyph
     * ranges at `/fonts/{fontstack}/{start}-{end}.pbf`), which use a different protobuf schema
     * entirely and would make [com.example.googlemapssystemdesigndecode.protobuf.MvtWireDecoder]
     * fail on garbage field tags if fed through it.
     */
    private fun isVectorTileResponse(url: String, response: Response): Boolean {
        val path = url.substringBefore('?')
        val contentType = response.header("Content-Type").orEmpty()
        return TILE_PATH_PATTERN.containsMatchIn(path) ||
            contentType.contains("vector-tile", ignoreCase = true)
    }

    companion object {
        // MVT tiles are almost always well under 500KB; 2MB is a generous safety cap
        // for peekBody so we never buffer something unbounded into memory.
        private const val MAX_PEEK_BYTES = 2_000_000L

        private val TILE_PATH_PATTERN = Regex("""/\d+/\d+/\d+\.pbf$""")

        /** Call once, before the MapLibre style/map is loaded (see MapLibreMapContainer). */
        fun install() {
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
                okhttp3.OkHttpClient.Builder()
                    .addNetworkInterceptor(TileTrafficInterceptor())
                    .build()
            )
        }
    }
}
