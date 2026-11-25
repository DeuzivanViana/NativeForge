package io.nativeforge.feature.editor

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorLanguagesTest {
    private val assets = File("src/main/assets")

    @Test
    fun filesOfCAndCppProjectsGetTheirLanguage() {
        val expected = mapOf(
            "main.c" to "source.c",
            "src/game.hpp" to "source.cpp",
            "CMakeLists.txt" to "source.cmake",
            "cmake/FindFoo.cmake" to "source.cmake",
            "config.h.cmake.in" to "source.cmake",
            "nativeforge.json" to "source.json",
            "CMakePresets.json" to "source.json",
            ".clang-format" to "source.yaml",
            ".clangd" to "source.yaml",
            "ci.yml" to "source.yaml",
            "Cargo.toml" to "source.toml",
            ".editorconfig" to "source.ini",
            "Makefile" to "source.makefile",
            "rules.mk" to "source.makefile",
            "build.sh" to "source.shell",
            "tools/gen.py" to "source.python",
            "README.md" to "text.html.markdown",
            "AndroidManifest.xml" to "text.xml",
            "shaders/light.frag" to "source.glsl",
            "fix.patch" to "source.diff",
        )
        expected.forEach { (file, scope) -> assertEquals(scope, EditorLanguages.scopeFor(file), file) }
        assertNull(EditorLanguages.scopeFor("notes.txt"))
    }

    @Test
    fun lineCommentsFollowTheLanguage() {
        assertEquals("#", EditorLanguages.lineComment("CMakeLists.txt"))
        assertEquals("#", EditorLanguages.lineComment("Makefile"))
        assertEquals("#", EditorLanguages.lineComment(".clang-format"))
        assertEquals("//", EditorLanguages.lineComment("main.cpp"))
        assertEquals("//", EditorLanguages.lineComment("light.frag"))
        assertEquals("//", EditorLanguages.lineComment("notes.txt"))
        assertNull(EditorLanguages.lineComment("nativeforge.json"))
        assertNull(EditorLanguages.lineComment("README.md"))
    }

    @Test
    fun everyScopeIsRegisteredWithExistingFiles() {
        val languages = JsonParser.parseString(File(assets, "textmate/languages.json").readText())
            .asJsonObject.getAsJsonArray("languages").map { it.asJsonObject }
        val registered = languages.associateBy { it["scopeName"].asString }
        EditorLanguages.scopes.forEach { assertTrue(it in registered, "$it is not in languages.json") }
        languages.forEach { language ->
            val grammar = File(assets, language["grammar"].asString)
            assertTrue(grammar.isFile, grammar.path)
            assertEquals(language["scopeName"].asString, JsonParser.parseString(grammar.readText()).asJsonObject["scopeName"].asString)
            language["languageConfiguration"]?.let { assertTrue(File(assets, it.asString).isFile, it.asString) }
        }
    }
}
