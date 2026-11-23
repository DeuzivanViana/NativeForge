package io.nativeforge.buildlogic

import org.gradle.api.JavaVersion

/** Single source of truth for SDK levels and JVM targets used by every module. */
object NativeForgeConfig {
    const val COMPILE_SDK = 35
    const val MIN_SDK = 26

    /**
     * The IDE targets API 28 on purpose: from API 29 on, Android forbids executing
     * binaries that live in the app's writable data directory (W^X policy), which
     * would make an on-device clang toolchain impossible. Termux uses the same
     * approach. Exported apps do not execute downloaded binaries and therefore
     * use [EXPORTED_APP_TARGET_SDK].
     */
    const val IDE_TARGET_SDK = 28
    const val EXPORTED_APP_TARGET_SDK = 35

    val JAVA_VERSION = JavaVersion.VERSION_17
}
