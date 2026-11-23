package io.nativeforge.core.build

import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.model.BuildResult
import io.nativeforge.core.build.system.BuildSystemRegistry
import io.nativeforge.core.common.process.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Entry point used by the UI: picks the project's build system, plans and executes. */
class BuildService(
    private val registry: BuildSystemRegistry,
    private val executor: BuildExecutor,
) {

    fun build(request: BuildRequest): Flow<BuildEvent> = flow {
        val missing = request.toolchain.missingComponents()
        if (missing.isNotEmpty()) {
            emit(BuildEvent.Output(OutputStream.STDERR, "Toolchain is not installed (missing: ${missing.joinToString()})."))
            emit(BuildEvent.Finished(BuildResult(false, null, emptyList(), 0)))
            return@flow
        }
        runCatching { request.toolchain.prepare() }
        val plan = try {
            registry.get(request.project.config.buildSystem).plan(request)
        } catch (e: IllegalArgumentException) {
            emit(BuildEvent.Output(OutputStream.STDERR, e.message.orEmpty()))
            emit(BuildEvent.Finished(BuildResult(false, null, emptyList(), 0)))
            return@flow
        }
        if (plan.steps.isEmpty()) {
            emit(BuildEvent.Output(OutputStream.STDOUT, "Everything is up to date."))
        }
        emitAll(executor.execute(plan))
    }
}
