package io.nativeforge.feature.editor

/** Maps file names to TextMate scope names registered in `assets/textmate/languages.json`. */
object EditorLanguages {
    private val BY_EXTENSION = mapOf(
        "c" to "source.c",
        "h" to "source.cpp",
        "cpp" to "source.cpp", "cc" to "source.cpp", "cxx" to "source.cpp", "c++" to "source.cpp",
        "hpp" to "source.cpp", "hh" to "source.cpp", "hxx" to "source.cpp", "inl" to "source.cpp",
        "cmake" to "source.cmake",
    )

    /** Returns the scope for [fileName], or null for plain text. */
    fun scopeFor(fileName: String): String? {
        if (fileName == "CMakeLists.txt") return "source.cmake"
        return BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()]
    }
}
