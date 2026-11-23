package io.nativeforge.feature.assets

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import io.nativeforge.core.assets.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/** OpenGL ES 2 preview of a mesh: lit, centered and scaled to fit; drag orbits, pinch zooms. */
@SuppressLint("ViewConstructor")
internal class ModelSurfaceView(context: Context, mesh: Mesh) : GLSurfaceView(context) {
    private val renderer = MeshRenderer(mesh)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.distance = (renderer.distance / detector.scaleFactor).coerceIn(0.3f, 20f)
            requestRender()
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 4)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setWireframe(on: Boolean) {
        renderer.wireframe = on
        requestRender()
    }

    fun resetCamera() {
        renderer.yaw = 30f; renderer.pitch = 20f; renderer.distance = 2.2f
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                renderer.yaw += (event.x - lastX) * 0.4f
                renderer.pitch = (renderer.pitch + (event.y - lastY) * 0.4f).coerceIn(-89f, 89f)
                lastX = event.x; lastY = event.y
                requestRender()
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = event.x; lastY = event.y }
        }
        return true
    }

    /** Mouse wheel zoom. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            renderer.distance = (renderer.distance * if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) 0.9f else 1.1f).coerceIn(0.3f, 20f)
            requestRender()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}

private class MeshRenderer(private val mesh: Mesh) : GLSurfaceView.Renderer {
    @Volatile var yaw = 30f
    @Volatile var pitch = 20f
    @Volatile var distance = 2.2f
    @Volatile var wireframe = false

    private var program = 0
    private var buffers = IntArray(3)
    private var wireCount = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val temp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.12f, 0.12f, 0.14f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        GLES20.glGenBuffers(3, buffers, 0)
        // Interleaved position + normal, normalized into a unit cube around the origin.
        val bounds = mesh.bounds()
        val cx = (bounds[0] + bounds[3]) / 2; val cy = (bounds[1] + bounds[4]) / 2; val cz = (bounds[2] + bounds[5]) / 2
        val extent = max(max(bounds[3] - bounds[0], bounds[4] - bounds[1]), bounds[5] - bounds[2]).takeIf { it > 0f } ?: 1f
        val scale = 1f / extent
        val data = FloatArray(mesh.vertexCount * 6)
        for (v in 0 until mesh.vertexCount) {
            data[v * 6] = (mesh.positions[v * 3] - cx) * scale
            data[v * 6 + 1] = (mesh.positions[v * 3 + 1] - cy) * scale
            data[v * 6 + 2] = (mesh.positions[v * 3 + 2] - cz) * scale
            data[v * 6 + 3] = mesh.normals[v * 3]; data[v * 6 + 4] = mesh.normals[v * 3 + 1]; data[v * 6 + 5] = mesh.normals[v * 3 + 2]
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ints(mesh.indices), GLES20.GL_STATIC_DRAW)
        val lines = IntArray(mesh.indices.size * 2)
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
            intArrayOf(a, b, b, c, c, a).copyInto(lines, t * 6)
        }
        wireCount = lines.size
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, lines.size * 4, ints(lines), GLES20.GL_STATIC_DRAW)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 45f, width / max(height, 1).toFloat(), 0.01f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(view, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(temp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFlat"), if (wireframe) 1f else 0f)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val normal = GLES20.glGetAttribLocation(program, "aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, 12)
        if (wireframe) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
            drawElements(GLES20.GL_LINES, wireCount)
        } else {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            drawElements(GLES20.GL_TRIANGLES, mesh.indices.size)
        }
    }

    /** 32-bit indices need OES_element_index_uint, which every GLES2 device we target has. */
    private fun drawElements(mode: Int, count: Int) {
        GLES20.glDrawElements(mode, count, GLES20.GL_UNSIGNED_INT, 0)
    }

    private fun floats(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).apply { position(0) }

    private fun ints(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(data).apply { position(0) }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                vNormal = mat3(uModel) * aNormal;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uFlat;
            varying vec3 vNormal;
            void main() {
                vec3 n = normalize(vNormal);
                float key = max(dot(n, normalize(vec3(0.4, 0.8, 0.6))), 0.0);
                float fill = max(dot(n, normalize(vec3(-0.6, -0.2, 0.4))), 0.0) * 0.35;
                vec3 base = vec3(0.78, 0.80, 0.86);
                vec3 lit = base * (0.18 + key * 0.8 + fill);
                gl_FragColor = vec4(mix(lit, vec3(0.45, 0.85, 1.0), uFlat), 1.0);
            }
        """
    }
}
