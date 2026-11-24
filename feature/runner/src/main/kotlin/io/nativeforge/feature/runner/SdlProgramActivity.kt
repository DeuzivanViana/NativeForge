package io.nativeforge.feature.runner

import android.os.Bundle
import android.view.View
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

    private var remote: RemoteHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        spec = SdlHosts.prepare(this)
        remote = RemoteHost.fromIntent(this)
        super.onCreate(savedInstanceState)
        spec?.let { RunnerWindow.apply(this, it) }
        remote?.let { SdlHosts.startInBrowser(this, it) { resumeNativeThread() } }
    }

    /** SDL puts its surface in the content view; a streamed run shows it on the remote host instead. */
    override fun setContentView(view: View?) {
        val host = remote
        if (host == null || view == null) return super.setContentView(view)
        host.presentation.setContentView(view)
        super.setContentView(host.placeholder())
    }

    // A streamed run keeps going while the phone's window is hidden or the screen is off.
    override fun pauseNativeThread() {
        if (remote == null) super.pauseNativeThread()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) = super.onWindowFocusChanged(hasFocus || remote != null)

    /**
     * SDL passes the size SDL_CreateWindow got (Android otherwise ignores it). A streamed run
     * sizes the display from it instead of turning the phone.
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        val host = remote ?: return super.setOrientationBis(w, h, resizable, hint)
        SdlHosts.requestWindow(host, w, h, resizable)
    }

    override fun getLibraries(): Array<String> = arrayOf("SDL3")

    override fun loadLibraries() = SdlHosts.loadLibraries(spec)

    override fun getMainSharedObject(): String = spec?.program ?: super.getMainSharedObject()

    override fun getArguments(): Array<String> = spec?.arguments?.toTypedArray() ?: emptyArray()

    override fun onDestroy() {
        remote?.close()
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

    /**
     * Shows a streamed run's SDL surface on the remote host and starts SDL's thread even if
     * the phone's window never gets focus or resumes (screen off, other app in front).
     */
    fun startInBrowser(activity: android.app.Activity, host: RemoteHost, resume: () -> Unit) {
        host.show()
        activity.window.decorView.post {
            activity.onWindowFocusChanged(true)
            resume()
        }
    }

    /** A window without a usable size (SDL passes 1 or less) follows the browser. */
    fun requestWindow(host: RemoteHost, width: Int, height: Int, resizable: Boolean) {
        if (width <= 1 || height <= 1) host.requestWindow(0, 0, true, wait = false)
        else host.requestWindow(width, height, resizable, wait = false)
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
