plugins {
    alias(libs.plugins.nativeforge.android.library)
}

android {
    namespace = "io.nativeforge.feature.terminal"
}

// No Compose here: the console ships inside every exported app (runtime-template).
dependencies {
    api(libs.termux.terminal.view)
    api(libs.termux.terminal.emulator)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
