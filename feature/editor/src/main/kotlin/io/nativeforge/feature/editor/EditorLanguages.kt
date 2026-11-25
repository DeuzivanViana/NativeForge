package io.nativeforge.feature.editor

/** Maps file names to TextMate scope names registered in `assets/textmate/languages.json`. */
object EditorLanguages {
    private const val C = "source.c"
    private const val CPP = "source.cpp"
    private const val CMAKE = "source.cmake"
    private const val JSON = "source.json"
    private const val JSONC = "source.json.comments"
    private const val YAML = "source.yaml"
    private const val TOML = "source.toml"
    private const val INI = "source.ini"
    private const val MAKE = "source.makefile"
    private const val SHELL = "source.shell"
    private const val PYTHON = "source.python"
    private const val MARKDOWN = "text.html.markdown"
    private const val XML = "text.xml"
    private const val GLSL = "source.glsl"
    private const val DIFF = "source.diff"

    private val BY_EXTENSION = mapOf(
        "c" to C,
        "h" to CPP,
        "cpp" to CPP, "cc" to CPP, "cxx" to CPP, "c++" to CPP,
        "hpp" to CPP, "hh" to CPP, "hxx" to CPP, "inl" to CPP, "ipp" to CPP, "tpp" to CPP,
        "cmake" to CMAKE,
        "json" to JSON,
        "jsonc" to JSONC, "json5" to JSONC,
        "yml" to YAML, "yaml" to YAML,
        "toml" to TOML,
        "ini" to INI, "cfg" to INI, "conf" to INI, "properties" to INI, "editorconfig" to INI, "gitconfig" to INI,
        "mk" to MAKE, "mak" to MAKE, "make" to MAKE,
        "sh" to SHELL, "bash" to SHELL, "zsh" to SHELL, "ksh" to SHELL,
        "py" to PYTHON, "pyw" to PYTHON,
        "md" to MARKDOWN, "markdown" to MARKDOWN,
        "xml" to XML, "svg" to XML, "plist" to XML, "xsd" to XML, "tmx" to XML,
        "glsl" to GLSL, "vert" to GLSL, "frag" to GLSL, "geom" to GLSL, "comp" to GLSL,
        "tesc" to GLSL, "tese" to GLSL, "vsh" to GLSL, "fsh" to GLSL,
        "diff" to DIFF, "patch" to DIFF,
    )

    /** Whole file names, and dotfiles whose name is not an extension. */
    private val BY_NAME = mapOf(
        "CMakeLists.txt" to CMAKE,
        "Makefile" to MAKE, "makefile" to MAKE, "GNUmakefile" to MAKE,
        // clang-format, clangd and clang-tidy read YAML.
        ".clang-format" to YAML, "_clang-format" to YAML, ".clangd" to YAML, ".clang-tidy" to YAML,
        ".editorconfig" to INI, ".gitconfig" to INI, ".gitmodules" to INI,
        ".bashrc" to SHELL, ".profile" to SHELL, ".bash_profile" to SHELL, ".zshrc" to SHELL,
        "configure" to SHELL, "gradlew" to SHELL,
    )

    /** Line comment of each language with one; the others ([JSON], [XML], [MARKDOWN], [DIFF]) have none. */
    private val LINE_COMMENTS = mapOf(
        C to "//", CPP to "//", GLSL to "//", JSONC to "//",
        CMAKE to "#", YAML to "#", TOML to "#", INI to "#", MAKE to "#", SHELL to "#", PYTHON to "#",
    )

    /** Every scope [scopeFor] can return. */
    val scopes: Set<String> get() = (BY_EXTENSION.values + BY_NAME.values + CMAKE).toSet()

    /** Returns the scope for [fileName], or null for plain text. */
    fun scopeFor(fileName: String): String? {
        val name = fileName.substringAfterLast('/')
        BY_NAME[name]?.let { return it }
        val lower = name.lowercase()
        // CMake's configure_file() templates: config.cmake.in, FooConfig.cmake.in.
        if (lower.endsWith(".cmake.in")) return CMAKE
        return BY_EXTENSION[lower.substringAfterLast('.', "")]
    }

    /**
     * Prefix that comments out a line of [fileName]: `#` in CMake, Makefiles, shell, YAML...,
     * `//` in C-like languages and plain text, and null where the language has no line comment.
     */
    fun lineComment(fileName: String): String? {
        val scope = scopeFor(fileName) ?: return "//"
        return LINE_COMMENTS[scope]
    }
}
