package io.nativeforge.core.assets

import java.io.File
import kotlin.math.sqrt

/**
 * A triangle mesh ready for drawing: positions and normals per vertex (xyz), and
 * triangle indices. Models are flattened into one mesh (node transforms applied).
 */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** Axis-aligned bounds: min xyz then max xyz. */
    fun bounds(): FloatArray {
        if (positions.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in positions.indices step 3) {
            for (axis in 0..2) {
                val v = positions[i + axis]
                if (v < b[axis]) b[axis] = v
                if (v > b[axis + 3]) b[axis + 3] = v
            }
        }
        return b
    }

    companion object {
        /** Builds a mesh, computing smooth normals when [normals] is null or the wrong size. */
        fun of(positions: FloatArray, indices: IntArray, normals: FloatArray? = null): Mesh {
            val n = if (normals != null && normals.size == positions.size) normals else computeNormals(positions, indices)
            return Mesh(positions, n, indices)
        }

        fun computeNormals(positions: FloatArray, indices: IntArray): FloatArray {
            val normals = FloatArray(positions.size)
            for (t in 0 until indices.size / 3) {
                val a = indices[t * 3] * 3
                val b = indices[t * 3 + 1] * 3
                val c = indices[t * 3 + 2] * 3
                if (maxOf(a, b, c) + 2 >= positions.size) continue
                val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (v in intArrayOf(a, b, c)) { normals[v] += nx; normals[v + 1] += ny; normals[v + 2] += nz }
            }
            for (i in normals.indices step 3) {
                val len = sqrt(normals[i] * normals[i] + normals[i + 1] * normals[i + 1] + normals[i + 2] * normals[i + 2])
                if (len > 1e-12f) { normals[i] /= len; normals[i + 1] /= len; normals[i + 2] /= len } else normals[i + 1] = 1f
            }
            return normals
        }
    }
}

/** Reads 3D model files into a [Mesh]. */
object ModelLoader {
    /** Upper bound that keeps the viewer responsive on phones. */
    const val MAX_TRIANGLES = 2_000_000

    fun load(file: File): Mesh = when (file.extension.lowercase()) {
        "obj" -> ObjParser.parse(file.readText())
        "stl" -> StlParser.parse(file.readBytes())
        "ply" -> PlyParser.parse(file.readBytes())
        "glb" -> GltfParser.parseGlb(file.readBytes())
        "gltf" -> GltfParser.parseGltf(file.readText()) { uri -> File(file.parentFile, uri).readBytes() }
        else -> throw IllegalArgumentException("Unsupported model format: .${file.extension}")
    }.also { require(it.triangleCount <= MAX_TRIANGLES) { "The model has ${it.triangleCount} triangles; the viewer shows up to $MAX_TRIANGLES" } }
}
