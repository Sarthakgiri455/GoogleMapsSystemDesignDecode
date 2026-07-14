package com.example.googlemapssystemdesigndecode.protobuf

/**
 * A hand-rolled decoder for the Mapbox/MapLibre Vector Tile (MVT) wire format
 * -- deliberately NOT using the protobuf-java runtime or generated classes, so
 * this file alone is a complete, readable answer to "what does a protobuf tile
 * actually look like on the wire?" for the article.
 *
 * Schema reference (open spec, vector-tile-spec v2.1):
 *   Tile          { repeated Layer layers = 3; }
 *   Layer         { string name = 1; repeated Feature features = 2;
 *                   repeated string keys = 3; repeated Value values = 4;
 *                   uint32 extent = 5 [default=4096]; uint32 version = 15; }
 *   Feature       { uint64 id = 1; repeated uint32 tags = 2 [packed];
 *                   GeomType type = 3; repeated uint32 geometry = 4 [packed]; }
 *   Value         { oneof: string(1) float(2) double(3) int(4) uint(5) sint(6) bool(7) }
 *
 * Every protobuf field on the wire is (fieldNumber << 3 | wireType) followed by
 * a payload whose shape depends on wireType: 0=varint, 2=length-delimited.
 */
object MvtWireDecoder {

    data class DecodedValue(val value: Any?)

    data class DecodedFeature(
        val id: Long,
        val geomType: String,
        val attributes: Map<String, Any?>,
        val geometryCommandCount: Int,
        val decodedPoints: List<Triple<Int, Int, Int>>, // (commandId, absX, absY)
    )

    data class DecodedLayer(
        val name: String,
        val version: Int,
        val extent: Int,
        val keys: List<String>,
        val values: List<DecodedValue>,
        val features: List<DecodedFeature>,
    )

    data class DecodedTile(val byteSize: Int, val layers: List<DecodedLayer>)

    private val GEOM_TYPES = arrayOf("UNKNOWN", "POINT", "LINESTRING", "POLYGON")

    /** Minimal cursor over a byte array, tracking read position like a protobuf CodedInputStream. */
    private class Cursor(val bytes: ByteArray) {
        var pos = 0
        fun hasMore() = pos < bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos].toLong() and 0xFF
                pos++
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
            }
            return result
        }

        fun readTag(): Pair<Int, Int> {
            val tag = readVarint()
            return Pair((tag shr 3).toInt(), (tag and 0x7).toInt())
        }

        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            val slice = bytes.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8 // 64-bit fixed
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> pos += 4 // 32-bit fixed
                else -> error("Unsupported wire type $wireType")
            }
        }
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    /** Turns the MVT `geometry` packed-varint command stream into absolute tile-space points. */
    fun decodeGeometryCommands(commands: List<Int>): List<Triple<Int, Int, Int>> {
        var i = 0
        var x = 0
        var y = 0
        val out = mutableListOf<Triple<Int, Int, Int>>()
        while (i < commands.size) {
            val cmdInt = commands[i]; i++
            val cmdId = cmdInt and 0x7
            val cmdCount = cmdInt shr 3
            repeat(cmdCount) {
                if (cmdId == 1 || cmdId == 2) { // MoveTo / LineTo
                    val dx = zigzag(commands[i].toLong()).toInt()
                    val dy = zigzag(commands[i + 1].toLong()).toInt()
                    i += 2
                    x += dx; y += dy
                    out.add(Triple(cmdId, x, y))
                } else if (cmdId == 7) { // ClosePath
                    out.add(Triple(cmdId, x, y))
                }
            }
        }
        return out
    }

    private fun decodeValue(bytes: ByteArray): DecodedValue {
        val cur = Cursor(bytes)
        var value: Any? = null
        while (cur.hasMore()) {
            val (field, wireType) = cur.readTag()
            when (field) {
                1 -> value = String(cur.readLengthDelimited(), Charsets.UTF_8)
                2 -> value = Float.fromBits(readFixed32(cur))
                3 -> value = Double.fromBits(readFixed64(cur))
                4 -> value = cur.readVarint()
                5 -> value = cur.readVarint()
                6 -> value = zigzag(cur.readVarint())
                7 -> value = cur.readVarint() != 0L
                else -> cur.skip(wireType)
            }
        }
        return DecodedValue(value)
    }

    private fun readFixed32(cur: Cursor): Int {
        var v = 0
        for (shift in 0 until 32 step 8) v = v or ((cur.bytes[cur.pos++].toInt() and 0xFF) shl shift)
        return v
    }

    private fun readFixed64(cur: Cursor): Long {
        var v = 0L
        for (shift in 0 until 64 step 8) v = v or ((cur.bytes[cur.pos++].toLong() and 0xFF) shl shift)
        return v
    }

    /** Feature fields decoded before the layer's keys/values tables are fully known. */
    private data class RawFeature(
        val id: Long,
        val tagPairs: List<Int>,
        val type: Int,
        val geometry: List<Int>,
    )

    private fun decodeFeature(bytes: ByteArray): RawFeature {
        val cur = Cursor(bytes)
        var id = 0L
        val tags = mutableListOf<Int>()
        var type = 0
        val geometry = mutableListOf<Int>()
        while (cur.hasMore()) {
            val (field, wireType) = cur.readTag()
            when (field) {
                1 -> id = cur.readVarint()
                2 -> readPackedVarints(cur, wireType, tags)
                3 -> type = cur.readVarint().toInt()
                4 -> readPackedVarints(cur, wireType, geometry)
                else -> cur.skip(wireType)
            }
        }
        return RawFeature(id, tags, type, geometry)
    }

    private fun readPackedVarints(cur: Cursor, wireType: Int, out: MutableList<Int>) {
        if (wireType == 2) {
            val slice = cur.readLengthDelimited()
            val inner = Cursor(slice)
            while (inner.hasMore()) out.add(inner.readVarint().toInt())
        } else {
            out.add(cur.readVarint().toInt())
        }
    }

    private fun decodeLayer(bytes: ByteArray): DecodedLayer {
        val cur = Cursor(bytes)
        var name = ""
        var version = 1
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<DecodedValue>()
        val rawFeatures = mutableListOf<RawFeature>()
        while (cur.hasMore()) {
            val (field, wireType) = cur.readTag()
            when (field) {
                1 -> name = String(cur.readLengthDelimited(), Charsets.UTF_8)
                2 -> rawFeatures.add(decodeFeature(cur.readLengthDelimited()))
                3 -> keys.add(String(cur.readLengthDelimited(), Charsets.UTF_8))
                4 -> values.add(decodeValue(cur.readLengthDelimited()))
                5 -> extent = cur.readVarint().toInt()
                15 -> version = cur.readVarint().toInt()
                else -> cur.skip(wireType)
            }
        }
        // Only now that keys/values are fully parsed can we resolve each feature's
        // (key_idx, value_idx) tag pairs into a real attribute map -- the reference MVT
        // encoder writes `features` before `keys`/`values`, so this has to be a second pass.
        val resolvedFeatures = rawFeatures.map { raw ->
            val attrs = raw.tagPairs.chunked(2)
                .filter { it.size == 2 }
                .associate { (k, v) -> keys[k] to values[v].value }
            DecodedFeature(
                id = raw.id,
                geomType = GEOM_TYPES.getOrElse(raw.type) { "UNKNOWN" },
                attributes = attrs,
                geometryCommandCount = raw.geometry.size,
                decodedPoints = decodeGeometryCommands(raw.geometry),
            )
        }
        return DecodedLayer(name, version, extent, keys, values, resolvedFeatures)
    }

    /** Entry point: decode a raw MVT `.pbf` payload as fetched straight off the wire. */
    fun decode(raw: ByteArray): DecodedTile {
        val cur = Cursor(raw)
        val layers = mutableListOf<DecodedLayer>()
        while (cur.hasMore()) {
            val (field, wireType) = cur.readTag()
            if (field == 3 && wireType == 2) {
                layers.add(decodeLayer(cur.readLengthDelimited()))
            } else {
                cur.skip(wireType)
            }
        }
        return DecodedTile(raw.size, layers)
    }
}
