package io.nativeforge.feature.runner

import android.os.Bundle
import io.nativeforge.core.project.runtime.LaunchSpec
import org.libsdl.sdl2.SDLActivity

/**
 * Hosts SDL2 programs with SDL 2's Java glue, relocated to `org.libsdl.sdl2` so it does not
 * clash with SDL3's (see `java/org/libsdl/sdl2/README.md`). Works like [SdlProgramActivity]:
 * the program is a shared library exporting `SDL_main`, loaded from the launch spec.
 */
class Sdl2ProgramActivity : SDLActivity() {

    private var spec: LaunchSpec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        spec = SdlHosts.prepare(this)
        super.onCreate(savedInstanceState)
        spec?.let { RunnerWindow.apply(this, it) }
    }

    override fun getLibraries(): Array<String> = arrayOf("SDL2")

    override fun loadLibraries() = SdlHosts.loadLibraries(spec)

    override fun getMainSharedObject(): String = spec?.program ?: super.getMainSharedObject()

    override fun getArguments(): Array<String> = spec?.arguments?.toTypedArray() ?: emptyArray()

    override fun onDestroy() {
        super.onDestroy()
        SdlHosts.endProcessIfFinishing(this)
    }
}
