package io.nativeforge.core.build.diagnostics

/** Turns raw tool output lines into [Diagnostic]s. */
fun interface DiagnosticParser {
    fun parse(line: String): Diagnostic?
}

/** Understands clang, lld and CMake output formats. */
class ToolchainDiagnosticParser : DiagnosticParser {

    override fun parse(line: String): Diagnostic? {
        CLANG.matchEntire(line)?.let { m ->
            val (file, lineNo, column, severity, message) = m.destructured
            return Diagnostic(severity(severity), message, file, lineNo.toInt(), column.toIntOrNull())
        }
        LINKER.matchEntire(line)?.let { m ->
            val (severity, message) = m.destructured
            return Diagnostic(severity(severity), message)
        }
        CMAKE.matchEntire(line)?.let { m ->
            val (severity, file, lineNo) = m.destructured
            return Diagnostic(severity(severity), "CMake ${severity.lowercase()}", file, lineNo.toInt())
        }
        return null
    }

    private fun severity(text: String): Severity = when (text.lowercase()) {
        "error", "fatal error" -> Severity.ERROR
        "warning" -> Severity.WARNING
        else -> Severity.NOTE
    }

    private companion object {
        /** `src/main.c:12:5: error: use of undeclared identifier 'x'` */
        val CLANG = Regex("""^(.+?):(\d+):(?:(\d+):)?\s+(fatal error|error|warning|note|remark):\s+(.*)$""")

        /** `ld.lld: error: undefined symbol: foo` */
        val LINKER = Regex("""^(?:.*/)?(?:ld\.lld|ld|lld|clang(?:\+\+)?)(?:-\d+)?:\s+(error|warning):\s+(.*)$""")

        /** `CMake Error at CMakeLists.txt:7 (add_executable):` */
        val CMAKE = Regex("""^CMake (Error|Warning)(?: \(dev\))? at (.+?):(\d+)(?: \(.*\))?:?$""")
    }
}
