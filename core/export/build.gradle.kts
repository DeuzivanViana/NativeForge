plugins {
    alias(libs.plugins.nativeforge.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(projects.core.project)
    api(projects.core.toolchain)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.apksig)
    implementation(libs.bouncycastle.pkix)
}
