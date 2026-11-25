package io.nativeforge.core.build

import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.system.CMakeInitialCache
import java.io.File

/** Variables of the initial-cache script (`-C <file>`) a CMake configure step loads. */
internal fun cmakeCache(plan: BuildPlan): Map<String, String> {
    val configure = plan.steps[0].process.command
    val file = File(configure[configure.indexOf("-C") + 1])
    return CMakeInitialCache.parse(file.readText())
}
