package io.nativeforge.app

import android.app.Application
import io.nativeforge.app.di.AppContainer

class NativeForgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
