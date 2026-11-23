plugins {
    alias(libs.plugins.nativeforge.android.library)
    alias(libs.plugins.nativeforge.android.compose)
}

android {
    namespace = "io.nativeforge.core.ui"
}

dependencies {
    api(libs.compose.material.icons.extended)
}
