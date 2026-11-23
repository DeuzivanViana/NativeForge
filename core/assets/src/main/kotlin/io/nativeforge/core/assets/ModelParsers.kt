package io.nativeforge.core.assets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Wavefront OBJ: `v`, `vn` and `f` (polygons are fanned into triangles, negative indices allowed). */
object ObjParser {
    fun parse(text: String): Mesh {
        val sourcePositions = ArrayList<Float>()
        val sourceNormals = ArrayList<Float>()
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val vertexIds = HashMap<Pair<Int, Int>, Int>()
        var hasNormals = true

        fun vertex(token: String): Int {
            val parts = token.split('/')
            val p = resolve(parts[0].toInt(), sourcePositions.size / 3)
            val n = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt()?.let { resolve(it, sourceNormals.size / 3) } ?: -1
            if (n < 0) hasNormals = false
            return vertexIds.getOrPut(p to n) {
                for (axis in 0..2) positions += sourcePositions[p * 3 + axis]
                for (axis in 0..2) normals += if (n >= 0) sourceNormals[n * 3 + axis] else 0f
                positions.size / 3 - 1
            }
        }

        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(WHITESPACE)
            when (parts[0]) {
                "v" -> for (i in 1..3) sourcePositions += parts.getOrNull(i)?.toFloatOrNull() ?: 0f
                "vn" -> for (i in 1..3) sourceNormals += parts.getOrNull(i)?.toFloatOrNull() ?: 0f
                "f" -> {
                    val face = parts.drop(1).map(::vertex)
                    for (i in 1 until face.size - 1) {
                        indices += face[0]; indices += face[i]; indices += face[i + 1]
                    }
                }
            }
        }
        val p = positions.toFloatArray()
        return Mesh.of(p, indices.toIntArray(), if (hasNormals) normals.toFloatArray() else null)
    }

    private fun resolve(index: Int, count: Int): Int = if (index < 0) count + index else index - 1

    private val WHITESPACE = Regex("\\s+")
}

/** STL in its binary or ASCII form. */
object StlParser {
    fun parse(bytes: ByteArray): Mesh = if (isAscii(bytes)) parseAscii(String(bytes, Charsets.US_ASCII)) else parseBinary(bytes)

    private fun isAscii(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return true
        val head = String(bytes, 0, 5, Charsets.US_ASCII)
        if (head != "solid") return false
        // Some binary files also start with "solid": trust the triangle count when it matches the size.
        val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        return 84 + count * 50 != bytes.size.toLong()
    }

    private fun parseBinary(bytes: ByteArray): Mesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = (buffer.getInt(80).toLong() and 0xFFFFFFFFL).toInt()
        require(count <= ModelLoader.MAX_TRIANGLES && 84L + count * 50L <= bytes.size) { "Damaged STL file" }
        val positions = FloatArray(count * 9)
        val normals = FloatArray(count * 9)
        for (t in 0 until count) {
            val base = 84 + t * 50
            val nx = buffer.getFloat(base); val ny = buffer.getFloat(base + 4); val nz = buffer.getFloat(base + 8)
            for (v in 0..2) {
                for (axis in 0..2) positions[t * 9 + v * 3 + axis] = buffer.getFloat(base + 12 + v * 12 + axis * 4)
                normals[t * 9 + v * 3] = nx; normals[t * 9 + v * 3 + 1] = ny; normals[t * 9 + v * 3 + 2] = nz
            }
        }
        val indices = IntArray(count * 3) { it }
        val valid = normals.any { it != 0f }
        return Mesh.of(positions, indices, if (valid) normals else null)
    }

    private fun parseAscii(text: String): Mesh {
        val positions = ArrayList<Float>()
        Regex("vertex\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)").findAll(text).forEach { m ->
            for (i in 1..3) positions += m.groupValues[i].toFloatOrNull() ?: 0f
        }
        val p = positions.toFloatArray()
        return Mesh.of(p, IntArray(p.size / 3) { it })
    }
}

/** ASCII and binary little-endian PLY with x/y/z (and optional nx/ny/nz) vertices and polygon faces. */
object PlyParser {
    fun parse(bytes: ByteArray): Mesh {
        val headerEnd = indexOf(bytes, "end_header".toByteArray()).also { require(it >= 0) { "Not a PLY file" } }
        var bodyStart = headerEnd + "end_header".length
        while (bodyStart < bytes.size && bytes[bodyStart] != '\n'.code.toByte()) bodyStart++
        bodyStart++
        val header = String(bytes, 0, headerEnd, Charsets.US_ASCII).lines()
        val format = header.firstOrNull { it.startsWith("format") }?.split(" ")?.getOrNull(1) ?: "ascii"
        var vertexCount = 0
        var faceCount = 0
        val vertexProps = mutableListOf<Pair<String, String>>()
        var faceCountType = "uchar"
        var faceIndexType = "int"
        var current = ""
        header.forEach { line ->
            val p = line.trim().split(Regex("\\s+"))
            when {
                p[0] == "element" -> { current = p[1]; if (current == "vertex") vertexCount = p[2].toInt() else if (current == "face") faceCount = p[2].toInt() }
                p[0] == "property" && current == "vertex" && p[1] != "list" -> vertexProps += p[2] to p[1]
                p[0] == "property" && current == "face" && p[1] == "list" -> { faceCountType = p[2]; faceIndexType = p[3] }
            }
        }
        val names = vertexProps.map { it.first }
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val hasNormals = "nx" in names
        val indices = ArrayList<Int>()
        if (format == "ascii") {
            val tokens = String(bytes, bodyStart, bytes.size - bodyStart, Charsets.US_ASCII).split(Regex("\\s+")).filter { it.isNotEmpty() }.iterator()
            for (v in 0 until vertexCount) {
                val values = names.associateWith { tokens.next().toFloat() }
                store(values, v, positions, normals)
            }
            repeat(faceCount) {
                val n = tokens.next().toInt()
                val face = IntArray(n) { tokens.next().toInt() }
                fan(face, indices)
            }
        } else {
            require(format == "binary_little_endian") { "Big-endian PLY files are not supported" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(bodyStart)
            for (v in 0 until vertexCount) {
                val values = vertexProps.associate { (name, type) -> name to read(buffer, type).toFloat() }
                store(values, v, positions, normals)
            }
            repeat(faceCount) {
                val n = read(buffer, faceCountType).toInt()
                val face = IntArray(n) { read(buffer, faceIndexType).toInt() }
                fan(face, indices)
            }
        }
        return Mesh.of(positions, indices.toIntArray(), if (hasNormals) normals else null)
    }

    private fun store(values: Map<String, Float>, v: Int, positions: FloatArray, normals: FloatArray) {
        positions[v * 3] = values["x"] ?: 0f; positions[v * 3 + 1] = values["y"] ?: 0f; positions[v * 3 + 2] = values["z"] ?: 0f
        normals[v * 3] = values["nx"] ?: 0f; normals[v * 3 + 1] = values["ny"] ?: 0f; normals[v * 3 + 2] = values["nz"] ?: 0f
    }

    private fun fan(face: IntArray, indices: MutableList<Int>) {
        for (i in 1 until face.size - 1) { indices += face[0]; indices += face[i]; indices += face[i + 1] }
    }

    private fun read(buffer: ByteBuffer, type: String): Double = when (type) {
        "char", "int8" -> buffer.get().toDouble()
        "uchar", "uint8" -> (buffer.get().toInt() and 0xFF).toDouble()
        "short", "int16" -> buffer.short.toDouble()
        "ushort", "uint16" -> (buffer.short.toInt() and 0xFFFF).toDouble()
        "int", "int32" -> buffer.int.toDouble()
        "uint", "uint32" -> (buffer.int.toLong() and 0xFFFFFFFFL).toDouble()
        "float", "float32" -> buffer.float.toDouble()
        "double", "float64" -> buffer.double
        else -> throw IllegalArgumentException("Unknown PLY type $type")
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * glTF 2.0 (`.gltf` with external or embedded buffers, and binary `.glb`): every triangle
 * primitive of the default scene, with node transforms applied.
 */
object GltfParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseGlb(bytes: ByteArray): Mesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 20 && buffer.getInt(0) == 0x46546C67) { "Not a GLB file" }
        var offset = 12
        var document: String? = null
        var binary: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val start = offset + 8
            require(length >= 0 && start + length <= bytes.size) { "Damaged GLB file" }
            when (type) {
                0x4E4F534A -> document = String(bytes, start, length, Charsets.UTF_8)
                0x004E4942 -> binary = bytes.copyOfRange(start, start + length)
            }
            offset = start + length
        }
        val text = document ?: error("GLB without JSON chunk")
        return parseDocument(json.parseToJsonElement(text).jsonObject, binary) { uri -> error("External buffer $uri in a GLB file") }
    }

    fun parseGltf(text: String, readUri: (String) -> ByteArray): Mesh =
        parseDocument(json.parseToJsonElement(text).jsonObject, null, readUri)

    private fun parseDocument(root: JsonObject, glbBuffer: ByteArray?, readUri: (String) -> ByteArray): Mesh {
        val buffers = (root["buffers"] as? JsonArray).orEmpty().mapIndexed { index, element ->
            val uri = element.jsonObject["uri"]?.jsonPrimitive?.contentOrNull
            when {
                uri == null -> glbBuffer ?: if (index == 0) ByteArray(0) else error("Missing buffer $index")
                uri.startsWith("data:") -> Base64.getDecoder().decode(uri.substringAfter(","))
                else -> readUri(java.net.URLDecoder.decode(uri, "UTF-8"))
            }
        }
        val views = (root["bufferViews"] as? JsonArray).orEmpty().map { it.jsonObject }
        val accessors = (root["accessors"] as? JsonArray).orEmpty().map { it.jsonObject }
        val meshes = (root["meshes"] as? JsonArray).orEmpty().map { it.jsonObject }
        val nodes = (root["nodes"] as? JsonArray).orEmpty().map { it.jsonObject }

        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Int>()
        var allNormals = true

        fun accessor(index: Int): Pair<FloatArray, Int> {
            val a = accessors[index]
            val count = a.int("count")
            val components = when (a["type"]?.jsonPrimitive?.contentOrNull) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1 }
            val componentType = a.int("componentType")
            val normalized = a["normalized"]?.jsonPrimitive?.contentOrNull == "true"
            val result = FloatArray(count * components)
            val viewIndex = a["bufferView"]?.jsonPrimitive?.intOrNull ?: return result to components
            val view = views[viewIndex]
            val data = buffers[view.int("buffer")]
            val size = componentSize(componentType)
            val stride = view["byteStride"]?.jsonPrimitive?.intOrNull ?: (size * components)
            val start = (view["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0) + (a["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0)
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) for (c in 0 until components) {
                val at = start + i * stride + c * size
                result[i * components + c] = when (componentType) {
                    5126 -> bb.getFloat(at)
                    5125 -> (bb.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
                    5123 -> (bb.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                    5122 -> bb.getShort(at).toInt().let { if (normalized) maxOf(it / 32767f, -1f) else it.toFloat() }
                    5121 -> (bb.get(at).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                    5120 -> bb.get(at).toInt().let { if (normalized) maxOf(it / 127f, -1f) else it.toFloat() }
                    else -> 0f
                }
            }
            return result to components
        }

        fun addMesh(meshIndex: Int, matrix: FloatArray) {
            val normalMatrix = Matrices.normalMatrix(matrix)
            (meshes[meshIndex]["primitives"] as? JsonArray).orEmpty().forEach { element ->
                val primitive = element.jsonObject
                val mode = primitive["mode"]?.jsonPrimitive?.intOrNull ?: 4
                if (mode != 4) return@forEach // triangles only
                val attributes = primitive["attributes"]?.jsonObject ?: return@forEach
                val positionAccessor = attributes["POSITION"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val (p, _) = accessor(positionAccessor)
                val n = attributes["NORMAL"]?.jsonPrimitive?.intOrNull?.let { accessor(it).first }
                if (n == null) allNormals = false
                val base = positions.size / 3
                for (v in 0 until p.size / 3) {
                    val t = Matrices.transformPoint(matrix, p[v * 3], p[v * 3 + 1], p[v * 3 + 2])
                    positions += t[0]; positions += t[1]; positions += t[2]
                    if (n != null) {
                        val tn = Matrices.transformDirection(normalMatrix, n[v * 3], n[v * 3 + 1], n[v * 3 + 2])
                        normals += tn[0]; normals += tn[1]; normals += tn[2]
                    } else {
                        normals += 0f; normals += 0f; normals += 0f
                    }
                }
                val indexAccessor = primitive["indices"]?.jsonPrimitive?.intOrNull
                if (indexAccessor != null) {
                    accessor(indexAccessor).first.forEach { indices += base + it.toInt() }
                } else {
                    for (v in 0 until p.size / 3) indices += base + v
                }
            }
        }

        fun visit(nodeIndex: Int, parent: FloatArray, depth: Int) {
            if (depth > 64) return
            val node = nodes[nodeIndex]
            val matrix = Matrices.multiply(parent, Matrices.nodeMatrix(node))
            node["mesh"]?.jsonPrimitive?.intOrNull?.let { addMesh(it, matrix) }
            (node["children"] as? JsonArray).orEmpty().forEach { child -> child.jsonPrimitive.intOrNull?.let { visit(it, matrix, depth + 1) } }
        }

        val scenes = (root["scenes"] as? JsonArray).orEmpty()
        val sceneIndex = root["scene"]?.jsonPrimitive?.intOrNull ?: 0
        val rootNodes = scenes.getOrNull(sceneIndex)?.jsonObject?.get("nodes")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
        if (rootNodes != null) {
            rootNodes.forEach { visit(it, Matrices.identity(), 0) }
        } else {
            meshes.indices.forEach { addMesh(it, Matrices.identity()) }
        }
        return Mesh.of(positions.toFloatArray(), indices.toIntArray(), if (allNormals) normals.toFloatArray() else null)
    }

    private fun componentSize(type: Int) = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    /** Column-major 4×4 matrices, as glTF stores them. */
    internal object Matrices {
        fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(16)
            for (col in 0..3) for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                r[col * 4 + row] = sum
            }
            return r
        }

        fun nodeMatrix(node: JsonObject): FloatArray {
            (node["matrix"] as? JsonArray)?.let { m -> if (m.size == 16) return FloatArray(16) { m[it].jsonPrimitive.floatOrNull ?: 0f } }
            val t = (node["translation"] as? JsonArray)?.map { it.jsonPrimitive.floatOrNull ?: 0f } ?: listOf(0f, 0f, 0f)
            val r = (node["rotation"] as? JsonArray)?.map { it.jsonPrimitive.floatOrNull ?: 0f } ?: listOf(0f, 0f, 0f, 1f)
            val s = (node["scale"] as? JsonArray)?.map { it.jsonPrimitive.floatOrNull ?: 1f } ?: listOf(1f, 1f, 1f)
            val (x, y, z, w) = r
            val rot = floatArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f,
                0f, 0f, 0f, 1f,
            )
            for (col in 0..2) for (row in 0..2) rot[col * 4 + row] *= s[col]
            rot[12] = t[0]; rot[13] = t[1]; rot[14] = t[2]
            return rot
        }

        fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
        )

        /** Upper-left 3×3 of the matrix (enough for rotation and uniform scale). */
        fun normalMatrix(m: FloatArray) = floatArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10])

        fun transformDirection(n: FloatArray, x: Float, y: Float, z: Float): FloatArray {
            val rx = n[0] * x + n[3] * y + n[6] * z
            val ry = n[1] * x + n[4] * y + n[7] * z
            val rz = n[2] * x + n[5] * y + n[8] * z
            val len = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz).takeIf { it > 1e-12f } ?: 1f
            return floatArrayOf(rx / len, ry / len, rz / len)
        }
    }
}
