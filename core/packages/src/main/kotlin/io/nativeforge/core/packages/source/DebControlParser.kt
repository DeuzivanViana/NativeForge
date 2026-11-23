package io.nativeforge.core.packages.source

/** Parses Debian control-format stanzas (as found in an APT `Packages` file). */
object DebControlParser {

    fun parse(text: String): List<Map<String, String>> {
        val stanzas = mutableListOf<Map<String, String>>()
        var current = linkedMapOf<String, String>()
        var lastKey: String? = null

        fun flush() {
            if (current.isNotEmpty()) stanzas += current
            current = linkedMapOf()
            lastKey = null
        }

        text.lineSequence().forEach { line ->
            when {
                line.isBlank() -> flush()
                (line.startsWith(" ") || line.startsWith("\t")) && lastKey != null -> {
                    val key = lastKey!!
                    current[key] = current.getValue(key) + "\n" + line.trim()
                }
                else -> {
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        val key = line.substring(0, separator).trim()
                        current[key] = line.substring(separator + 1).trim()
                        lastKey = key
                    }
                }
            }
        }
        flush()
        return stanzas
    }

    /** Splits a comma-separated relationship field (Depends, Provides...). */
    fun splitList(field: String?): List<String> =
        field.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
