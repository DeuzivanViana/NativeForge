plugins {
    alias(libs.plugins.nativeforge.jvm.library)
}

dependencies {
    api(projects.core.common)
    api(projects.core.project)
    api(projects.core.toolchain)
}
