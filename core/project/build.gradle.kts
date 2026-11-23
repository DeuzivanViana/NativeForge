plugins {
    alias(libs.plugins.nativeforge.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.serialization.json)
}
