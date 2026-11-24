package io.nativeforge.feature.runner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.project.runtime.LaunchSpec

/** Result of asking the launcher to start a graphical program. */
sealed interface LaunchResult {
    data object Started : LaunchResult
    data class Unsupported(val reason: String) : LaunchResult
}

/**
 * Picks the host activity for a runtime and starts it. New runtimes (SDL2,
 * SDL3...) are added by mapping them to their host activity in [hostFor].
 */
object ProgramLauncher {

    fun hostFor(runtime: RuntimeKind): Class<out Activity>? = when (runtime) {
        RuntimeKind.NATIVE_ACTIVITY -> NativeProgramActivity::class.java
        RuntimeKind.SDL3 -> SdlProgramActivity::class.java
        // SDL2's Java glue is relocated to org.libsdl.sdl2 so it can sit next to SDL3's.
        RuntimeKind.SDL2 -> Sdl2ProgramActivity::class.java
        RuntimeKind.CONSOLE -> null // Console programs run in the terminal feature.
    }

    /**
     * Starts [spec]. With [remote] the program draws on the IDE's off-screen display for
     * the remote web IDE (see [RemoteHost]) instead of the phone's screen.
     */
    fun launch(context: Context, spec: LaunchSpec, remote: RemoteRun? = null): LaunchResult {
        val host = hostFor(spec.runtime)
            ?: return LaunchResult.Unsupported(unsupportedReason(spec.runtime))
        stopRunningProgram(context)
        val intent = LaunchSpecs.putInto(Intent(context, host), spec)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        remote?.let { RemoteHost.putInto(intent, it) }
        context.startActivity(intent)
        return LaunchResult.Started
    }

    private fun unsupportedReason(runtime: RuntimeKind): String = when (runtime) {
        else -> "The ${runtime.name} runtime host is not available"
    }

    /** Kills a previous `:runner` process so the new run loads fresh libraries. */
    fun stopRunningProgram(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runnerProcess = "${context.packageName}:runner"
        manager.runningAppProcesses.orEmpty()
            .filter { it.processName == runnerProcess }
            .forEach { Process.killProcess(it.pid) }
    }
}
