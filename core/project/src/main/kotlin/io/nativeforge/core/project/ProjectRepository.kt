package io.nativeforge.core.project

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate

/**
 * Storage abstraction for projects. Projects live in the IDE's private workspace or, when the
 * user opened them, in any folder of the device ([io.nativeforge.core.project.model.ProjectLocation]).
 */
interface ProjectRepository {
    /** Workspace projects and the external folders the user opened that are still readable. */
    suspend fun listProjects(): List<Project>

    /** Opens a project by its [Project.key]: a workspace folder name or an external folder's path. */
    suspend fun open(key: String): Outcome<Project>

    /**
     * Creates a project from [template]. With [parent] null it goes into the workspace;
     * otherwise into `<parent>/<name>` on the device, which is then remembered like
     * [openFolder] does.
     */
    suspend fun create(
        name: String,
        template: ProjectTemplate,
        options: ProjectOptions = template.defaultOptions(),
        parent: java.io.File? = null,
    ): Outcome<Project>

    suspend fun saveConfig(project: Project, config: ProjectConfig): Outcome<Project>

    /**
     * Deletes a workspace project's folder. External projects are never deleted by the IDE;
     * use [forget] for them.
     */
    suspend fun delete(project: Project): Outcome<Unit>

    /**
     * Opens a folder anywhere on the device as a project, where it stays: files are edited in
     * place and builds go to private storage. A `nativeforge.json` is written into the folder
     * (detected from its files when missing). The folder is remembered for [listProjects].
     */
    suspend fun openFolder(directory: java.io.File): Outcome<Project>

    /** Removes an external project from the list and deletes its private build files; its folder is untouched. */
    suspend fun forget(project: Project): Outcome<Unit>

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
