plugins {
    alias(libs.plugins.nativeforge.android.library)
    alias(libs.plugins.nativeforge.android.compose)
}

android {
    namespace = "io.nativeforge.feature.terminal"
}

dependencies {
    implementation(projects.core.ui)
    implementation(libs.androidx.activity.compose)
    api(libs.termux.terminal.view)
    api(libs.termux.terminal.emulator)
}
