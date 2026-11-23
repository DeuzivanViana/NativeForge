package io.nativeforge.feature.runner

import android.app.NativeActivity
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.Log
import android.widget.Toast

/**
 * Hosts programs that implement `ANativeActivity_onCreate`, usually through
 * android_native_app_glue: raw EGL/OpenGL ES code, raylib and SFML.
 *
 * Dependencies are preloaded with [System.load] so the dynamic linker can
 * resolve the program's DT_NEEDED entries by soname, then the native shim
 * loads the program itself.
 */
class NativeProgramActivity : NativeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val spec = (LaunchSpecs.fromIntent(intent) ?: LaunchSpecs.fromAssets(this))
            ?.let { LaunchSpecs.resolvePaths(this, it) }

        if (spec == null) {
            Log.e(TAG, "No launch spec provided")
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        try {
            spec.environment.forEach { (key, value) -> Os.setenv(key, value, true) }
            spec.workingDirectory?.let { Os.setenv("PWD", it, true) }
            spec.preloadLibraries.forEach(System::load)
            Os.setenv(PROGRAM_ENV, spec.program, true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to prepare ${spec.program}", e)
            RunErrors.write("Could not prepare ${spec.program}: ${e.message}")
            Toast.makeText(this, "Could not load program: ${e.message}", Toast.LENGTH_LONG).show()
        }
        super.onCreate(savedInstanceState)
        RunnerWindow.apply(this, spec)
        immersive = spec.immersive
    }

    private var immersive = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Bars come back after dialogs or app switches; hide them again.
        if (hasFocus && immersive) RunnerWindow.hideSystemBars(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Native globals cannot be unloaded; end the process so the next run starts clean.
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "NativeProgramActivity"
        const val PROGRAM_ENV = "NATIVEFORGE_PROGRAM"
    }
}
