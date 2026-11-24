package io.nativeforge.core.project.model

import java.io.File

/** Where a project's folder lives. */
enum class ProjectLocation {
    /** A folder inside the IDE's private workspace (`files/projects/<name>`). */
    WORKSPACE,

    /**
     * A folder elsewhere on the device (shared storage, an SD card) that the user opened.
     * The IDE never deletes it; removing the project only forgets it.
     */
    EXTERNAL,
}

/** An opened project: its root directory plus its parsed configuration. */
data class Project(
    val root: File,
    val config: ProjectConfig,
    val location: ProjectLocation = ProjectLocation.WORKSPACE,
    /**
     * Where build outputs go instead of `<root>/build`. External projects build into the
     * IDE's private storage: shared storage cannot hold executables (it is mounted noexec)
     * and the compiler wrappers in the build folder must be executable.
     */
    val buildRoot: File? = null,
) {
    val name: String get() = config.name
    val sourceDirectory: File get() = config.sourceDir.let { if (it.isBlank() || it == ".") root else File(root, it) }
    val buildDirectory: File get() = buildRoot ?: File(root, BUILD_DIR)
    val isExternal: Boolean get() = location == ProjectLocation.EXTERNAL

    /**
     * Identifies the project in navigation and [io.nativeforge.core.project.ProjectRepository.open]:
     * the folder name for workspace projects (project names cannot contain '/'), the absolute
     * path for external ones.
     */
    val key: String get() = if (isExternal) root.path else root.name

    fun buildDirectory(type: BuildType): File = File(buildDirectory, type.name.lowercase())

    companion object {
        const val SOURCE_DIR = "src"
        const val BUILD_DIR = "build"
    }
}
