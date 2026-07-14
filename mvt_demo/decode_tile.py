"""
Decode a real Mapbox/MapLibre Vector Tile (.pbf/.mvt) to show the wire structure
that underlies protobuf-based map clients. Source: MapLibre's open demo tile
server (public sample data, not Google).
"""
import requests
import vector_tile_pb2 as vt
import zlib

URL = "https://demotiles.maplibre.org/tiles/2/2/1.pbf"  # z/x/y

def zigzag_decode(n):
    return (n >> 1) ^ (-(n & 1))

def decode_geometry(cmds):
    """MVT geometry command stream -> list of (dx, dy) moves, per spec section 4.3.4."""
    i = 0
    out = []
    x = y = 0
    while i < len(cmds):
        cmd_int = cmds[i]; i += 1
        cmd_id = cmd_int & 0x7
        cmd_count = cmd_int >> 3
        for _ in range(cmd_count):
            if cmd_id in (1, 2):  # MoveTo / LineTo
                dx = zigzag_decode(cmds[i]); dy = zigzag_decode(cmds[i+1]); i += 2
                x += dx; y += dy
                out.append((cmd_id, x, y))
            elif cmd_id == 7:  # ClosePath
                out.append((cmd_id, x, y))
    return out

def main():
    raw = requests.get(URL, timeout=15).content
    print(f"[wire] raw bytes fetched: {len(raw)} B  (gzip magic? {raw[:2].hex() == '1f8b'})")

    tile = vt.Tile()
    tile.ParseFromString(raw)

    print(f"[tile] layers: {len(tile.layers)}\n")
    for layer in tile.layers:
        print(f"--- layer '{layer.name}' v{layer.version} extent={layer.extent} "
              f"features={len(layer.features)} keys={len(layer.keys)} values={len(layer.values)}")
        for feat in list(layer.features)[:2]:  # sample first 2 features per layer
            tag_pairs = list(zip(feat.tags[0::2], feat.tags[1::2]))
            attrs = {}
            for k_idx, v_idx in tag_pairs:
                key = layer.keys[k_idx]
                val = layer.values[v_idx]
                which = val.WhichOneof("_") if False else None
                for field in ("string_value","float_value","double_value","int_value","uint_value","sint_value","bool_value"):
                    if val.HasField(field):
                        attrs[key] = getattr(val, field)
                        break
            geom_type = vt.Tile.GeomType.Name(feat.type)
            points = decode_geometry(list(feat.geometry))
            print(f"   feature id={feat.id} type={geom_type} attrs={attrs} "
                  f"geometry_cmds={len(feat.geometry)} decoded_pts_sample={points[:3]}")
        print()

if __name__ == "__main__":
    main()
