package io.nativeforge.feature.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A few representative colors of an editor theme, for previews in theme pickers (ARGB ints). */
data class EditorThemeColors(val background: Int, val foreground: Int, val keyword: Int, val string: Int, val comment: Int)

object EditorThemePreviews {
    private val cache = mutableMapOf<EditorTheme, EditorThemeColors>()

    fun colors(context: Context, theme: EditorTheme): EditorThemeColors = synchronized(cache) {
        cache.getOrPut(theme) { runCatching { parse(context, theme) }.getOrDefault(FALLBACK) }
    }

    private fun parse(context: Context, theme: EditorTheme): EditorThemeColors {
        val json = JSONObject(context.assets.open(theme.assetPath).bufferedReader().use { it.readText() })
        val colors = json.optJSONObject("colors") ?: JSONObject()
        val background = color(colors.optString("editor.background")) ?: FALLBACK.background
        val foreground = color(colors.optString("editor.foreground")) ?: tokenColor(json, "") ?: FALLBACK.foreground
        return EditorThemeColors(
            background = background,
            foreground = foreground,
            keyword = tokenColor(json, "keyword") ?: foreground,
            string = tokenColor(json, "string") ?: foreground,
            comment = tokenColor(json, "comment") ?: foreground,
        )
    }

    /** Foreground of the first token rule whose scope starts with [scope] ("" = the global rule). */
    private fun tokenColor(json: JSONObject, scope: String): Int? {
        val rules = json.optJSONArray("tokenColors") ?: return null
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val scopes = when (val value = rule.opt("scope")) {
                is String -> value.split(',').map { it.trim() }
                is JSONArray -> (0 until value.length()).map { value.optString(it) }
                else -> listOf("")
            }
            val matches = if (scope.isEmpty()) rule.opt("scope") == null else scopes.any { it == scope || it.startsWith("$scope.") }
            if (matches) color(rule.optJSONObject("settings")?.optString("foreground"))?.let { return it }
        }
        return null
    }

    /** Parses `#RGB`, `#RRGGBB` and `#RRGGBBAA`. */
    private fun color(value: String?): Int? {
        val hex = value?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return null
        val rgba = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("") + "FF"
            6 -> hex + "FF"
            8 -> hex
            else -> return null
        }
        val parsed = rgba.toLongOrNull(16) ?: return null
        val rgb = (parsed shr 8).toInt() and 0xFFFFFF
        val alpha = (parsed and 0xFF).toInt()
        return (alpha shl 24) or rgb
    }

    private val FALLBACK = EditorThemeColors(0xFF000000.toInt(), 0xFFE6E6E6.toInt(), 0xFFC678DD.toInt(), 0xFF98C379.toInt(), 0xFF7F848E.toInt())
}
