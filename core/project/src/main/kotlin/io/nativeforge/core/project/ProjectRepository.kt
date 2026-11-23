package io.nativeforge.core.project

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate

/** Storage abstraction for projects. */
interface ProjectRepository {
    suspend fun listProjects(): List<Project>
    suspend fun open(name: String): Outcome<Project>
    suspend fun create(name: String, template: ProjectTemplate, options: ProjectOptions = template.defaultOptions()): Outcome<Project>
    suspend fun saveConfig(project: Project, config: ProjectConfig): Outcome<Project>
    suspend fun delete(project: Project): Outcome<Unit>

    /**
     * Folder a new project called [name] would use, or a validation error. Used to clone
     * a repository straight into the workspace.
     */
    fun directoryFor(name: String): Outcome<java.io.File>

    /**
     * Turns a folder created by [directoryFor] (for example a cloned repository) into a
     * project. A `nativeforge.json` it already contains is kept; otherwise the build system,
     * language and libraries are detected from its files.
     */
    suspend fun importDirectory(directory: java.io.File): Outcome<Project>
}
