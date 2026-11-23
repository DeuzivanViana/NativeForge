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
