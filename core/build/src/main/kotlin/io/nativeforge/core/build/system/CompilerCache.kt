package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.BuildRequest
import java.io.File

/**
 * ccache in front of the compiler: a clean build, a build after switching between debug and
 * release, or the same library sources in two projects are served from the cache instead of
 * being compiled again. Used only when Termux's `ccache` package is installed and the project
 * did not turn it off ([io.nativeforge.core.project.model.ProjectConfig.compilerCache]).
 * Links never go through it.
 */
internal object CompilerCache {

    fun launcher(request: BuildRequest): File? =
        request.toolchain.compilerCache?.takeIf { request.project.config.compilerCache }

    /** [command] (compiler first) run through ccache when it is used. */
    fun wrap(request: BuildRequest, command: List<String>): List<String> =
        launcher(request)?.let { listOf(it.path) + command } ?: command
}
