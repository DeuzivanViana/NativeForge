package io.nativeforge.core.export

import io.nativeforge.core.export.AxmlTestBuilder.Attr
import io.nativeforge.core.export.axml.ManifestPatch
import io.nativeforge.core.export.axml.ManifestPatcher
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestPatcherTest {

    private fun template(utf8: Boolean) = AxmlTestBuilder(utf8)
        .element(
            "manifest",
            Attr("versionCode", 0x0101021b, 0x10, 1),
            Attr("versionName", 0x0101021c, 0x03, "1.0"),
            Attr("package", 0, 0x03, "io.nativeforge.runtime.template"),
        )
        .element("permission", Attr("name", 0x01010003, 0x03, "io.nativeforge.runtime.template.DYNAMIC_RECEIVER_PERMISSION"))
        .element("application", Attr("label", 0x01010001, 0x03, "NativeForge App"))
        .element(
            "activity",
            Attr("name", 0x01010003, 0x03, "io.nativeforge.runtime.template.LauncherActivity"),
            Attr("screenOrientation", 0x0101001e, 0x10, -1),
        )
        .build()

    private val patch = ManifestPatch(
        packageName = "com.example.mygame",
        versionCode = 42,
        versionName = "2.1.0",
        label = "My Game ✨",
        screenOrientation = 0,
        oldPackageName = "io.nativeforge.runtime.template",
    )

    @Test
    fun `patches identity, version, label and orientation (utf8 pool)`() = verify(utf8 = true)

    @Test
    fun `patches identity, version, label and orientation (utf16 pool)`() = verify(utf8 = false)

    private fun verify(utf8: Boolean) {
        val elements = AxmlTestReader.read(ManifestPatcher().patch(template(utf8), patch)).toMap()

        val manifest = elements.getValue("manifest")
        assertEquals("com.example.mygame", manifest["package"])
        assertEquals(42, manifest["versionCode"])
        assertEquals("2.1.0", manifest["versionName"])
        assertEquals("My Game ✨", elements.getValue("application")["label"])
        assertEquals("com.example.mygame.DYNAMIC_RECEIVER_PERMISSION", elements.getValue("permission")["name"])

        val activity = elements.getValue("activity")
        assertEquals("io.nativeforge.runtime.template.LauncherActivity", activity["name"], "class names stay untouched")
        assertEquals(0, activity["screenOrientation"])
    }

    @Test
    fun `adds uses-permission elements after manifest (utf8 pool)`() = verifyPermissions(utf8 = true)

    @Test
    fun `adds uses-permission elements after manifest (utf16 pool)`() = verifyPermissions(utf8 = false)

    private fun verifyPermissions(utf8: Boolean) {
        val template = AxmlTestBuilder(utf8)
            .element("manifest", Attr("package", 0, 0x03, "io.nativeforge.runtime.template"))
            .element("uses-permission", Attr("name", 0x01010003, 0x03, "android.permission.VIBRATE"))
            .element("application", Attr("label", 0x01010001, 0x03, "NativeForge App"))
            .element("activity", Attr("name", 0x01010003, 0x03, "io.nativeforge.runtime.template.LauncherActivity"))
            .build()
        val permissions = listOf("android.permission.CAMERA", "android.permission.VIBRATE", "android.permission.INTERNET", "android.permission.CAMERA")
        val patched = ManifestPatcher().patch(template, patch.copy(permissions = permissions))
        val elements = AxmlTestReader.read(patched)

        assertEquals(
            listOf("manifest", "uses-permission", "uses-permission", "uses-permission", "application", "activity"),
            elements.map { it.first },
        )
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.INTERNET", "android.permission.VIBRATE"),
            elements.filter { it.first == "uses-permission" }.map { it.second["name"] },
            "new permissions come first, the declared one is not repeated",
        )
        assertEquals("io.nativeforge.runtime.template.LauncherActivity", elements.last().second["name"])
        assertEquals("com.example.mygame", elements.first().second["package"])
        assertEquals(balancedEndElements(patched), 0)
    }

    @Test
    fun `no permissions leaves the element tree alone`() {
        val original = template(utf8 = true)
        val patched = ManifestPatcher().patch(original, ManifestPatch())
        assertEquals(AxmlTestReader.read(original), AxmlTestReader.read(patched))
    }

    /** Start minus end element chunks; 0 when every element is closed. */
    private fun balancedEndElements(bytes: ByteArray): Int {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var position = 8
        var depth = 0
        while (position < bytes.size) {
            when (buffer.getShort(position).toInt() and 0xFFFF) {
                0x0102 -> depth++
                0x0103 -> depth--
            }
            position += buffer.getInt(position + 4)
        }
        return depth
    }
}
