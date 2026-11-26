package io.nativeforge.feature.editor.highlight

import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.Registry
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ProgressiveHighlighter] with the real C++ grammar ends with the same tokens as a top-to-bottom pass. */
class TextMateProgressiveTest {
    private val grammars = File("src/main/assets/textmate/grammars")
    // The voxel sandbox's sources one after another: real C++, long enough for a screen at line 800.
    private val sampleDir = File("../../core/project/src/main/resources/io/nativeforge/core/project/templates/voxel-sandbox")
    private val sampleFiles = listOf("game.hpp", "world.cpp", "player.cpp", "things.cpp", "ui.cpp", "main.cpp")

    private class GrammarTokenizer(val grammar: IGrammar, val lines: MutableList<String>) : LineTokenizer<IStateStack, List<Int>> {
        override val initialState: IStateStack? = null

        override fun tokenize(line: Int, state: IStateStack?): Tokenized<IStateStack, List<Int>> {
            val result = grammar.tokenizeLine2(lines[line] + "\n", state, Duration.ofSeconds(2))
            return Tokenized(result.ruleStack, result.tokens.toList())
        }

        override fun stateEquals(a: IStateStack?, b: IStateStack?) = a == b

        fun reference(): List<List<Int>> {
            var state: IStateStack? = null
            return lines.indices.map { tokenize(it, state).also { r -> state = r.state }.spans }
        }
    }

    private class Store : LineSpanStore<List<Int>> {
        val lines = ArrayList<List<Int>?>()
        override fun set(line: Int, spans: List<Int>?) { lines[line] = spans }
        override fun insert(line: Int, count: Int) { lines.addAll(line, List(count) { null }) }
        override fun remove(line: Int, count: Int) { repeat(count) { lines.removeAt(line) } }
        override fun clear(lineCount: Int) { lines.clear(); lines.addAll(List(lineCount) { null }) }
    }

    private fun grammar(): IGrammar {
        val files = mapOf(
            "source.c" to "c.tmLanguage.json",
            "source.cpp" to "cpp.tmLanguage.json",
            "source.cpp.embedded.macro" to "cpp-macro.tmLanguage.json",
        )
        return Registry(object : IRegistryOptions {
            override fun getGrammarSource(scopeName: String): IGrammarSource? =
                files[scopeName]?.let { IGrammarSource.fromFile(File(grammars, it)) }
        }).loadGrammar("source.cpp")!!
    }

    private fun ProgressiveHighlighter<*, *>.runToIdle() {
        while (hasWork()) step(System.nanoTime() + 20_000_000)
    }

    @Test
    fun screenFirstHighlightingEndsWithTheSameTokens() {
        val lines = sampleFiles.flatMap { File(sampleDir, it).readLines() }.toMutableList()
        assertTrue(lines.size > 1000, "sample has ${lines.size} lines")
        val tokenizer = GrammarTokenizer(grammar(), lines)
        tokenizer.reference() // Warm up the grammar's regex cache.
        val store = Store()
        val highlighter = ProgressiveHighlighter(tokenizer, store)

        var start = System.nanoTime()
        val whole = tokenizer.reference()
        val wholeMs = (System.nanoTime() - start) / 1e6

        highlighter.reset(lines.size)
        highlighter.setViewport(0, 40)
        start = System.nanoTime()
        while (store.lines[40] == null) highlighter.step(System.nanoTime() + 20_000_000)
        val firstMs = (System.nanoTime() - start) / 1e6

        highlighter.setViewport(800, 840)
        start = System.nanoTime()
        highlighter.step(System.nanoTime())
        val jumpMs = (System.nanoTime() - start) / 1e6
        assertTrue(store.lines[840] != null)

        println("C++, ${lines.size} lines: whole file $wholeMs ms; first screen $firstMs ms; screen at line 800 $jumpMs ms")
        highlighter.runToIdle()
        assertEquals(whole, store.lines.toList())

        // Opening a block comment recolors everything below it, closing it restores the tokens.
        lines[100] = "/* " + lines[100]
        highlighter.onInsert(100, 0)
        highlighter.runToIdle()
        assertEquals(tokenizer.reference(), store.lines.toList())
        lines[100] = lines[100].removePrefix("/* ")
        highlighter.onDelete(100, 0)
        highlighter.runToIdle()
        assertEquals(whole, store.lines.toList())
    }
}
