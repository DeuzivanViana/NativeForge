package io.nativeforge.feature.editor

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.oniguruma.impl.joni.JoniOnigRegExp
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.Registry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled grammars come from VS Code, which uses Oniguruma; on Android sora-editor
 * matches with Joni, which rejects some constructs. A rejected `end` pattern leaves a
 * comment open until the end of the file. Fix failures with tools/textmate-joni-compat.py.
 */
class GrammarJoniTest {
    private val grammars = File("src/main/assets/textmate/grammars")

    @Test
    fun everyPatternCompilesWithJoni() {
        val failures = mutableListOf<String>()
        grammars.listFiles { f -> f.name.endsWith(".json") }!!.sorted().forEach { file ->
            patterns(JsonParser.parseString(file.readText())) { key, pattern ->
                // Back-references in end/while are replaced by the captured text at run time.
                val testable = if (key == "end" || key == "while") pattern.replace(Regex("""\\\d+"""), "x") else pattern
                runCatching { JoniOnigRegExp(testable) }.onFailure { failures += "${file.name} $key: ${pattern.take(120)}" }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun codeAfterCommentsIsNotComment() {
        val source = """
            // line comment
            int a = 1; // trailing
            /* block */ int b = 2;
            /* multi
               line */
            int c = 3;
            /// doc comment
            int d = 4;
            #define MAX(a, b) \
                ((a) > (b) ? (a) : (b)) // after a continued macro
            int e = MAX(1, 2);
        """.trimIndent().lines()
        for (scope in listOf("source.c", "source.cpp")) {
            val grammar = registry().loadGrammar(scope)!!
            val commentLines = commentOnlyLines(grammar, source)
            assertEquals(listOf(0, 3, 4, 6), commentLines, scope)
        }
    }

    @Test
    fun projectFileGrammarsTokenize() {
        // scope, lines, the line and text to look at, a scope that text must have.
        val cases = listOf(
            Case("source.cmake", listOf("# comment", "add_executable(main main.c)"), 0, "comment", "comment"),
            Case("source.cmake", listOf("# comment", "add_executable(main main.c)"), 1, "add_executable", "keyword"),
            Case("source.json", listOf("{\"name\": 1}"), 0, "name", "support.type.property-name"),
            Case("source.json.comments", listOf("// note", "{}"), 0, "note", "comment"),
            Case("source.yaml", listOf("BasedOnStyle: LLVM # comment"), 0, "BasedOnStyle", "entity.name.tag"),
            Case("source.yaml", listOf("BasedOnStyle: LLVM # comment"), 0, "comment", "comment"),
            Case("source.toml", listOf("[package]", "name = \"x\" # c"), 1, "# c", "comment"),
            Case("source.ini", listOf("[*.c]", "indent_size = 4 ; x", "# y"), 2, "y", "comment"),
            Case("source.makefile", listOf("# rules", "all: main", "\t\$(CC) -o main main.c"), 0, "rules", "comment"),
            Case("source.makefile", listOf("# rules", "all: main"), 1, "all", "entity.name.function.target"),
            Case("source.shell", listOf("#!/bin/sh", "echo hi # done"), 1, "done", "comment"),
            Case("source.python", listOf("def f():  # x", "    return 1"), 0, "def", "storage.type.function"),
            Case("text.html.markdown", listOf("# Title"), 0, "Title", "markup.heading"),
            Case("text.html.markdown", listOf("```cpp", "int x; // in C++", "```", "after"), 1, "in C++", "comment"),
            Case("text.xml", listOf("<manifest> <!-- note --> </manifest>"), 0, "note", "comment"),
            Case("source.glsl", listOf("#version 300 es", "/* c */ uniform vec4 color; // d"), 1, "vec4", "storage.type"),
            Case("source.glsl", listOf("/* multi", "   line */ float a = 1.0;"), 1, "float", "storage.type"),
            Case("source.diff", listOf("--- a/x", "+++ b/x", "@@ -1 +1 @@", "+added"), 3, "added", "markup.inserted"),
        )
        val registry = projectRegistry()
        val failures = cases.mapNotNull { case ->
            val grammar = registry.loadGrammar(case.scope) ?: return@mapNotNull "${case.scope}: grammar not loaded"
            var state: IStateStack? = null
            var scopes = emptyList<String>()
            case.lines.forEachIndexed { index, line ->
                val result = grammar.tokenizeLine(line, state, null)
                state = result.ruleStack
                if (index == case.line) {
                    val at = line.indexOf(case.text)
                    scopes = result.tokens.firstOrNull { at >= it.startIndex && at < it.endIndex }?.scopes.orEmpty()
                }
            }
            if (scopes.any { it.startsWith(case.expected) }) null else "${case.scope} '${case.text}': $scopes"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private data class Case(val scope: String, val lines: List<String>, val line: Int, val text: String, val expected: String)

    /** Every grammar of languages.json, so embedded languages (code blocks in Markdown) resolve. */
    private fun projectRegistry(): Registry {
        val languages = JsonParser.parseString(File(grammars.parentFile, "languages.json").readText())
            .asJsonObject.getAsJsonArray("languages").map { it.asJsonObject }
        val files = languages.associate { it["scopeName"].asString to File(grammars.parentFile.parentFile, it["grammar"].asString) }
        return Registry(object : IRegistryOptions {
            override fun getGrammarSource(scopeName: String): IGrammarSource? = files[scopeName]?.let { IGrammarSource.fromFile(it) }
        })
    }

    /** Lines whose every non-blank character is inside a comment scope. */
    private fun commentOnlyLines(grammar: IGrammar, lines: List<String>): List<Int> {
        var state: IStateStack? = null
        return lines.indices.filter { index ->
            val line = lines[index]
            val result = grammar.tokenizeLine(line, state, null)
            state = result.ruleStack
            result.tokens.all { token ->
                val text = line.substring(token.startIndex.coerceAtMost(line.length), token.endIndex.coerceAtMost(line.length))
                text.isBlank() || token.scopes.any { it.startsWith("comment") }
            }
        }
    }

    private fun registry(): Registry {
        val files = mapOf(
            "source.c" to "c.tmLanguage.json",
            "source.cpp" to "cpp.tmLanguage.json",
            "source.cpp.embedded.macro" to "cpp-macro.tmLanguage.json",
        )
        return Registry(object : IRegistryOptions {
            override fun getGrammarSource(scopeName: String): IGrammarSource? =
                files[scopeName]?.let { IGrammarSource.fromFile(File(grammars, it)) }
        })
    }

    private fun patterns(node: JsonElement, onPattern: (String, String) -> Unit) {
        when {
            node.isJsonObject -> node.asJsonObject.entrySet().forEach { (key, value) ->
                if (key in PATTERN_KEYS && value.isJsonPrimitive) onPattern(key, value.asString) else patterns(value, onPattern)
            }
            node.isJsonArray -> node.asJsonArray.forEach { patterns(it, onPattern) }
        }
    }

    private companion object {
        val PATTERN_KEYS = setOf("match", "begin", "end", "while")
    }
}
