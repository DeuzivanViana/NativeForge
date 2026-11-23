package io.nativeforge.core.project.model

import java.io.File

/** An opened project: its root directory plus its parsed configuration. */
data class Project(
    val root: File,
    val config: ProjectConfig,
) {
    val name: String get() = config.name
    val sourceDirectory: File get() = config.sourceDir.let { if (it.isBlank() || it == ".") root else File(root, it) }
    val buildDirectory: File get() = File(root, BUILD_DIR)

    fun buildDirectory(type: BuildType): File = File(buildDirectory, type.name.lowercase())

    companion object {
        const val SOURCE_DIR = "src"
        const val BUILD_DIR = "build"
    }
}
