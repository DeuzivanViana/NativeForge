package io.nativeforge.core.packages

import io.nativeforge.core.packages.model.PackageInfo

/** Thrown when dependencies cannot be satisfied. */
class DependencyResolutionException(message: String) : Exception(message)

/**
 * Resolves Debian-style dependency clauses into an install order where every
 * package comes after its dependencies.
 *
 * Version constraints are currently ignored: each source publishes a single
 * version per package, which is the one installed.
 */
class DependencyResolver(catalog: Collection<PackageInfo>) {

    private val byName: Map<String, PackageInfo> = catalog.associateBy { it.name }
    private val byProvides: Map<String, PackageInfo> = buildMap {
        catalog.forEach { pkg -> pkg.provides.forEach { putIfAbsent(it, pkg) } }
    }

    /**
     * @param requested package names to install.
     * @param installed names already present; they and their subtrees are skipped.
     */
    fun resolve(requested: List<String>, installed: Set<String> = emptySet()): List<PackageInfo> {
        val ordered = LinkedHashMap<String, PackageInfo>()
        val visiting = HashSet<String>()

        fun visit(pkg: PackageInfo, path: List<String>) {
            if (pkg.name in ordered || pkg.name in installed) return
            if (!visiting.add(pkg.name)) {
                // Debian allows dependency cycles; break them instead of failing.
                return
            }
            pkg.depends.forEach { clause ->
                when (val choice = choose(clause, installed)) {
                    null -> throw DependencyResolutionException(
                        "Package '${pkg.name}' needs '$clause', which is not available (via ${path.joinToString(" -> ")})",
                    )
                    Choice.AlreadyInstalled -> Unit
                    is Choice.Install -> visit(choice.pkg, path + choice.pkg.name)
                }
            }
            visiting.remove(pkg.name)
            ordered[pkg.name] = pkg
        }

        // Report every missing name at once instead of failing on the first one.
        val missing = requested.filter { find(it) == null }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { "'$it'" }
            throw DependencyResolutionException(if (missing.size == 1) "Package $names was not found" else "Packages $names were not found")
        }
        requested.forEach { name -> find(name)?.let { visit(it, listOf(it.name)) } }
        return ordered.values.toList()
    }

    private sealed interface Choice {
        data object AlreadyInstalled : Choice
        data class Install(val pkg: PackageInfo) : Choice
    }

    /** Picks the first satisfiable alternative of [clause], or null when none is. */
    private fun choose(clause: String, installed: Set<String>): Choice? {
        val alternatives = clause.split('|').map(::packageName).filter { it.isNotEmpty() }
        if (alternatives.any { it in installed }) return Choice.AlreadyInstalled
        return alternatives.firstNotNullOfOrNull(::find)?.let { Choice.Install(it) }
    }

    private fun find(name: String): PackageInfo? = byName[name] ?: byProvides[name]

    companion object {
        /** `libfoo:any (>= 1.2)` -> `libfoo`. */
        fun packageName(atom: String): String =
            atom.substringBefore('(').substringBefore('[').trim().substringBefore(':').trim()
    }
}
