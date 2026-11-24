package io.nativeforge.core.export

import io.nativeforge.core.project.model.ExportSettings

/** Validates user-entered export settings before an APK is produced. */
object ExportValidator {
    private val SEGMENT = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /** A dotted name such as `android.permission.CAMERA` or `com.example.MY_PERMISSION`. */
    private val PERMISSION = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val RESERVED = setOf(
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
        "true", "false", "null",
    )

    /** Returns a list of problems; empty when [settings] are valid. */
    fun validate(settings: ExportSettings): List<String> = buildList {
        if (settings.appName.isBlank()) add("App name must not be empty")
        packageIdProblem(settings.packageId)?.let(::add)
        if (settings.versionName.isBlank()) add("Version name must not be empty")
        if (settings.versionCode !in 1..2_100_000_000) add("Version code must be between 1 and 2100000000")
        settings.permissions.filterNot { PERMISSION.matches(it) }.forEach { add("\"$it\" is not a valid permission name") }
    }

    fun packageIdProblem(packageId: String): String? {
        val segments = packageId.split('.')
        return when {
            segments.size < 2 -> "Package ID needs at least two segments, e.g. com.example.game"
            segments.any { !SEGMENT.matches(it) } ->
                "Each package ID segment must start with a letter and contain only letters, digits or '_'"
            segments.any { it in RESERVED } -> "Package ID segments cannot be Java keywords"
            else -> null
        }
    }
}
