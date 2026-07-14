# Google Maps System Design — Decoded

An interactive Android demo built to accompany a write-up on how mobile mapping
clients (Google Maps and friends) squeeze bandwidth: vector tiles, Protocol
Buffers on the wire, and live-traffic overlays that recolor a route without
re-fetching its geometry.

Everything here runs against **open-source software and open map data**
(MapLibre + OpenFreeMap/OpenStreetMap). Nothing decompiles, patches, or
intercepts Google's proprietary Maps client — the goal is to demonstrate the
same architectural ideas on infrastructure that's actually free to inspect.

## What it demonstrates, and where

| Concept | Where in the repo |
|---|---|
| Protocol Buffers on the wire | `mvt_demo/` — a standalone Python encoder/decoder that hand-parses the Mapbox Vector Tile (MVT) wire format byte by byte, no protobuf runtime. `app/.../protobuf/MvtWireDecoder.kt` — the same decoder ported to Kotlin, run live inside the app. |
| Vector tiles vs. raster PNGs | `MapLibreMapScreen.kt` renders real MVT vector tiles from OpenFreeMap. `TrafficMonitor.estimatedRasterEquivalentBytes()` computes what the same viewport would have cost as raster PNGs, using a cited average tile size (see Sources below) — it doesn't fetch a real raster server (see *Why no live raster comparison* below). |
| Network spikes on viewport change | `TileTrafficInterceptor.kt` is an OkHttp **network** interceptor registered on MapLibre's HTTP client, so it measures actual wire bytes (pre-decompression) for every tile request. `TrafficMonitor.kt` buckets that into a 15-second rolling throughput history. The HUD's sparkline visibly spikes on a big camera jump (many new tiles) and stays flat on a small pan (only edge tiles are new; the rest are already cached) — try the "Big jump → spike" vs "Tiny pan → no spike" buttons. |
| Routing tiles + live telemetry coloring | `RouteData.kt` is a small hardcoded "routing tile" (Kyoto Station → Kyoto University). `TelemetrySimulator.kt` is a coroutine standing in for a server → client WebSocket, emitting a congestion score per segment every 2s. The map re-styles the *existing* GeoJSON source's `congestion` property each tick — the route geometry is fetched exactly once. |
| Location services | `MapLibreMapScreen.kt` activates MapLibre's `LocationComponent` once `ACCESS_FINE_LOCATION` is granted. |

## Running it

1. Open the project root in Android Studio (this repo *is* the project root — `settings.gradle.kts` is at the top level).
2. Let Gradle sync (needs network access to `mavenCentral()` and `google()` for the MapLibre/OkHttp/Compose dependencies).
3. Run on an emulator or device with Google Play services **not** required — no API key, no billing account, nothing to sign up for.
4. Grant the location permission prompt (optional; only powers the blue-dot demo).
5. Pan/zoom around Kyoto, tap "Big jump → spike," watch the network card and the live protobuf inspector update in real time.

> This repo was scaffolded in a sandbox without an Android SDK or Maven network access, so the Kotlin/Gradle files here were written and reviewed by hand rather than compiled in that environment. Do a normal Gradle sync + build in Android Studio as your first step.

## Why no live raster-tile comparison?

It would be more visually dramatic to add a second, real raster XYZ layer (e.g.
`tile.openstreetmap.org`) and flip between the two. We deliberately didn't:
OSM's [tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
prohibits embedding their tile server in an app that other people will clone
and run — a single popular repo can generate disproportionate load on a
donation-funded service. The raster figure shown in the HUD is instead a
computed estimate (`tiles fetched × average PNG tile size`), with the average
sourced from a published comparative study (see Sources).

## Attribution & licenses

- Map rendering: [MapLibre Native](https://github.com/maplibre/maplibre-native) (BSD-2-Clause).
- Map data & vector tiles: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, served by [OpenFreeMap](https://openfreemap.org/) (ODbL — data must remain attributed; MapLibre shows this via the in-map attribution control, left enabled intentionally).
- Raster-vs-vector tile size figures: Ivanov, R. et al., *"Performance Testing on Vector vs. Raster Map Tiles"*, ISPRS Int. J. Geo-Inf. 9(2):101, 2020 — https://www.mdpi.com/2220-9964/9/2/101
- App code in this repo: MIT, see `LICENSE`.

## Article outline this repo supports

1. Raster → vector: why sending drawing instructions instead of pixels cuts bandwidth, with real measured numbers from this app.
2. Protocol Buffers, decoded by hand: walking through `MvtWireDecoder.kt`/`mvt_demo/build_and_decode.py` field by field.
3. GPU rendering: vector tiles are tessellated client-side so the same tile looks correct at any zoom/rotation, unlike a raster tile which just gets blurry.
4. Network spikes: what actually causes a burst of tile requests (viewport jump) vs. what doesn't (small pan), demonstrated live.
5. Live traffic without re-fetching geometry: how a server push (WebSocket/gRPC stream in production) only ever updates a lightweight property on an already-cached route.
