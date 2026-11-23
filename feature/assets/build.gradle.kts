plugins {
    alias(libs.plugins.nativeforge.android.library)
    alias(libs.plugins.nativeforge.android.compose)
}

android {
    namespace = "io.nativeforge.feature.assets"
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.assets)
    implementation(libs.kotlinx.coroutines.android)
}
