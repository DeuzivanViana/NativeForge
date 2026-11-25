package io.nativeforge.app

import android.app.Application
import io.nativeforge.app.crash.CrashReporter
import io.nativeforge.app.di.AppContainer
import io.nativeforge.feature.assets.PreviewCache

class NativeForgeApplication : Application() {
    /**
     * Built on first use, not in [onCreate]: graphical programs run in the `:runner` process,
     * which also creates this Application, and never need the IDE's services. Their start no
     * longer waits for the package manager, signing keys and the rest.
     */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // First, so that a crash anywhere later is recorded (IDE process only).
        CrashReporter.install(this)
    }

    /** Gives cached image previews back when Android runs short of memory. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PreviewCache.trim(level)
    }
}
