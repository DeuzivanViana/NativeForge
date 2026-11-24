package io.nativeforge.core.project.template

/**
 * Template sources kept as plain files under `src/main/resources/io/nativeforge/core/project/templates/`,
 * so they can be compiled and tested like any C or C++ file. Larger examples live there;
 * short templates stay inline in [TemplateSources].
 */
internal object TemplateResources {
    private const val ROOT = "/io/nativeforge/core/project/templates/"

    /** Text of [path] (relative to the templates folder); fails loudly when the file is missing. */
    fun read(path: String): String {
        val stream = TemplateResources::class.java.getResourceAsStream(ROOT + path)
            ?: error("Template file $path is missing from the build")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
