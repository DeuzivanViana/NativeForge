package io.nativeforge.feature.runner

import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.Log
import io.nativeforge.core.project.runtime.LaunchSpec
import org.libsdl.app.SDLActivity

/**
 * Hosts SDL3 programs. SDL's Java glue (org.libsdl.app, bundled in this module)
 * normally loads `libSDL3.so` and `libmain.so` from the APK; here the libraries
 * come from the launch spec instead, so programs built in the IDE (absolute
 * paths) and exported apps (native library directory) both work.
 *
 * The program is a shared library exporting `SDL_main` (SDL_main.h renames main).
 */
class SdlProgramActivity : SDLActivity() {

    private var spec: LaunchSpec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        spec = SdlHosts.prepare(this)
        super.onCreate(savedInstanceState)
        spec?.let { RunnerWindow.apply(this, it) }
    }

    override fun getLibraries(): Array<String> = arrayOf("SDL3")

    override fun loadLibraries() = SdlHosts.loadLibraries(spec)

    override fun getMainSharedObject(): String = spec?.program ?: super.getMainSharedObject()

    override fun getArguments(): Array<String> = spec?.arguments?.toTypedArray() ?: emptyArray()

    override fun onDestroy() {
        super.onDestroy()
        SdlHosts.endProcessIfFinishing(this)
    }
}

/** What the SDL2 and SDL3 hosts share (their base classes come from different Java packages). */
internal object SdlHosts {
    private const val TAG = "SdlHost"

    /** Reads the launch spec and applies its environment and working directory before SDL starts. */
    fun prepare(activity: android.app.Activity): LaunchSpec? {
        val spec = (LaunchSpecs.fromIntent(activity.intent) ?: LaunchSpecs.fromAssets(activity))
            ?.let { LaunchSpecs.resolvePaths(activity, it) }
        spec?.let { launch ->
            runCatching {
                launch.environment.forEach { (key, value) -> Os.setenv(key, value, true) }
                launch.workingDirectory?.let {
                    Os.setenv("PWD", it, true)
                    Posix.chdir(it)
                }
            }.onFailure { Log.w(TAG, "Could not prepare the environment", it) }
        }
        return spec
    }

    /** Loads libc++, the SDL library and other dependencies by absolute path, in order, then the program. */
    fun loadLibraries(spec: LaunchSpec?) {
        val launch = spec ?: throw UnsatisfiedLinkError("No launch spec provided")
        try {
            launch.preloadLibraries.forEach(System::load)
            // Load the program here too, so a missing symbol is reported with the linker's message.
            System.load(launch.program)
        } catch (e: UnsatisfiedLinkError) {
            RunErrors.write("Could not load ${launch.program}: ${e.message}")
            throw e
        }
    }

    /** SDL keeps native state in statics; end the process so the next run starts clean. */
    fun endProcessIfFinishing(activity: android.app.Activity) {
        if (activity.isFinishing) Process.killProcess(Process.myPid())
    }
}

/** Small POSIX helpers implemented in the native shim. */
internal object Posix {
    init {
        System.loadLibrary("nativeforge_shim")
    }

    @JvmStatic external fun chdir(path: String): Int
}
