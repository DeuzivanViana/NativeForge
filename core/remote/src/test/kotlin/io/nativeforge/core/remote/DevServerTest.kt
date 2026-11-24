package io.nativeforge.core.remote

import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.remote.security.TlsIdentity
import java.io.File
import java.net.InetAddress
import kotlin.test.Test

/** Local development server for the web UI: NF_DEV_SERVER=<dir> ./gradlew :core:remote:test --tests '*DevServerTest*' */
class DevServerTest {
    @Test
    fun run() {
        val dirName = System.getenv("NF_DEV_SERVER") ?: return
        val dir = File(dirName).apply { mkdirs() }
        val access = AccessControl(File(dir, "browsers.json"))
        val backend = FakeBackend(dir).apply { devVideo = System.getenv("NF_DEV_VIDEO")?.let(::File) }
        val server = RemoteServer(backend, access, PrintLogger)
        server.start(TlsIdentity.loadOrCreate(File(dir, "tls.p12")).serverSocket(8443, InetAddress.getLoopbackAddress()))
        File(dir, "code.txt").writeText(access.newPairingCode().code)
        val until = System.currentTimeMillis() + (System.getenv("NF_DEV_MINUTES")?.toLong() ?: 20) * 60_000
        while (System.currentTimeMillis() < until && !File(dir, "stop").exists()) {
            if (File(dir, "newcode").delete()) File(dir, "code.txt").writeText(access.newPairingCode().code)
            Thread.sleep(300)
        }
        server.stop()
    }
}

object PrintLogger : io.nativeforge.core.common.Logger {
    override fun debug(tag: String, message: String) {}
    override fun info(tag: String, message: String) = println("I/$tag: $message")
    override fun warn(tag: String, message: String, error: Throwable?) { println("W/$tag: $message"); error?.printStackTrace(System.out) }
    override fun error(tag: String, message: String, error: Throwable?) { println("E/$tag: $message"); error?.printStackTrace(System.out) }
}
