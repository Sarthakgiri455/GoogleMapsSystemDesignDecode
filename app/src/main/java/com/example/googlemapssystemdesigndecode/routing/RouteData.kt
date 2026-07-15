package com.example.googlemapssystemdesigndecode.routing

import org.json.JSONArray
import org.json.JSONObject

/**
 * Central Kyoto, near the station -- chosen only because it has a dense enough real street
 * grid for [com.example.googlemapssystemdesigndecode.map.MapLibreMapScreen] to query several
 * real road segments off the rendered basemap and recolor those, instead of drawing a
 * synthetic line that floats over the map unrelated to the streets underneath it.
 */
object RouteData {

    val demoAreaCenter: Pair<Double, Double> = 135.7681 to 34.9945 // (lon, lat)

    /** One real street segment, queried live off the rendered "transportation" layer -- its
     *  coordinates are exactly what OpenFreeMap's vector tiles say that street is, not a
     *  hand-picked point. */
    data class RealSegment(
        val key: String,
        val coordinates: List<Pair<Double, Double>>, // (lon, lat)
        val roadClass: String?,
    )

    /**
     * Renders the given real segments as a GeoJSON FeatureCollection, one LineString Feature
     * per segment, each tagged with `segmentKey` and the caller-supplied `congestion`
     * (0.0 = clear, 1.0 = jammed). Built with org.json (already on the Android platform,
     * no extra dependency) rather than a geojson model library, so this stays simple to
     * read for the article.
     */
    fun segmentFeatureCollectionJson(segments: List<RealSegment>, congestionBySegment: Map<String, Double>): String {
        val features = JSONArray()
        for (segment in segments) {
            val coordinates = JSONArray()
            segment.coordinates.forEach { (lon, lat) -> coordinates.put(JSONArray().put(lon).put(lat)) }

            val geometry = JSONObject()
                .put("type", "LineString")
                .put("coordinates", coordinates)

            val properties = JSONObject()
                .put("segmentKey", segment.key)
                .put("roadClass", segment.roadClass)
                .put("congestion", congestionBySegment[segment.key] ?: 0.0)

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
