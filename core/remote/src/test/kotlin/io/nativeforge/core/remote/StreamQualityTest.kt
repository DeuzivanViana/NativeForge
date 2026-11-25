package io.nativeforge.core.remote

import io.nativeforge.core.common.ThermalLevel
import io.nativeforge.core.remote.screen.AdaptiveQuality
import io.nativeforge.core.remote.screen.PointerAction
import io.nativeforge.core.remote.screen.RemoteInput
import io.nativeforge.core.remote.screen.ScreenHub
import io.nativeforge.core.remote.screen.ScreenViewer
import io.nativeforge.core.remote.screen.StreamQuality
import io.nativeforge.core.remote.screen.StreamQuality.Companion.LEVELS
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamQualityTest {

    private var now = 1_000L
    private val clock = { now }

    @Test
    fun `falling behind steps down at most every two seconds`() {
        val quality = AdaptiveQuality(clock)
        assertEquals(LEVELS[0], quality.current)
        assertTrue(quality.congested())
        assertEquals(LEVELS[1], quality.current)
        now += 500
        assertFalse(quality.congested(), "too soon after the last step")
        now += 2_000
        assertTrue(quality.sample(AdaptiveQuality.HIGH_DELAY_MILLIS + 1))
        assertEquals(2, quality.networkLevel)
        repeat(10) { now += 2_000; quality.congested() }
        assertEquals(LEVELS.last(), quality.current)
    }

    @Test
    fun `short queues for long enough step back up one level at a time`() {
        val quality = AdaptiveQuality(clock)
        quality.congested()
        now += 2_000
        quality.congested()
        assertEquals(2, quality.networkLevel)
        // Calm, but too soon after the last step down.
        now += 1_000
        assertFalse(quality.sample(10))
        now += 4_000
        assertFalse(quality.sample(10))
        now += 4_000
        assertTrue(quality.sample(10))
        assertEquals(1, quality.networkLevel)
        // A medium queue restarts the calm period.
        now += 4_000
        assertFalse(quality.sample(200))
        now += 4_000
        assertFalse(quality.sample(10))
        now += 8_000
        assertTrue(quality.sample(10))
        assertEquals(0, quality.networkLevel)
        now += 20_000
        assertFalse(quality.sample(0), "already at the best level")
    }

    @Test
    fun `a hot phone caps the frame rate whatever the network does`() {
        val quality = AdaptiveQuality(clock)
        assertTrue(quality.thermalChanged(ThermalLevel.MODERATE))
        assertEquals(30, quality.current.frameRate)
        assertTrue(quality.limitedByHeat)
        assertFalse(quality.congested(), "the network level is still under the heat's")
        assertEquals(1, quality.networkLevel)
        assertTrue(quality.thermalChanged(ThermalLevel.SEVERE))
        assertEquals(20, quality.current.frameRate)
        assertTrue(quality.thermalChanged(ThermalLevel.NONE))
        assertEquals(LEVELS[1], quality.current)
        assertFalse(quality.limitedByHeat)
        assertFalse(quality.thermalChanged(ThermalLevel.LIGHT))
    }

    @Test
    fun `bit rate follows size and frame rate within limits`() {
        assertEquals((1280 * 720 * 60 * 0.08).toInt(), LEVELS[0].bitRate(1280, 720))
        assertTrue(LEVELS[2].bitRate(1280, 720) < LEVELS[1].bitRate(1280, 720))
        assertEquals(StreamQuality.MAX_BIT_RATE, LEVELS[0].bitRate(2560, 1440))
        assertEquals(StreamQuality.MIN_BIT_RATE, StreamQuality.IDLE.bitRate(320, 240))
        assertEquals(2_000_000, LEVELS[0].bitRate(640, 360), "small displays keep the old 2 Mbit/s floor")
        LEVELS.zipWithNext().forEach { (better, worse) -> assertTrue(worse.frameRate <= better.frameRate && worse.bitRateScale < better.bitRateScale) }
    }

    // ---------------------------------------------------------------- with ScreenHub

    private class Viewer : ScreenViewer {
        val texts = LinkedBlockingQueue<String>()
        @Volatile var gate: CountDownLatch? = null
        override fun sendText(text: String) { texts += text }
        override fun sendBinary(data: ByteArray) { gate?.await(5, TimeUnit.SECONDS) }
        override fun close() = Unit

        fun next(type: String): Map<String, Any?> {
            while (true) {
                val text = texts.poll(5, TimeUnit.SECONDS) ?: error("No $type message")
                val json = Json.parseObject(text)!!
                if (json.string("t") == type) return mapOf("fps" to json.int("fps"), "hot" to json.bool("hot"))
            }
        }
    }

    @Test
    fun `a viewer that falls behind lowers the encoder's quality and hears about it`() {
        val hub = ScreenHub(SilentLogger, clock, maxQueuedBytes = 100)
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(640, 480, sink).also { stream = it } }
        val viewer = Viewer().apply { gate = CountDownLatch(1) }
        hub.attach(viewer, session.id)
        assertEquals(60, viewer.next("quality")["fps"])
        repeat(40) { stream.frame(key = false) }
        assertEquals(listOf(LEVELS[1]), stream.qualities.toList(), "one step, however many frames overflowed")
        viewer.gate!!.countDown()
        assertEquals(60, viewer.next("quality")["fps"])

        // The browser's decoder dropping frames counts too.
        now += 3_000
        hub.attach(Viewer(), session.id)!!.receive("""{"t":"lag"}""")
        assertEquals(LEVELS[2], stream.qualities.last())
        assertEquals(30, viewer.next("quality")["fps"])
        hub.stop()
    }

    @Test
    fun `heat caps running streams and new ones`() {
        val hub = ScreenHub(SilentLogger, clock)
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(640, 480, sink).also { stream = it } }
        val viewer = Viewer()
        hub.attach(viewer, session.id)
        viewer.next("quality")
        hub.thermalChanged(ThermalLevel.SEVERE)
        assertEquals(LEVELS[4], stream.qualities.last())
        assertEquals(mapOf("fps" to 20, "hot" to true), viewer.next("quality"))

        lateinit var next: FakeStream
        hub.start("q") { sink -> FakeStream(640, 480, sink).also { next = it } }
        assertEquals(listOf(LEVELS[4]), next.qualities.toList())
        hub.thermalChanged(ThermalLevel.NONE)
        assertEquals(LEVELS[0], next.qualities.last())
        hub.stop()
    }

    @Test
    fun `the encoder idles while nobody watches and wakes up for a new viewer`() {
        val hub = ScreenHub(SilentLogger, clock)
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(640, 480, sink).also { stream = it } }
        now += 1_000
        stream.frame(key = false)
        assertTrue(stream.qualities.isEmpty(), "a browser usually joins within a few seconds")
        now += 5_000
        stream.frame(key = false)
        assertEquals(listOf(StreamQuality.IDLE), stream.qualities.toList())
        val viewer = hub.attach(Viewer(), session.id)!!
        assertEquals(LEVELS[0], stream.qualities.last())
        viewer.close()
        now += 1_000
        stream.frame(key = false)
        now += 5_000
        stream.frame(key = false)
        assertEquals(StreamQuality.IDLE, stream.qualities.last())
        hub.stop()
    }

    @Test
    fun `pointer and wheel input also come as binary messages`() {
        val hub = ScreenHub(SilentLogger, clock)
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(1000, 500, sink).also { stream = it } }
        val viewer = hub.attach(Viewer(), session.id)!!

        fun pointer(action: Int, touch: Boolean, id: Int, buttons: Int, x: Float, y: Float) =
            ByteBuffer.allocate(13).put(1).put(action.toByte()).put(if (touch) 1 else 0).put(id.toByte()).put(buttons.toByte()).putFloat(x).putFloat(y).array()

        viewer.receive(pointer(0, false, 0, 1, 0.5f, 0.25f))
        viewer.receive(pointer(1, true, 3, 1, 2f, -1f))
        viewer.receive(pointer(4, false, 200, 0xff, 0.1f, 0.1f))
        viewer.receive(ByteBuffer.allocate(17).put(2).putFloat(0.1f).putFloat(0.1f).putFloat(0f).putFloat(3f).array())
        // Ignored: unknown type or action, too short, not a number.
        viewer.receive(byteArrayOf(9, 0, 0))
        viewer.receive(pointer(7, false, 0, 0, 0.5f, 0.5f))
        viewer.receive(pointer(0, false, 0, 0, 0.5f, 0.5f).copyOf(10))
        viewer.receive(pointer(0, false, 0, 0, Float.NaN, 0.5f))
        viewer.receive(ByteArray(0))

        assertEquals(
            listOf(
                RemoteInput.Pointer(PointerAction.DOWN, 500f, 125f, 0, mouse = true, buttons = 1),
                RemoteInput.Pointer(PointerAction.MOVE, 1000f, 0f, 3, mouse = false, buttons = 1),
                RemoteInput.Pointer(PointerAction.HOVER, 100f, 50f, 9, mouse = true, buttons = 7),
                RemoteInput.Scroll(100f, 50f, 0f, -3f),
            ),
            stream.inputs.toList(),
        )
        hub.stop()
    }
}
