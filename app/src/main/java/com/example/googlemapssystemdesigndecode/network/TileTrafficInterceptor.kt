package com.example.googlemapssystemdesigndecode.network

import okhttp3.Interceptor
import okhttp3.Response

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

        val byteCount = peekedBytes?.size
            ?: response.body?.contentLength()?.takeIf { it >= 0 }?.toInt()
            ?: 0

        TrafficMonitor.onNetworkResponse(
            url = url,
            byteCount = byteCount,
            isVectorTile = isVectorTile,
            rawTileBytes = if (isVectorTile) peekedBytes else null,
        )

        return response
    }

    private fun isVectorTileResponse(url: String, response: Response): Boolean {
        val path = url.substringBefore('?')
        val contentType = response.header("Content-Type").orEmpty()
        return path.endsWith(".pbf", ignoreCase = true) ||
            path.endsWith(".mvt", ignoreCase = true) ||
            contentType.contains("vector-tile", ignoreCase = true) ||
            contentType.contains("x-protobuf", ignoreCase = true)
    }

    companion object {
        // MVT tiles are almost always well under 500KB; 2MB is a generous safety cap
        // for peekBody so we never buffer something unbounded into memory.
        private const val MAX_PEEK_BYTES = 2_000_000L

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
