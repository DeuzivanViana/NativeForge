package io.nativeforge.core.build

import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.build.diagnostics.Severity
import io.nativeforge.core.build.diagnostics.ToolchainDiagnosticParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagnosticParserTest {
    private val parser = ToolchainDiagnosticParser()

    @Test
    fun `parses clang errors and warnings`() {
        assertEquals(
            Diagnostic(Severity.ERROR, "use of undeclared identifier 'x'", "src/main.c", 12, 5),
            parser.parse("src/main.c:12:5: error: use of undeclared identifier 'x'"),
        )
        assertEquals(
            Diagnostic(Severity.WARNING, "unused variable 'y' [-Wunused-variable]", "/p/src/a b.cpp", 3, 9),
            parser.parse("/p/src/a b.cpp:3:9: warning: unused variable 'y' [-Wunused-variable]"),
        )
        assertEquals(Severity.ERROR, parser.parse("main.cpp:1:10: fatal error: 'SDL.h' file not found")?.severity)
    }

    @Test
    fun `parses linker and cmake messages`() {
        assertEquals(
            Diagnostic(Severity.ERROR, "undefined symbol: foo"),
            parser.parse("ld.lld: error: undefined symbol: foo"),
        )
        val cmake = parser.parse("CMake Error at CMakeLists.txt:7 (add_executable):")
        assertEquals("CMakeLists.txt", cmake?.file)
        assertEquals(7, cmake?.line)
    }

    @Test
    fun `ignores ordinary output`() {
        assertNull(parser.parse("[2/3] Building CXX object CMakeFiles/main.dir/src/main.cpp.o"))
        assertNull(parser.parse("    int x = 3;"))
    }
}
