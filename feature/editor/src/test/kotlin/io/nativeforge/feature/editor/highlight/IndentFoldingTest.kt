package io.nativeforge.feature.editor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals

class IndentFoldingTest {
    private fun ranges(text: String, offSide: Boolean = false): List<Pair<Int, Int>> {
        val lines = text.trimIndent().lines()
        return IndentFolding.ranges(
            lineCount = lines.size,
            offSide = offSide,
            indent = { line -> lines[line].takeIf { it.isNotBlank() }?.let { it.length - it.trimStart().length } ?: -1 },
            marker = { line ->
                when {
                    lines[line].trim().startsWith("#pragma region") -> IndentFolding.Marker.START
                    lines[line].trim().startsWith("#pragma endregion") -> IndentFolding.Marker.END
                    else -> IndentFolding.Marker.NONE
                }
            },
        ).sortedBy { it.first }
    }

    @Test
    fun indentedBodiesAreBlocks() {
        val text = """
            int main(void) {
                if (x) {
                    y();
                    z();
                }
                return 0;
            }
        """
        assertEquals(listOf(0 to 5, 1 to 3), ranges(text))
    }

    @Test
    fun blankLinesInsideABlockDoNotEndIt() {
        val text = """
            void f(void) {
                a();

                b();
            }
        """
        assertEquals(listOf(0 to 3), ranges(text))
    }

    @Test
    fun regionMarkersFold() {
        val text = """
            #pragma region helpers
            int a;
            int b;
            #pragma endregion
            int c;
        """
        assertEquals(listOf(0 to 3), ranges(text))
    }
}
