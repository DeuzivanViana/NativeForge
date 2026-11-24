package io.nativeforge.feature.runner

import android.os.Bundle
import android.view.View
import io.nativeforge.core.project.runtime.LaunchSpec
import org.libsdl.sdl2.SDLActivity

/**
 * Hosts SDL2 programs with SDL 2's Java glue, relocated to `org.libsdl.sdl2` so it does not
 * clash with SDL3's (see `java/org/libsdl/sdl2/README.md`). Works like [SdlProgramActivity]:
 * the program is a shared library exporting `SDL_main`, loaded from the launch spec.
 */
class Sdl2ProgramActivity : SDLActivity() {

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

    override fun getLibraries(): Array<String> = arrayOf("SDL2")

    override fun loadLibraries() = SdlHosts.loadLibraries(spec)

    override fun getMainSharedObject(): String = spec?.program ?: super.getMainSharedObject()

    override fun getArguments(): Array<String> = spec?.arguments?.toTypedArray() ?: emptyArray()

    override fun onDestroy() {
        remote?.close()
        super.onDestroy()
        SdlHosts.endProcessIfFinishing(this)
    }
}
