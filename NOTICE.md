# Third-party notices

This project renders map data using open-source software and open data. None
of the following require attribution beyond what's listed here, but it's
included for completeness and because getting this right is itself part of
the system-design story.

## MapLibre Native (Android SDK)

BSD-2-Clause License. https://github.com/maplibre/maplibre-native

## Map data & vector tiles

Map data: © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors, available under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/).

Tile hosting: [OpenFreeMap](https://openfreemap.org/) — free, no API key, no
usage limits, serving an unmodified [OpenMapTiles](https://openmaptiles.org/)
schema. The "Liberty" style used in this app is served from
`https://tiles.openfreemap.org/styles/liberty`.

MapLibre displays the required ODbL attribution via its built-in attribution
control (the small "i" button on the map); this is left enabled deliberately
and should not be removed if you fork this project.

## Why this app does not hit tile.openstreetmap.org

The raster-tile "equivalent bandwidth" figure shown in the HUD is a computed
estimate, not a live fetch. OpenStreetMap's [tile usage
policy](https://operations.osmfoundation.org/policies/tiles/) prohibits
embedding their raster tile server in redistributable apps — a public GitHub
repo that others clone and run can generate disproportionate load on a
donation-funded service. See README.md for the cited source of the average
raster tile size used in the estimate.

## Data-size figures cited in the HUD/README

Ivanov, R.; et al. "Performance Testing on Vector vs. Raster Map
Tiles — Comparative Study on Load Metrics." *ISPRS International Journal of
Geo-Information* 9, no. 2 (2020): 101.
https://www.mdpi.com/2220-9964/9/2/101
