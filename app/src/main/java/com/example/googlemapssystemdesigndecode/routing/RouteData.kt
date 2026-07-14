package com.example.googlemapssystemdesigndecode.routing

import org.json.JSONArray
import org.json.JSONObject

/**
 * A small hardcoded "routing tile" -- Kyoto Station up to the Kyoto University Yoshida
 * campus -- standing in for a real routing-tile response. The point of this file is
 * NOT the route itself; it's that this geometry is fetched *once* and never again.
 * Everything that follows (recoloring by live congestion) only ever rewrites the
 * lightweight `congestion` property on the existing GeoJSON source.
 */
object RouteData {

    /** (longitude, latitude) waypoints. */
    private val routePoints: List<Pair<Double, Double>> = listOf(
        135.7681 to 34.9858, // Kyoto Station
        135.7702 to 34.9945,
        135.7721 to 35.0035,
        135.7738 to 35.0102,
        135.7756 to 35.0175,
        135.7794 to 35.0248,
        135.7822 to 35.0290, // Kyoto University, Yoshida campus
    )

    val segmentIds: List<Int> = (0 until routePoints.size - 1).toList()

    val initialCameraTarget = routePoints.first()

    /**
     * Renders the route as a GeoJSON FeatureCollection, one LineString Feature per
     * segment, each tagged with `segmentId` and the caller-supplied `congestion`
     * (0.0 = clear, 1.0 = jammed). Built with org.json (already on the Android
     * platform, no extra dependency) rather than a geojson model library, so this
     * stays simple to read for the article.
     */
    fun segmentFeatureCollectionJson(congestionBySegment: Map<Int, Double>): String {
        val features = JSONArray()
        for (i in segmentIds) {
            val (lon1, lat1) = routePoints[i]
            val (lon2, lat2) = routePoints[i + 1]
            val congestion = congestionBySegment[i] ?: 0.0

            val geometry = JSONObject()
                .put("type", "LineString")
                .put(
                    "coordinates",
                    JSONArray()
                        .put(JSONArray().put(lon1).put(lat1))
                        .put(JSONArray().put(lon2).put(lat2)),
                )

            val properties = JSONObject()
                .put("segmentId", i)
                .put("congestion", congestion)

            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", properties)
                    .put("geometry", geometry),
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }
}
