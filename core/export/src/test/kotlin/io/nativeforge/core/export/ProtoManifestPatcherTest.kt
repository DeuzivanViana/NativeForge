package io.nativeforge.core.export

import io.nativeforge.core.export.axml.ManifestPatch
import io.nativeforge.core.export.axml.ManifestPatcher
import io.nativeforge.core.export.bundle.ProtoManifestPatcher
import io.nativeforge.core.export.bundle.ProtoMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtoManifestPatcherTest {

    @Test
    fun `patches package, version, label, orientation and package-prefixed names`() {
        val patched = ProtoManifestPatcher().patch(
            manifest(),
            ManifestPatch(
                packageName = "com.example.game", versionCode = 42, versionName = "2.1", label = "Game",
                screenOrientation = 6, oldPackageName = ApkExporter.TEMPLATE_PACKAGE,
            ),
        )
        val values = attributes(patched)
        assertEquals("com.example.game", values["manifest/package"])
        assertEquals("42", values["manifest/versionCode"])
        assertEquals(42L, compiledInt(patched, "manifest", "versionCode"))
        assertEquals("2.1", values["manifest/versionName"])
        assertEquals("Game", values["application/label"])
        assertEquals("Game", values["activity/label"])
        assertEquals("sensorLandscape", values["activity/screenOrientation"])
        assertEquals(6L, compiledInt(patched, "activity", "screenOrientation"))
        assertEquals("com.example.game.PERMISSION", values["permission/name"])
        assertEquals("com.example.game.androidx-startup", values["provider/authorities"])
        assertEquals("${ApkExporter.TEMPLATE_PACKAGE}.LauncherActivity", values["activity/name"], "class names stay")
    }

    @Test
    fun `negative orientation is a 64-bit varint`() {
        val patched = ProtoManifestPatcher().patch(manifest(), ManifestPatch(screenOrientation = -1))
        assertEquals(-1L, compiledInt(patched, "activity", "screenOrientation"))
        assertEquals("unspecified", attributes(patched)["activity/screenOrientation"])
    }

    @Test
    fun `adds uses-permission children before the other children`() {
        val patched = ProtoManifestPatcher().patch(
            manifest(),
            ManifestPatch(permissions = listOf("android.permission.CAMERA", "android.permission.INTERNET", "android.permission.CAMERA")),
        )
        val manifest = ProtoMessage.decode(patched).message(1)!!
        val children = manifest.messages(5).map { it.message(1)!! }
        assertEquals(listOf("uses-permission", "uses-permission", "permission", "application"), children.map { it.string(3) })
        val names = children.take(2).map { it.message(4)!! }
        assertEquals(listOf("android.permission.CAMERA", "android.permission.INTERNET"), names.map { it.string(3) })
        names.forEach {
            assertEquals("http://schemas.android.com/apk/res/android", it.string(1))
            assertEquals("name", it.string(2))
            assertEquals(ManifestPatcher.ATTR_NAME.toLong(), it.varint(5))
        }

        val again = ProtoManifestPatcher().patch(patched, ManifestPatch(permissions = listOf("android.permission.INTERNET")))
        assertEquals(4, ProtoMessage.decode(again).message(1)!!.messages(5).size, "declared permissions are not repeated")
    }

    companion object {
        private fun attribute(name: String, value: String, resourceId: Int = 0, int: Long? = null) = ProtoMessage().apply {
            if (resourceId != 0) setString(1, "http://schemas.android.com/apk/res/android")
            setString(2, name)
            setString(3, value)
            if (resourceId != 0) setVarint(5, resourceId.toLong())
            if (int != null) setMessage(6, ProtoMessage().apply { setMessage(7, ProtoMessage().apply { setVarint(6, int) }) })
        }

        private fun element(name: String, attributes: List<ProtoMessage>, children: List<ProtoMessage> = emptyList()): ProtoMessage {
            val element = ProtoMessage().apply {
                setString(3, name)
                attributes.forEach { fields += io.nativeforge.core.export.bundle.ProtoField.Bytes(4, it.encode()) }
                children.forEach { fields += io.nativeforge.core.export.bundle.ProtoField.Bytes(5, it.encode()) }
            }
            return ProtoMessage().apply { setMessage(1, element) }
        }

        /** A proto manifest shaped like the runtime template's (see `bundletool dump manifest`). */
        fun manifest(): ByteArray {
            val pkg = ApkExporter.TEMPLATE_PACKAGE
            val activity = element(
                "activity",
                listOf(
                    attribute("label", "NativeForge App", ManifestPatcher.ATTR_LABEL),
                    attribute("name", "$pkg.LauncherActivity", ManifestPatcher.ATTR_NAME),
                    attribute("screenOrientation", "unspecified", ManifestPatcher.ATTR_SCREEN_ORIENTATION, int = -1),
                ),
            )
            val provider = element("provider", listOf(attribute("authorities", "$pkg.androidx-startup", 0x01010018)))
            val application = element("application", listOf(attribute("label", "NativeForge App", ManifestPatcher.ATTR_LABEL)), listOf(activity, provider))
            val permission = element("permission", listOf(attribute("name", "$pkg.PERMISSION", ManifestPatcher.ATTR_NAME)))
            return element(
                "manifest",
                listOf(
                    attribute("versionCode", "1", ManifestPatcher.ATTR_VERSION_CODE, int = 1),
                    attribute("versionName", "1.0", ManifestPatcher.ATTR_VERSION_NAME),
                    attribute("package", pkg),
                ),
                listOf(permission, application),
            ).encode()
        }

        /** "element/attribute" to value for every attribute in the tree. */
        fun attributes(manifest: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fun walk(node: ProtoMessage) {
                val element = node.message(1) ?: return
                val name = element.string(3)
                element.messages(4).forEach { out["$name/${it.string(2)}"] = it.string(3).orEmpty() }
                element.messages(5).forEach(::walk)
            }
            walk(ProtoMessage.decode(manifest))
            return out
        }

        fun compiledInt(manifest: ByteArray, elementName: String, attributeName: String): Long? {
            var found: Long? = null
            fun walk(node: ProtoMessage) {
                val element = node.message(1) ?: return
                if (element.string(3) == elementName) {
                    element.messages(4).firstOrNull { it.string(2) == attributeName }?.let {
                        found = it.message(6)?.message(7)?.varint(6)
                    }
                }
                element.messages(5).forEach(::walk)
            }
            walk(ProtoMessage.decode(manifest))
            return found
        }
    }
}
