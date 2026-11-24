pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux terminal libraries are published through JitPack only.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.termux.*") }
        }
    }
}

rootProject.name = "NativeForge"

// Enables `projects.core.common`-style accessors in build scripts.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Application modules
include(":app")
include(":runtime-template")

// Pure Kotlin/JVM core modules (no Android dependency, fully unit-testable)
include(":core:common")
include(":core:project")
include(":core:toolchain")
include(":core:build")
include(":core:packages")
include(":core:export")
include(":core:vcs")
include(":core:lsp")
include(":core:codeintel")
include(":core:assets")
include(":core:remote")

// Android core modules
include(":core:ui")

// Feature modules
include(":feature:editor")
include(":feature:terminal")
include(":feature:runner")
include(":feature:assets")
