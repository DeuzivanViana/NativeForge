package io.nativeforge.core.project

import io.nativeforge.core.common.NfError
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.coroutines.DefaultDispatcherProvider
import io.nativeforge.core.common.coroutines.DispatcherProvider
import io.nativeforge.core.common.outcomeOf
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import kotlinx.coroutines.withContext
import java.io.File

/** Stores each project as a directory under [workspaceRoot] containing a `nativeforge.json`. */
class FileSystemProjectRepository(
    private val workspaceRoot: File,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : ProjectRepository {

    override suspend fun listProjects(): List<Project> = withContext(dispatchers.io) {
        workspaceRoot.listFiles().orEmpty()
            .filter { File(it, ProjectConfig.FILE_NAME).isFile }
            .mapNotNull { readProject(it).getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun open(name: String): Outcome<Project> = withContext(dispatchers.io) {
        val dir = File(workspaceRoot, name)
        if (!File(dir, ProjectConfig.FILE_NAME).isFile) {
            Outcome.Failure(NfError.NotFound("Project '$name' not found"))
        } else {
            readProject(dir)
        }
    }

    override suspend fun create(name: String, template: ProjectTemplate, options: ProjectOptions): Outcome<Project> =
        withContext(dispatchers.io) {
            val validation = ProjectNameValidator.validate(name)
            if (validation != null) return@withContext Outcome.Failure(NfError.Validation(validation))

            val dir = File(workspaceRoot, name)
            if (dir.exists()) {
                return@withContext Outcome.Failure(NfError.Validation("A project named '$name' already exists"))
            }
            outcomeOf(errorFactory = { NfError.Io("Could not create project: ${it.message}", it) }) {
                val generated = template.generate(name, options)
                generated.files.forEach { (relativePath, content) ->
                    File(dir, relativePath).apply {
                        parentFile?.mkdirs()
                        writeText(content)
                    }
                }
                writeConfig(dir, generated.config)
                Project(dir, generated.config)
            }
        }

    override suspend fun saveConfig(project: Project, config: ProjectConfig): Outcome<Project> =
        withContext(dispatchers.io) {
            outcomeOf(errorFactory = { NfError.Io("Could not save project: ${it.message}", it) }) {
                writeConfig(project.root, config)
                project.copy(config = config)
            }
        }

    override suspend fun delete(project: Project): Outcome<Unit> = withContext(dispatchers.io) {
        if (project.root.canonicalFile.parentFile == workspaceRoot.canonicalFile && SafeFiles.deleteTree(project.root)) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(NfError.Io("Could not delete ${project.root}"))
        }
    }

    override fun directoryFor(name: String): Outcome<File> {
        ProjectNameValidator.validate(name)?.let { return Outcome.Failure(NfError.Validation(it)) }
        val dir = File(workspaceRoot, name)
        if (dir.exists()) return Outcome.Failure(NfError.Validation("A project named '$name' already exists"))
        return Outcome.Success(dir)
    }

    override suspend fun importDirectory(directory: File): Outcome<Project> = withContext(dispatchers.io) {
        if (directory.canonicalFile.parentFile != workspaceRoot.canonicalFile || !directory.isDirectory) {
            return@withContext Outcome.Failure(NfError.Validation("${directory.name} is not a folder of the workspace"))
        }
        outcomeOf(errorFactory = { NfError.Io("Could not import ${directory.name}: ${it.message}", it) }) {
            val existing = File(directory, ProjectConfig.FILE_NAME).takeIf { it.isFile }?.let { file ->
                runCatching { ProjectJson.decodeFromString(ProjectConfig.serializer(), file.readText()) }.getOrNull()
            }
            val config = existing?.copy(name = directory.name) ?: ProjectDetector.detect(directory, directory.name)
            writeConfig(directory, config)
            Project(directory, config)
        }
    }

    private fun readProject(dir: File): Outcome<Project> =
        outcomeOf(errorFactory = { NfError.Io("Invalid ${ProjectConfig.FILE_NAME} in ${dir.name}: ${it.message}", it) }) {
            val config = ProjectJson.decodeFromString(
                ProjectConfig.serializer(),
                File(dir, ProjectConfig.FILE_NAME).readText(),
            )
            Project(dir, config)
        }

    private fun writeConfig(dir: File, config: ProjectConfig) {
        dir.mkdirs()
        val target = File(dir, ProjectConfig.FILE_NAME)
        val temp = File(dir, ProjectConfig.FILE_NAME + ".tmp")
        temp.writeText(ProjectJson.encodeToString(ProjectConfig.serializer(), config))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }
}

object ProjectNameValidator {
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}$")

    /** Returns an error message, or null when [name] is valid. */
    fun validate(name: String): String? = when {
        name.isBlank() -> "Project name must not be empty"
        !VALID.matches(name) -> "Use letters, digits, spaces, '.', '_' or '-' (max 64 characters)"
        else -> null
    }
}
