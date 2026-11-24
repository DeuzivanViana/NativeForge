package io.nativeforge.core.remote

import io.nativeforge.core.project.model.ExportSettings.Orientation
import io.nativeforge.core.remote.screen.H264
import io.nativeforge.core.remote.screen.KeyMap
import io.nativeforge.core.remote.screen.PointerAction
import io.nativeforge.core.remote.screen.RemoteInput
import io.nativeforge.core.remote.screen.ScreenHub
import io.nativeforge.core.remote.screen.ScreenViewer
import io.nativeforge.core.remote.screen.StreamSize
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenHubTest {

    private class FakeViewer : ScreenViewer {
        val texts = LinkedBlockingQueue<String>()
        val packets = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)
        @Volatile var gate: CountDownLatch? = null
        override fun sendText(text: String) { texts += text }
        override fun sendBinary(data: ByteArray) { gate?.await(5, TimeUnit.SECONDS); packets += data }
        override fun close() = closed.countDown()

        fun text(): String = texts.poll(5, TimeUnit.SECONDS) ?: error("No text message")
        fun packet(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: error("No video packet")
    }

    private fun hub() = ScreenHub(SilentLogger)

    @Test
    fun `viewer gets hello then a key frame that starts with the codec configuration`() {
        val hub = hub()
        lateinit var stream: FakeStream
        val session = hub.start("▶ game") { sink -> FakeStream(1280, 720, sink).also { stream = it } }
        val viewer = FakeViewer()
        assertNotNull(hub.attach(viewer, session.id))

        val hello = Json.parseObject(viewer.text())!!
        assertEquals("hello", hello.string("t"))
        assertEquals(1280, hello.int("width"))
        assertEquals("avc1.42c01f", hello.string("codec"))

        // attach asked for a key frame; the fake answers right away.
        val packet = viewer.packet()
        assertEquals(1, packet[0].toInt())
        val data = packet.copyOfRange(9, packet.size)
        assertTrue(data.copyOfRange(0, FakeStream.CONFIG.size).contentEquals(FakeStream.CONFIG))
        assertEquals(0x65, data[FakeStream.CONFIG.size + 4].toInt())

        // Delta frames follow without the configuration.
        stream.frame(key = false)
        val delta = viewer.packet()
        assertEquals(0, delta[0].toInt())
        assertEquals(1L, ByteBuffer.wrap(delta, 1, 8).long)
        assertEquals(9 + 8, delta.size)
        hub.stop()
    }

    @Test
    fun `a new viewer skips delta frames until a key frame`() {
        val hub = hub()
        lateinit var stream: FakeStream
        // The encoder takes its time to answer key frame requests.
        val session = hub.start("p") { sink -> FakeStream(640, 480, sink).also { stream = it; it.answerKeyFrames = false } }
        val viewer = FakeViewer()
        hub.attach(viewer, session.id)
        viewer.text()
        stream.frame(key = false)
        stream.frame(key = false)
        assertNull(viewer.packets.poll(200, TimeUnit.MILLISECONDS))
        stream.frame(key = true)
        assertEquals(1, viewer.packet()[0].toInt())
        hub.stop()
    }

    @Test
    fun `a slow viewer is dropped back to the next key frame`() {
        val hub = ScreenHub(SilentLogger, maxQueuedBytes = 100)
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(640, 480, sink).also { stream = it } }
        val viewer = FakeViewer().apply { gate = CountDownLatch(1) }
        hub.attach(viewer, session.id)
        viewer.text()
        repeat(40) { stream.frame(key = false) } // queue overflows while the socket is blocked
        val gate = viewer.gate!!
        viewer.gate = null
        gate.countDown()
        val received = generateSequence { viewer.packets.poll(300, TimeUnit.MILLISECONDS) }.toList()
        assertTrue(received.size < 41, "frames were dropped")
        // After dropping, the next packet it accepts again is a key frame.
        stream.frame(key = false)
        stream.frame(key = true)
        val next = generateSequence { viewer.packets.poll(300, TimeUnit.MILLISECONDS) }.toList()
        assertTrue(next.isNotEmpty())
        assertEquals(1, next.first()[0].toInt())
        hub.stop()
    }

    @Test
    fun `input is converted to Android units and key ups match key downs`() {
        val hub = hub()
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(1000, 500, sink).also { stream = it } }
        val viewer = hub.attach(FakeViewer(), session.id)!!

        viewer.receive("""{"t":"ptr","a":"down","x":0.5,"y":0.25,"b":1,"id":0,"k":"mouse"}""")
        viewer.receive("""{"t":"ptr","a":"move","x":2,"y":-1,"b":1,"id":0,"k":"touch"}""")
        viewer.receive("""{"t":"wheel","x":0.1,"y":0.1,"dx":0,"dy":3}""")
        // Shift held while pressing the key labelled "A" on an AZERTY keyboard (code KeyQ).
        viewer.receive("""{"t":"key","d":true,"code":"KeyQ","key":"A","m":1}""")
        viewer.receive("""{"t":"key","d":false,"code":"KeyQ","key":"a","m":0}""")
        viewer.receive("""{"t":"key","d":true,"code":"ArrowLeft","key":"ArrowLeft","m":0}""")
        viewer.receive("""{"t":"key","d":true,"code":"Lang9","key":"Unidentified","m":0}""")
        viewer.receive("""{"t":"release"}""")
        viewer.receive("not json")

        assertEquals(
            listOf(
                RemoteInput.Pointer(PointerAction.DOWN, 500f, 125f, 0, mouse = true, buttons = 1),
                RemoteInput.Pointer(PointerAction.MOVE, 1000f, 0f, 0, mouse = false, buttons = 1),
                RemoteInput.Scroll(100f, 50f, 0f, -3f),
                RemoteInput.Key(true, 29, 0x41),
                RemoteInput.Key(false, 29, 0),
                RemoteInput.Key(true, 21, 0),
                RemoteInput.Key(false, 21, 0), // released when the page lost focus
            ),
            stream.inputs.toList(),
        )
        hub.stop()
    }

    @Test
    fun `viewers learn when the program ends and cannot join afterwards`() {
        val hub = hub()
        val events = mutableListOf<String>()
        hub.onEvent = { events += it.string("t")!! }
        lateinit var stream: FakeStream
        val session = hub.start("p") { sink -> FakeStream(640, 480, sink).also { stream = it } }
        val viewer = FakeViewer()
        hub.attach(viewer, session.id)
        viewer.text()
        stream.sink.onEnded("exit code 0")
        var end: String
        do end = viewer.text() while (!end.contains("\"end\""))
        assertEquals("exit code 0", Json.parseObject(end)!!.string("reason"))
        assertTrue(viewer.closed.await(5, TimeUnit.SECONDS))
        assertNull(hub.session)
        assertNull(hub.attach(FakeViewer(), session.id))
        assertEquals(listOf("screen.start", "screen.end"), events)
    }

    @Test
    fun `starting a new run stops the previous one`() {
        val hub = hub()
        lateinit var first: FakeStream
        hub.start("a") { sink -> FakeStream(640, 480, sink).also { first = it } }
        val second = hub.start("b") { sink -> FakeStream(640, 480, sink) }
        assertTrue(first.stopped)
        assertEquals(second, hub.session)
        hub.stop()
        assertNull(hub.session)
    }

    @Test
    fun `stream size keeps the panel's shape, follows the orientation and fits 1080p`() {
        assertEquals(1280 to 720, StreamSize.fit(1280, 720, Orientation.UNSPECIFIED))
        assertEquals(720 to 1280, StreamSize.fit(1280, 720, Orientation.PORTRAIT))
        assertEquals(1280 to 720, StreamSize.fit(720, 1280, Orientation.SENSOR_LANDSCAPE))
        val (w, h) = StreamSize.fit(3840, 2000, Orientation.UNSPECIFIED)
        assertTrue(w <= 1920 && h <= 1080 && w % 16 == 0 && h % 16 == 0, "$w x $h")
        assertEquals(240 to 240, StreamSize.fit(10, 10, Orientation.UNSPECIFIED))
        assertEquals(1008 to 560, StreamSize.fit(1001, 563, Orientation.UNSPECIFIED))
    }

    @Test
    fun `codec string comes from the SPS`() {
        assertEquals("avc1.42c01f", H264.codecString(FakeStream.CONFIG))
        assertEquals("avc1.640028", H264.codecString(byteArrayOf(0, 0, 1, 0x67, 0x64, 0, 0x28, 1)))
        assertNull(H264.codecString(byteArrayOf(0, 0, 0, 1, 0x68, 1, 2)))
        assertEquals(listOf(4 to 9, 13 to 17), H264.nalUnits(FakeStream.CONFIG))
    }

    @Test
    fun `key map follows the layout for letters and positions for the rest`() {
        assertEquals(29, KeyMap.keyCode("KeyQ", "a"))
        assertEquals(45, KeyMap.keyCode("KeyQ", "q"))
        assertEquals(8, KeyMap.keyCode("Digit1", "!"))
        assertEquals(66, KeyMap.keyCode("Enter", "Enter"))
        assertEquals(74, KeyMap.keyCode("Semicolon", "ç"))
        assertEquals(131, KeyMap.keyCode("F1", "F1"))
        assertNull(KeyMap.keyCode("Lang1", "Unidentified"))
        assertEquals(0x1 or 0x40 or 0x1000 or 0x2000, KeyMap.metaState(KeyMap.MOD_SHIFT or KeyMap.MOD_CTRL))
    }
}
