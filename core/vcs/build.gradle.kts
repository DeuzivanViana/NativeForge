plugins {
    alias(libs.plugins.nativeforge.jvm.library)
}

dependencies {
    api(projects.core.common)
    implementation(libs.jgit)
    // JGit logs through SLF4J; the no-op binding keeps it quiet.
    runtimeOnly(libs.slf4j.nop)
}
