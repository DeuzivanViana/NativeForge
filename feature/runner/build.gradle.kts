plugins {
    alias(libs.plugins.nativeforge.android.library)
}

android {
    namespace = "io.nativeforge.feature.runner"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(projects.core.project)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
}
