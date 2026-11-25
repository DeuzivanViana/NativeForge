import io.nativeforge.buildlogic.NativeForgeConfig

// Minimal app that hosts an exported program. Its release APK is embedded in the
// IDE and patched on the device (package id, label, version, native libraries).
plugins {
    alias(libs.plugins.nativeforge.android.application)
}

android {
    namespace = "io.nativeforge.runtime.template"

    defaultConfig {
        // Must match io.nativeforge.core.export.ApkExporter.TEMPLATE_PACKAGE.
        applicationId = "io.nativeforge.runtime.template"
        // Exported apps never execute downloaded binaries, so they can target a current API level.
        targetSdk = NativeForgeConfig.EXPORTED_APP_TARGET_SDK
        versionCode = 1
        versionName = "1.0"
    }

    lint {
        // The template is a binary input of the IDE, not a shipped app on its own.
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            // Every exported app carries this dex: shrink it to what the hosts use.
            isMinifyEnabled = true
            // Resource file names stay readable (android.enableResourceOptimizations=false).
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned: the IDE signs each exported APK with the user's key.
            signingConfig = null
        }
    }

    packaging {
        resources.excludes += setOf(
            // Project templates and examples from core:project (feature:runner depends on it for
            // the launch spec model); the IDE needs them, exported apps never do.
            "io/nativeforge/core/project/templates/**",
            // Kotlin and coroutines metadata for reflection and debuggers.
            "kotlin/**",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "META-INF/versions/**",
        )
        // feature:runner's crash recorder is for the IDE process only.
        jniLibs.excludes += "**/libnativeforge_crash.so"
    }
}

dependencies {
    implementation(projects.feature.runner)
    implementation(projects.feature.terminal)
}
