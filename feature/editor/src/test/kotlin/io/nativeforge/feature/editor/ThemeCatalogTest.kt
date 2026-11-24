package io.nativeforge.feature.editor

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nativeforge.core.ui.theme.AppTheme
import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color as ComposeColor

/** Checks the bundled editor themes and that every app theme pairs with one of them. */
class ThemeCatalogTest {
    private val themesDir = File("src/main/assets/textmate/themes")

    private fun json(theme: EditorTheme): JsonObject {
        val file = File(themesDir, "${theme.id}.json")
        assertTrue(file.isFile, "Missing ${file.path}")
        return JsonParser.parseString(file.readText()).asJsonObject
    }

    @Test
    fun editorThemeIdsAndNamesAreUnique() {
        assertEquals(EditorTheme.entries.size, EditorTheme.entries.map { it.id }.toSet().size)
        assertEquals(EditorTheme.entries.size, EditorTheme.entries.map { it.displayName }.toSet().size)
    }

    @Test
    fun everyEditorThemeHasEditorColorsMatchingItsKind() {
        EditorTheme.entries.forEach { theme ->
            val colors = json(theme).getAsJsonObject("colors")
            assertNotNull(colors, "${theme.id}: no colors")
            val background = colors.get("editor.background")?.asString
            assertNotNull(background, "${theme.id}: no editor.background")
            assertNotNull(colors.get("editor.foreground")?.asString, "${theme.id}: no editor.foreground")
            assertEquals(theme.isDark, luminance(hex(background)) < 0.5, "${theme.id}: isDark does not match its background")
        }
    }

    /** sora-editor parses only `#RRGGBB` and `#RRGGBBAA`; `#fff` throws while the theme is applied. */
    @Test
    fun everyColorUsesALongHexForm() {
        val bad = mutableListOf<String>()
        EditorTheme.entries.forEach { theme ->
            strings(json(theme)) { value ->
                if (value.startsWith("#") && value.length != 7 && value.length != 9) bad += "${theme.id}: $value"
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun everyAppThemeHasAMatchingEditorTheme() {
        AppTheme.entries.forEach { app ->
            val editor = EditorTheme.entries.firstOrNull { it.id == app.editorThemeId }
            assertNotNull(editor, "${app.name}: unknown editor theme ${app.editorThemeId}")
            assertEquals(app.palette.isDark, editor.isDark, "${app.name}: light/dark differs from ${editor.id}")
        }
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.displayName }.toSet().size)
    }

    @Test
    fun appThemeTextIsReadable() {
        AppTheme.entries.forEach { theme ->
            val p = theme.palette
            listOf(p.background, p.panel, p.raised).forEach { surface ->
                assertTrue(contrast(p.text, surface) >= 4.5, "${theme.name}: text on $surface")
            }
            listOf(p.background, p.panel).forEach { surface ->
                assertTrue(contrast(p.textMuted, surface) >= 3.0, "${theme.name}: muted text on $surface")
            }
            assertTrue(contrast(p.text, p.accentContainer) >= 4.5, "${theme.name}: text on accent container")
            assertTrue(contrast(p.onAccent, p.accent) >= 3.0, "${theme.name}: text on accent")
            assertTrue(contrast(p.accent, p.background) >= 3.0, "${theme.name}: accent on background")
        }
    }

    private fun strings(element: JsonElement, onString: (String) -> Unit) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { strings(it.value, onString) }
            element.isJsonArray -> element.asJsonArray.forEach { strings(it, onString) }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> onString(element.asString)
        }
    }

    private fun hex(value: String): ComposeColor {
        val rgb = value.removePrefix("#").take(6).toLong(16)
        return ComposeColor(0xFF000000 or rgb)
    }

    /** WCAG relative luminance. */
    private fun luminance(color: ComposeColor): Double {
        fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: ComposeColor, b: ComposeColor): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
