# Google Maps System Design — Decoded

Interactive Android demo: open-source vector tiles (MapLibre + OpenFreeMap), a
hand-rolled protobuf/MVT decoder, a live network-byte HUD, and a simulated
live-traffic overlay that recolors real street segments — no Google Maps code
or data involved.

## Try it

1. Open in Android Studio, let Gradle sync, run on a device/emulator.
2. Grant the location permission prompt if it appears (optional).
3. Slide and zoom the map — watch the network HUD and the decoded-tile panel below it update live.
4. Tap **Traffic jam** / **Clear jam** to force the live-traffic overlay, or leave it alone — it updates on its own every 2 seconds.
5. Tap **Reset view** to jump back to the starting location.

Map rendering by [MapLibre](https://github.com/maplibre/maplibre-native)
(BSD-2-Clause). Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors, served by [OpenFreeMap](https://openfreemap.org/). App code: MIT,
see `LICENSE`.
