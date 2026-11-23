plugins {
    alias(libs.plugins.nativeforge.jvm.library)
}

dependencies {
    api(projects.core.lsp)
    api(projects.core.build)
    implementation(libs.kotlinx.coroutines.core)
}
