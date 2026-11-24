package io.nativeforge.feature.runner

import android.app.NativeActivity
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.Log
import android.view.View
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            remoteReady.countDown()
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        try {
            spec.environment.forEach { (key, value) -> Os.setenv(key, value, true) }
            spec.workingDirectory?.let { Os.setenv("PWD", it, true) }
            spec.preloadLibraries.forEach(System::load)
            Os.setenv(PROGRAM_ENV, spec.program, true)
            // The shim then reports raylib's InitWindow size through onProgramWindow.
            if (RemoteHost.isStreamed(intent)) Os.setenv(REMOTE_WINDOW_ENV, "1", true) else Os.unsetenv(REMOTE_WINDOW_ENV)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to prepare ${spec.program}", e)
            RunErrors.write("Could not prepare ${spec.program}: ${e.message}")
            Toast.makeText(this, "Could not load program: ${e.message}", Toast.LENGTH_LONG).show()
        }
        super.onCreate(savedInstanceState)
        RunnerWindow.apply(this, spec)
        immersive = spec.immersive
        remote = RemoteHost.fromIntent(this)?.also(::showInBrowser)
        remoteReady.countDown()
    }

    @Volatile private var remote: RemoteHost? = null

    /** Opens once [remote] is set; the program's thread may ask for a window before that. */
    private val remoteReady = CountDownLatch(1)

    private var immersive = false

    private var windowRequestExpected = false

    /**
     * Moves the program to the remote host's presentation: NativeActivity took this
     * window's surface and input queue in onCreate; they are handed back (so the phone
     * shows a note) and the presentation's are given to the program instead.
     */
    private fun showInBrowser(host: RemoteHost) {
        window.takeSurface(null)
        window.takeInputQueue(null)
        setContentView(host.placeholder())
        host.presentation.window?.apply {
            takeSurface(this@NativeProgramActivity)
            takeInputQueue(this@NativeProgramActivity)
        }
        if (windowRequestExpected) host.showAfterWindowRequest(View(this)) else host.show(View(this))
        // The program keeps drawing while the phone's own window is in the background or
        // the screen is off; programs pause without focus (raylib stops its loop).
        window.decorView.post { super.onWindowFocusChanged(true) }
    }

    /**
     * Called by the native shim during onCreate when the streamed program is a raylib one:
     * it will ask for its window size, so the presentation waits for that (see showInBrowser).
     */
    @Suppress("unused")
    fun expectWindowRequest() {
        windowRequestExpected = true
    }

    /**
     * Called by the native shim on the program's thread when a streamed raylib program
     * calls InitWindow; returns once the display has that size, before raylib reads it.
     */
    @Suppress("unused")
    fun onProgramWindow(width: Int, height: Int, resizable: Boolean) {
        remoteReady.await(2, TimeUnit.SECONDS)
        remote?.requestWindow(width, height, resizable, wait = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (remote != null) return // focus follows the browser, see showInBrowser
        super.onWindowFocusChanged(hasFocus)
        // Bars come back after dialogs or app switches; hide them again.
        if (hasFocus && immersive) RunnerWindow.hideSystemBars(this)
    }

    override fun onDestroy() {
        remote?.close()
        super.onDestroy()
        // Native globals cannot be unloaded; end the process so the next run starts clean.
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "NativeProgramActivity"
        const val PROGRAM_ENV = "NATIVEFORGE_PROGRAM"
        const val REMOTE_WINDOW_ENV = "NATIVEFORGE_REMOTE_WINDOW"
    }
}
