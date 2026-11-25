package io.nativeforge.core.toolchain

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellSetupTest {

    private val root: File = Files.createTempDirectory("nf-shell").toFile()
    private val layout = ToolchainLayout(root)
    private val setup = ShellSetup(layout)

    @Test
    fun `without bash the system shell starts with the hint file`() {
        setup.prepare()
        val shell = setup.shell()
        assertEquals("/system/bin/sh", shell.executable)
        assertEquals(setup.shrc.path, shell.environment["ENV"])
        assertTrue("pkg setup" in setup.shrc.readText())
    }

    @Test
    fun `bash starts with the generated rc file that sources the relocated profile and the user's bashrc`() {
        setup.prepare()
        setup.bash.writeText("#!/system/bin/sh\n")
        setup.bash.setExecutable(true)
        val shell = setup.shell()
        assertEquals(setup.bash.path, shell.executable)
        assertEquals(listOf("--rcfile", setup.bashrc.path), shell.arguments)
        assertEquals(setup.bash.path, shell.environment["SHELL"])
        val rc = setup.bashrc.readText()
        assertTrue("\$PREFIX/etc/profile" in rc)
        assertTrue("\$HOME/.bashrc" in rc)
    }

    @Test
    fun `pkg replaces a script or symlink installed by a Termux package`() {
        layout.binDir.mkdirs()
        File(layout.binDir, "termux-pkg").writeText("#!/bin/sh\napt \"\$@\"\n")
        Files.createSymbolicLink(setup.pkgScript.toPath(), File(layout.binDir, "termux-pkg").toPath())
        setup.prepare()

        assertFalse(Files.isSymbolicLink(setup.pkgScript.toPath()))
        val pkg = setup.pkgScript.readText()
        assertTrue(pkg.startsWith("#!/system/bin/sh\n"))
        assertTrue("app_process" in pkg && ShellSetup.PKG_MAIN_CLASS in pkg && "\$NATIVEFORGE_APK" in pkg)
        assertTrue("unset LD_LIBRARY_PATH" in pkg)
        assertTrue(setup.pkgScript.canExecute())
        // The target of the old symlink is left alone.
        assertTrue("apt" in File(layout.binDir, "termux-pkg").readText())
        assertTrue("/pkg\"" in setup.aptScript.readText())
    }

    @Test
    fun `start-up scripts that name Termux's home are rewritten to ours`() {
        val script = File(layout.prefix, "etc/profile.d/init-termux-properties.sh")
        script.parentFile.mkdirs()
        script.writeText(
            "if [ ! -e /data/data/com.termux/files/home/.termux/termux.properties ]; then\n" +
                "  mkdir -p /data/data/com.termux/files/home/.termux\nfi\n",
        )
        setup.prepare()
        val text = script.readText()
        assertFalse("com.termux" in text, text)
        assertTrue("mkdir -p ${layout.homeDir.path}/.termux" in text, text)
    }

    @Test
    fun `old start-up scripts are rewritten only on the first prepare`() {
        setup.prepare()
        val later = File(layout.prefix, "etc/profile.d/later.sh")
        later.parentFile.mkdirs()
        later.writeText("echo /data/data/com.termux/files/home\n")
        setup.prepare()
        assertTrue("com.termux" in later.readText(), "etc is not walked again on every terminal start")
    }

    @Test
    fun `prepare is idempotent`() {
        setup.prepare()
        val stamp = setup.bashrc.lastModified()
        setup.bashrc.setLastModified(stamp - 10_000)
        setup.prepare()
        assertEquals(stamp - 10_000, setup.bashrc.lastModified())
    }

    @Test
    fun `terminal environment points Termux libraries at the prefix`() {
        val env = ClangToolchain(layout, Abi.ARM64_V8A).environment()
        assertEquals(File(layout.prefix, "share/terminfo").path, env["TERMINFO"])
        assertEquals(File(layout.prefix, "etc/inputrc").path, env["INPUTRC"])
        assertEquals(File(layout.prefix, "etc/tls/cert.pem").path, env["SSL_CERT_FILE"])
    }
}
