plugins {
    alias(libs.plugins.nativeforge.jvm.library)
}

dependencies {
    api(projects.core.common)
    api(projects.core.project)
    api(projects.core.build)
    api(projects.core.codeintel)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.pkix)
}
