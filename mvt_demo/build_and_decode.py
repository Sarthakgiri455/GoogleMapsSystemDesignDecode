"""
Sandbox has no open egress to public tile CDNs, so this builds a real MVT
(Mapbox Vector Tile) message by hand -- same wire format OpenMapTiles/MapLibre/
Mapbox/Google's own tile pipeline are built on -- then parses it back byte-by-byte.
Swap `raw = tile.SerializeToString()` for `raw = open('some_tile.pbf','rb').read()`
to run this against any real captured tile from your emulator/proxy log.
"""
import vector_tile_pb2 as vt

# ---------- 1. ENCODE: build one layer, one LINESTRING feature (a "road") ----------
tile = vt.Tile()
layer = tile.layers.add()
layer.version = 2
layer.name = "roads"
layer.extent = 4096  # tile coordinate space, per spec

layer.keys.extend(["class", "oneway"])
v0 = layer.values.add(); v0.string_value = "primary"
v1 = layer.values.add(); v1.bool_value = True

feat = layer.features.add()
feat.id = 101
feat.type = vt.Tile.LINESTRING
feat.tags.extend([0, 0, 1, 1])  # (key_idx, val_idx) pairs -> class=primary, oneway=true

def zigzag_encode(n):
    return (n << 1) ^ (n >> 31)

def encode_geometry(points):
    """points: list of (dx, dy) deltas in tile units, first point is MoveTo."""
    cmds = []
    cmds.append((1 & 0x7) | (1 << 3))          # MoveTo, count=1
    cmds.append(zigzag_encode(points[0][0]))
    cmds.append(zigzag_encode(points[0][1]))
    rest = points[1:]
    cmds.append((2 & 0x7) | (len(rest) << 3))  # LineTo, count=len(rest)
    for dx, dy in rest:
        cmds.append(zigzag_encode(dx))
        cmds.append(zigzag_encode(dy))
    return cmds

feat.geometry.extend(encode_geometry([(100, 100), (50, 0), (0, 50)]))

raw = tile.SerializeToString()
print(f"[encode] serialized MVT tile: {len(raw)} bytes")
print(f"[encode] hex: {raw.hex()}\n")

# ---------- 2. DECODE: parse the wire bytes back out ----------
def zigzag_decode(n):
    return (n >> 1) ^ (-(n & 1))

def decode_geometry(cmds):
    i, x, y, out = 0, 0, 0, []
    while i < len(cmds):
        cmd_int = cmds[i]; i += 1
        cmd_id, cmd_count = cmd_int & 0x7, cmd_int >> 3
        for _ in range(cmd_count):
            if cmd_id in (1, 2):
                dx, dy = zigzag_decode(cmds[i]), zigzag_decode(cmds[i + 1]); i += 2
                x, y = x + dx, y + dy
                out.append((cmd_id, x, y))
            elif cmd_id == 7:
                out.append((cmd_id, x, y))
    return out

parsed = vt.Tile()
parsed.ParseFromString(raw)

for layer in parsed.layers:
    print(f"[decode] layer '{layer.name}' v{layer.version} extent={layer.extent} "
          f"features={len(layer.features)}")
    for f in layer.features:
        pairs = list(zip(f.tags[0::2], f.tags[1::2]))
        attrs = {}
        for k, v in pairs:
            val = layer.values[v]
            for field in ("string_value", "float_value", "double_value",
                          "int_value", "uint_value", "sint_value", "bool_value"):
                if val.HasField(field):
                    attrs[layer.keys[k]] = getattr(val, field)
                    break
        print(f"  feature id={f.id} type={vt.Tile.GeomType.Name(f.type)} attrs={attrs}")
        print(f"  raw geometry command ints: {list(f.geometry)}")
        print(f"  decoded absolute tile-space points: {decode_geometry(list(f.geometry))}")
