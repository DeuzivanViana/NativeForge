package io.nativeforge.core.build.diagnostics

enum class Severity { ERROR, WARNING, NOTE }

/** A compiler, linker or CMake message tied (when possible) to a source location. */
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val file: String? = null,
    /** 1-based line, or null when unknown. */
    val line: Int? = null,
    /** 1-based column, or null when unknown. */
    val column: Int? = null,
)
