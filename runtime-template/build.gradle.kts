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
            isMinifyEnabled = false
            // Left unsigned: the IDE signs each exported APK with the user's key.
            signingConfig = null
        }
    }
}

dependencies {
    implementation(projects.feature.runner)
    implementation(projects.feature.terminal)
}
