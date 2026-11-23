package io.nativeforge.core.export

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.export.AxmlTestBuilder.Attr
import io.nativeforge.core.export.signing.ApkSigningEngine
import io.nativeforge.core.export.signing.SignatureScheme
import io.nativeforge.core.export.signing.SigningKeyStore
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.toolchain.Abi
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApkExporterTest {

    private val temp: File = Files.createTempDirectory("nf-export").toFile()

    private fun templateApk(): File {
        val manifest = AxmlTestBuilder()
            .element("manifest", Attr("package", 0, 0x03, ApkExporter.TEMPLATE_PACKAGE))
            .build()
        return File(temp, "template.apk").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                    "AndroidManifest.xml" to manifest,
                    "classes.dex" to byteArrayOf(1, 2, 3),
                    "META-INF/CERT.RSA" to byteArrayOf(9),
                    "lib/arm64-v8a/libnativeforge_shim.so" to byteArrayOf(7),
                    "lib/x86_64/libnativeforge_shim.so" to byteArrayOf(8),
                    "res/drawable-nodpi-v4/${ApkExporter.ICON_FOREGROUND}" to byteArrayOf(5),
                ).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }
    }

    @Test
    fun `assembles an apk with the program, its libraries and a launch spec`() {
        val program = File(temp, "libmain.so").apply { writeText("program") }
        val sdl = File(temp, "libSDL2.so").apply { writeText("sdl") }
        val copySigner = ApkSigningEngine { unsigned, output, _, _, _ -> unsigned.copyTo(output, overwrite = true) }
        val key = SigningKeyStore(File(temp, "keystore.p12"), "secret".toCharArray()).loadOrCreate()

        val result = ApkExporter(copySigner).export(
            ExportRequest(
                templateApk = templateApk(),
                outputApk = File(temp, "out/game.apk"),
                settings = ExportSettings(appName = "Game", packageId = "com.example.game"),
                binaries = listOf(AbiBinaries(Abi.ARM64_V8A, program, listOf(sdl))),
                runtime = RuntimeKind.SDL2,
                signingKey = key,
            ),
        )
        assertIs<Outcome.Success<File>>(result, result.errorOrNull()?.message)

        ZipFile(result.value).use { apk ->
            val names = apk.entries().asSequence().map { it.name }.toSet()
            assertTrue("lib/arm64-v8a/libnf_program.so" in names)
            assertTrue("lib/arm64-v8a/libSDL2.so" in names)
            assertTrue("lib/arm64-v8a/libnativeforge_shim.so" in names)
            assertTrue("lib/x86_64/libnativeforge_shim.so" !in names, "other ABIs are stripped")
            assertTrue("META-INF/CERT.RSA" !in names, "old signatures are removed")
            assertEquals(ZipEntry.STORED, apk.getEntry("lib/arm64-v8a/libnf_program.so").method)

            val spec = apk.getInputStream(apk.getEntry("assets/nativeforge/launch.json")).reader().readText()
            assertTrue("libSDL2.so" in spec && "\"relativeToNativeLibraryDir\": true" in spec, spec)

            val manifest = AxmlTestReader.read(apk.getInputStream(apk.getEntry("AndroidManifest.xml")).readBytes())
            assertEquals("com.example.game", manifest.toMap().getValue("manifest")["package"])
        }
    }

    @Test
    fun `custom icon, project assets and display options are applied`() {
        val program = File(temp, "libmain.so").apply { writeText("program") }
        val assets = File(temp, "project/assets").apply { mkdirs() }
        File(assets, "sprites").mkdirs()
        File(assets, "sprites/player.png").writeText("png")
        val copySigner = ApkSigningEngine { unsigned, output, _, _, _ -> unsigned.copyTo(output, overwrite = true) }
        val key = SigningKeyStore(File(temp, "keystore.p12"), "secret".toCharArray()).loadOrCreate()
        val settings = ExportSettings(
            appName = "Game", packageId = "com.example.game",
            orientation = ExportSettings.Orientation.SENSOR_LANDSCAPE, immersive = true, keepScreenOn = true,
        )

        val result = ApkExporter(copySigner).export(
            ExportRequest(
                templateApk = templateApk(), outputApk = File(temp, "out/game2.apk"), settings = settings,
                binaries = listOf(AbiBinaries(Abi.ARM64_V8A, program, emptyList())),
                runtime = RuntimeKind.NATIVE_ACTIVITY, signingKey = key,
                iconForegroundPng = byteArrayOf(42, 43), assetsDirectory = assets,
            ),
        )
        assertIs<Outcome.Success<File>>(result, result.errorOrNull()?.message)

        ZipFile(result.value).use { apk ->
            val icon = apk.getInputStream(apk.getEntry("res/drawable-nodpi-v4/${ApkExporter.ICON_FOREGROUND}")).readBytes()
            assertEquals(listOf<Byte>(42, 43), icon.toList())
            assertTrue(apk.getEntry("assets/assets/sprites/player.png") != null, "assets keep their relative path")
            val spec = apk.getInputStream(apk.getEntry("assets/nativeforge/launch.json")).reader().readText()
            assertTrue("\"sensor_landscape\"" in spec && "\"keepScreenOn\": true" in spec && "\"immersive\": true" in spec, spec)
        }
    }

    @Test
    fun `invalid settings are rejected before touching files`() {
        val result = ApkExporter(ApkSigningEngine { _, _, _, _, _ -> error("must not sign") }).export(
            ExportRequest(
                templateApk = File("missing.apk"), outputApk = File(temp, "x.apk"),
                settings = ExportSettings(appName = "", packageId = "nodots", versionCode = 0),
                binaries = listOf(AbiBinaries(Abi.ARM64_V8A, File("missing"), emptyList())),
                runtime = RuntimeKind.CONSOLE,
                signingKey = SigningKeyStore(File(temp, "k.p12"), "a".toCharArray()).loadOrCreate(),
            ),
        )
        assertEquals(3, (result as Outcome.Failure).error.message.lines().size)
    }

    @Test
    fun `several ABIs keep their own shim and program`() {
        val arm = File(temp, "arm/libmain.so").apply { parentFile.mkdirs(); writeText("arm") }
        val x86 = File(temp, "x86/libmain.so").apply { parentFile.mkdirs(); writeText("x86") }
        val copySigner = ApkSigningEngine { unsigned, output, _, _, _ -> unsigned.copyTo(output, overwrite = true) }
        val key = SigningKeyStore(File(temp, "keystore.p12"), "secret".toCharArray()).loadOrCreate()
        val result = ApkExporter(copySigner).export(
            ExportRequest(
                templateApk = templateApk(), outputApk = File(temp, "out/multi.apk"),
                settings = ExportSettings(appName = "Game", packageId = "com.example.game"),
                binaries = listOf(AbiBinaries(Abi.ARM64_V8A, arm, emptyList()), AbiBinaries(Abi.X86_64, x86, emptyList())),
                runtime = RuntimeKind.NATIVE_ACTIVITY, signingKey = key,
            ),
        )
        assertIs<Outcome.Success<File>>(result, result.errorOrNull()?.message)
        ZipFile(result.value).use { apk ->
            assertEquals("arm", apk.getInputStream(apk.getEntry("lib/arm64-v8a/libnf_program.so")).reader().readText())
            assertEquals("x86", apk.getInputStream(apk.getEntry("lib/x86_64/libnf_program.so")).reader().readText())
            assertTrue(apk.getEntry("lib/x86_64/libnativeforge_shim.so") != null)
        }
    }

    @Test
    fun `app bundle takes resources from the skeleton and code from the apk`() {
        val manifest = ProtoManifestPatcherTest.manifest()
        val skeleton = File(temp, "skeleton.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                    "BundleConfig.pb" to byteArrayOf(1),
                    ApkExporter.BUNDLE_MANIFEST to manifest,
                    "base/resources.pb" to byteArrayOf(2),
                    "base/res/drawable-nodpi-v4/${ApkExporter.ICON_FOREGROUND}" to byteArrayOf(5),
                    "base/root/kotlin/kotlin.kotlin_builtins" to byteArrayOf(3),
                ).forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
        }
        val program = File(temp, "libmain.so").apply { writeText("program") }
        var scheme: SignatureScheme? = null
        val signer = ApkSigningEngine { unsigned, output, _, _, s -> scheme = s; unsigned.copyTo(output, overwrite = true) }
        val key = SigningKeyStore(File(temp, "keystore.p12"), "secret".toCharArray()).loadOrCreate()
        val result = ApkExporter(signer).export(
            ExportRequest(
                templateApk = templateApk(), outputApk = File(temp, "out/game.aab"),
                settings = ExportSettings(appName = "Game", packageId = "com.example.game", format = ExportSettings.Format.AAB, versionCode = 7),
                binaries = listOf(AbiBinaries(Abi.ARM64_V8A, program, emptyList())),
                runtime = RuntimeKind.NATIVE_ACTIVITY, signingKey = key, iconForegroundPng = byteArrayOf(42),
                bundleSkeleton = skeleton,
            ),
        )
        assertIs<Outcome.Success<File>>(result, result.errorOrNull()?.message)
        assertEquals(SignatureScheme.JAR, scheme)
        ZipFile(result.value).use { aab ->
            val names = aab.entries().asSequence().map { it.name }.toSet()
            listOf(
                "BundleConfig.pb", "base/resources.pb", "base/dex/classes.dex", "base/lib/arm64-v8a/libnativeforge_shim.so",
                "base/lib/arm64-v8a/libnf_program.so", "base/assets/nativeforge/launch.json", "base/root/kotlin/kotlin.kotlin_builtins",
            ).forEach { assertTrue(it in names, "$it missing from $names") }
            assertTrue(names.none { it.startsWith("lib/") || it == "AndroidManifest.xml" || it.startsWith("base/lib/x86_64/") }, names.toString())
            assertEquals(listOf<Byte>(42), aab.getInputStream(aab.getEntry("base/res/drawable-nodpi-v4/${ApkExporter.ICON_FOREGROUND}")).readBytes().toList())
            val patched = ProtoManifestPatcherTest.attributes(aab.getInputStream(aab.getEntry(ApkExporter.BUNDLE_MANIFEST)).readBytes())
            assertEquals("com.example.game", patched.getValue("manifest/package"))
            assertEquals("7", patched.getValue("manifest/versionCode"))
        }
    }

    @Test
    fun `keystore is reused across exports`() {
        val store = SigningKeyStore(File(temp, "reuse.p12"), "pw".toCharArray())
        assertEquals(store.loadOrCreate().certificate, store.loadOrCreate().certificate)
    }
}
