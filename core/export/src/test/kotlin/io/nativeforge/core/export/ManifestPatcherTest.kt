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
}
