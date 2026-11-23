package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.project.model.BuildSystemKind

/**
 * Turns a project into a [BuildPlan]. New build systems (Meson, xmake...) are
 * added by implementing this interface and registering it in [BuildSystemRegistry].
 */
interface BuildSystem {
    val kind: BuildSystemKind

    fun plan(request: BuildRequest): BuildPlan
}

class BuildSystemRegistry(systems: List<BuildSystem>) {
    private val byKind = systems.associateBy { it.kind }

    fun get(kind: BuildSystemKind): BuildSystem =
        byKind[kind] ?: throw IllegalArgumentException("No build system registered for $kind")

    companion object {
        fun default() = BuildSystemRegistry(
            listOf(SingleSourceBuildSystem(), CMakeBuildSystem(), MakeBuildSystem(), NinjaBuildSystem()),
        )
    }
}
