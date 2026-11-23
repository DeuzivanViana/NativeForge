package io.nativeforge.core.assets

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelParsersTest {

    @Test
    fun `obj quads are split into triangles and normals computed`() {
        val mesh = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n")
        assertEquals(2, mesh.triangleCount)
        assertEquals(4, mesh.vertexCount)
        assertEquals(1f, mesh.normals[2], 1e-5f) // +Z
    }

    @Test
    fun `obj negative indices and slash forms`() {
        val mesh = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvn 0 0 1\nf -3/1/1 -2/1/1 -1/1/1\n")
        assertEquals(1, mesh.triangleCount)
        assertEquals(1f, mesh.normals[2])
    }

    @Test
    fun `binary and ascii stl`() {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80); buffer.putInt(1)
        buffer.putFloat(0f); buffer.putFloat(0f); buffer.putFloat(1f)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { buffer.putFloat(it) }
        assertEquals(1, StlParser.parse(buffer.array()).triangleCount)
        val ascii = "solid t\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid t\n"
        assertEquals(1, StlParser.parse(ascii.toByteArray()).triangleCount)
    }

    @Test
    fun `ascii ply`() {
        val ply = "ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\n" +
            "element face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n1 1 0\n0 1 0\n4 0 1 2 3\n"
        assertEquals(2, PlyParser.parse(ply.toByteArray()).triangleCount)
    }

    @Test
    fun `gltf with embedded buffer and node translation`() {
        val data = ByteBuffer.allocate(36 + 6).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { data.putFloat(it) }
        data.putShort(0); data.putShort(1); data.putShort(2)
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data.array())
        val gltf = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0,"translation":[10,0,0]}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
             "buffers":[{"byteLength":42,"uri":"$uri"}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}
        """.trimIndent()
        val mesh = GltfParser.parseGltf(gltf) { error("no external files") }
        assertEquals(1, mesh.triangleCount)
        val bounds = mesh.bounds()
        assertEquals(10f, bounds[0]); assertEquals(11f, bounds[3])
        assertTrue(mesh.normals[2] > 0.99f)
    }
}
