package io.nativeforge.core.codeintel

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.lsp.CompletionKind
import io.nativeforge.core.lsp.Position
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.Toolchain
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to real clangd and clang-format binaries when an NDK is available
 * (NF_NDK=/path/to/ndk); otherwise only the pure parts run.
 */
class CodeIntelTest {

    private val ndk = System.getenv("NF_NDK")?.let(::File)?.takeIf { it.isDirectory }
    private val bin get() = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin")
    private val root: File = Files.createTempDirectory("nf-codeintel").toFile()

    @Test
    fun `clangd options follow the cores and free memory`() {
        assertEquals(CodeIntelOptions(clangTidy = true, backgroundIndex = true, threads = 4), CodeIntelOptions.forDevice(true, cores = 8, availableMemoryMb = 6_000))
        assertEquals(2, CodeIntelOptions.forDevice(false, cores = 8, availableMemoryMb = 1_600).threads)
        val low = CodeIntelOptions.forDevice(false, cores = 8, availableMemoryMb = 700)
        assertEquals(1, low.threads)
        assertEquals(false, low.backgroundIndex, "no project index when memory is short")
        assertEquals(1, CodeIntelOptions.forDevice(false, cores = 1, availableMemoryMb = null).threads)
    }

    @Test
    fun `background index runs at low priority`() {
        val on = ClangdSession.command(File("/x/clangd"), File("/db"), CodeIntelOptions(threads = 2))
        assertTrue("--background-index-priority=low" in on && "-j=2" in on && "--pch-storage=disk" in on)
        val off = ClangdSession.command(File("/x/clangd"), File("/db"), CodeIntelOptions(backgroundIndex = false))
        assertTrue("--background-index=false" in off && off.none { it.startsWith("--background-index-priority") })
    }

    @Test
    fun `replacement offsets are converted from UTF-8 bytes to chars`() {
        val text = "int ação=1;\nint  x;"
        val bytes = text.toByteArray()
        // "int  x" -> "int x": the extra space is at byte offset of the second line + 4.
        val secondLine = text.indexOf('\n') + 1
        val byteOffset = text.substring(0, secondLine).toByteArray().size + 3
        val xml = "<?xml version='1.0'?>\n<replacements xml:space='preserve' incomplete_format='false'>\n" +
            "<replacement offset='$byteOffset' length='2'> </replacement>\n</replacements>"
        val replacements = ClangFormatter.parseReplacements(xml, bytes)
        assertEquals(listOf(Replacement(secondLine + 3, 2, " ")), replacements)
        assertEquals("int ação=1;\nint x;", ClangFormatter.apply(text, replacements))
    }

    @Test
    fun `xml entities in replacements are decoded`() {
        val text = "a"
        val xml = "<replacement offset='1' length='0'>&#10;&lt;&amp;</replacement>"
        assertEquals("a\n<&", ClangFormatter.apply(text, ClangFormatter.parseReplacements(xml, text.toByteArray())))
    }

    @Test
    fun `clang-format formats with the fallback style`() {
        assumeTrue(ndk != null)
        val file = File(root, "src/main.c").apply { parentFile.mkdirs() }
        val text = "int main(){int a=1;\nif(a){return 0;}\n  return 1;}\n"
        val formatter = ClangFormatter(File(bin, "clang-format"))
        val replacements = formatter.format(text, file, root, FormatStyle.LLVM, indentWidth = 4)
        val formatted = ClangFormatter.apply(text, replacements)
        assertEquals("int main() {\n    int a = 1;\n    if (a) {\n        return 0;\n    }\n    return 1;\n}\n", formatted)
    }

    @Test
    fun `clangd completes, reports errors and finds definitions`() {
        assumeTrue(ndk != null)
        val dir = File(root, "proj")
        val main = File(dir, "src/main.cpp").apply { parentFile.mkdirs() }
        File(dir, "src/util.h").writeText("#pragma once\nint addNumbers(int a, int b);\n")
        File(dir, "src/util.cpp").writeText("#include \"util.h\"\nint addNumbers(int a, int b) { return a + b; }\n")
        val text = "#include <cstdio>\n#include \"util.h\"\nint main() {\n    addNum;\n    undefined_call();\n}\n"
        main.writeText(text)
        val project = Project(dir, ProjectConfig(name = "proj", language = Language.CPP))
        val session = ClangdSession.start(BuildRequest(project, NdkToolchain(ndk!!, root)), CodeIntelOptions(backgroundIndex = false))
        try {
            val diagnostics = java.util.concurrent.CompletableFuture<List<io.nativeforge.core.lsp.LspDiagnostic>>()
            session.onDiagnostics = { file, list -> if (file.name == "main.cpp" && list.isNotEmpty()) diagnostics.complete(list) }

            session.sync(main, text)
            val errors = diagnostics.get(30, TimeUnit.SECONDS)
            assertTrue(errors.any { "undefined_call" in it.message }, errors.joinToString { it.message })

            // Completion runs on the parsed file (the editor opens files long before typing).
            val completion = session.completion(main, text, Position(3, 10)).get(30, TimeUnit.SECONDS)
            val item = completion.items.firstOrNull { it.label.startsWith("addNumbers") }
            assertTrue(item != null, "completion offers addNumbers: ${completion.items.take(5).map { it.label }} log=${session.recentLog()}")
            assertEquals(CompletionKind.FUNCTION, item.kind)

            val fixed = text.replace("    addNum;\n", "    addNumbers(1, 2);\n")
            val definition = session.definition(main, fixed, Position(3, 6)).get(30, TimeUnit.SECONDS)
            assertTrue(definition.any { it.file?.name == "util.h" || it.file?.name == "util.cpp" }, definition.toString())

            val rename = session.rename(main, fixed, Position(3, 6), "sum").get(30, TimeUnit.SECONDS)
            assertTrue(rename.changes.keys.any { it.endsWith("main.cpp") }, rename.toString())
        } finally {
            session.stop()
        }
    }

    /** Uses the NDK's clang/clangd for Android arm64, the same target the device uses. */
    private class NdkToolchain(ndk: File, private val home: File) : Toolchain {
        private val bin = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin")
        private val sysroot = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/sysroot")
        override val abi = Abi.ARM64_V8A
        override val apiLevel = 24
        override val cCompiler = File(bin, "clang")
        override val cxxCompiler = File(bin, "clang++")
        override val cmake = File(bin, "cmake")
        override val ninja = File(bin, "ninja")
        override val make = File(bin, "make")
        override val shell = File("/bin/sh")
        override fun environment() = mapOf("HOME" to home.path, "PATH" to "${bin.path}:/usr/bin:/bin")
        override fun baseCompileFlags() = listOf("--target=aarch64-linux-android24", "--sysroot=${sysroot.path}")
        override fun baseLinkFlags() = baseCompileFlags()
        override fun runtimeLibraries() = emptyList<File>()
        override fun missingComponents() = emptyList<String>()
    }
}

class TextEditsTest {
    @Test
    fun `edits are applied bottom-up with line and column positions`() {
        val text = "int value = 1;\nint other = value + value;\n"
        fun at(line: Int, start: Int, end: Int) =
            io.nativeforge.core.lsp.TextEdit(io.nativeforge.core.lsp.Range(Position(line, start), Position(line, end)), "total")
        val result = TextEdits.apply(text, listOf(at(0, 4, 9), at(1, 12, 17), at(1, 20, 25)))
        assertEquals("int total = 1;\nint other = total + total;\n", result)
    }
}
