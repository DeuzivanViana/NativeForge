package io.nativeforge.core.project

import io.nativeforge.core.common.NfError
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.coroutines.DefaultDispatcherProvider
import io.nativeforge.core.common.coroutines.DispatcherProvider
import io.nativeforge.core.common.outcomeOf
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.ProjectLocation
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Stores each project as a directory under [workspaceRoot] containing a `nativeforge.json`,
 * plus external folders the user opened, remembered in [linkedProjectsFile]. External
 * projects build into [externalBuildRoot]`/<name>-<hash of the path>`.
 */
class FileSystemProjectRepository(
    private val workspaceRoot: File,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    private val linkedProjectsFile: File = File(workspaceRoot, ".linked-projects.json"),
    private val externalBuildRoot: File = File(workspaceRoot, ".external-builds"),
) : ProjectRepository {

    private val linkedMutex = Mutex()

    override suspend fun listProjects(): List<Project> = withContext(dispatchers.io) {
        val workspace = workspaceRoot.listFiles().orEmpty()
            .filter { File(it, ProjectConfig.FILE_NAME).isFile }
            .mapNotNull { readProject(it).getOrNull() }
        val external = linkedPaths()
            .map(::File)
            .filter { File(it, ProjectConfig.FILE_NAME).isFile }
            .mapNotNull { readProject(it, ProjectLocation.EXTERNAL).getOrNull() }
        (workspace + external).sortedBy { it.name.lowercase() }
    }

    override suspend fun open(key: String): Outcome<Project> = withContext(dispatchers.io) {
        if (key.startsWith(File.separator)) {
            val dir = File(key)
            when {
                dir.path !in linkedPaths() -> Outcome.Failure(NfError.NotFound("$key is not an opened folder"))
                !File(dir, ProjectConfig.FILE_NAME).isFile ->
                    Outcome.Failure(NfError.NotFound("$key is not available. Is the storage or SD card still there?"))
                else -> readProject(dir, ProjectLocation.EXTERNAL)
            }
        } else {
            val dir = File(workspaceRoot, key)
            if (!File(dir, ProjectConfig.FILE_NAME).isFile) {
                Outcome.Failure(NfError.NotFound("Project '$key' not found"))
            } else {
                readProject(dir)
            }
        }
    }

    override suspend fun create(name: String, template: ProjectTemplate, options: ProjectOptions, parent: File?): Outcome<Project> =
        withContext(dispatchers.io) {
            val validation = ProjectNameValidator.validate(name)
            if (validation != null) return@withContext Outcome.Failure(NfError.Validation(validation))
            if (parent != null) {
                externalFolderProblem(parent)?.let { return@withContext Outcome.Failure(NfError.Validation(it)) }
            }

            val dir = File(parent ?: workspaceRoot, name)
            if (dir.exists()) {
                return@withContext Outcome.Failure(NfError.Validation("A folder named '$name' already exists there"))
            }
            val created = outcomeOf(errorFactory = { NfError.Io("Could not create project: ${it.message}", it) }) {
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
            if (parent == null || created !is Outcome.Success) return@withContext created
            remember(dir.canonicalFile)
            readProject(dir.canonicalFile, ProjectLocation.EXTERNAL)
        }

    override suspend fun saveConfig(project: Project, config: ProjectConfig): Outcome<Project> =
        withContext(dispatchers.io) {
            outcomeOf(errorFactory = { NfError.Io("Could not save project: ${it.message}", it) }) {
                writeConfig(project.root, config)
                project.copy(config = config)
            }
        }

    override suspend fun delete(project: Project): Outcome<Unit> = withContext(dispatchers.io) {
        if (project.isExternal) {
            return@withContext Outcome.Failure(NfError.Validation("${project.name} is a folder on the device; remove it from the list instead"))
        }
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
            val config = configFor(directory)
            writeConfig(directory, config)
            Project(directory, config)
        }
    }

    override suspend fun openFolder(directory: File): Outcome<Project> = withContext(dispatchers.io) {
        val dir = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
        // A folder of the workspace is an ordinary project.
        if (dir.parentFile == workspaceRoot.canonicalFile) {
            return@withContext if (File(dir, ProjectConfig.FILE_NAME).isFile) readProject(dir) else importDirectory(dir)
        }
        externalFolderProblem(dir)?.let { return@withContext Outcome.Failure(NfError.Validation(it)) }
        val opened = outcomeOf(errorFactory = { NfError.Io("Could not open ${dir.path}: ${it.message}", it) }) {
            // An existing nativeforge.json is kept as it is (an invalid one is reported below).
            if (!File(dir, ProjectConfig.FILE_NAME).isFile) writeConfig(dir, ProjectDetector.detect(dir, dir.name))
        }
        if (opened is Outcome.Failure) return@withContext opened
        remember(dir)
        readProject(dir, ProjectLocation.EXTERNAL)
    }

    override suspend fun forget(project: Project): Outcome<Unit> = withContext(dispatchers.io) {
        if (!project.isExternal) return@withContext Outcome.Failure(NfError.Validation("${project.name} is not an opened folder"))
        linkedMutex.withLock { writeLinkedPaths(readLinkedPaths() - project.root.path) }
        SafeFiles.deleteTree(buildRootFor(project.root))
        Outcome.Success(Unit)
    }

    /** Why [dir] cannot hold an external project, or null when it can. */
    private fun externalFolderProblem(dir: File): String? {
        val canonical = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        val workspace = workspaceRoot.canonicalFile
        return when {
            !canonical.isDirectory -> "${dir.path} is not a folder, or NativeForge cannot see it. Allow access to files and try again."
            canonical == workspace || canonical.path.startsWith(workspace.path + File.separator) ->
                "${dir.path} is inside NativeForge's own project folder"
            workspace.path.startsWith(canonical.path + File.separator) -> "${dir.path} contains NativeForge's own files"
            canonical.parentFile == null -> "Choose a folder, not the root of the file system"
            !canonical.canRead() || !canonical.canWrite() ->
                "NativeForge cannot read and write ${dir.path}. Allow access to files and try again."
            else -> null
        }
    }

    /** Existing configuration of [directory] (with its folder name), or one detected from its files. */
    private fun configFor(directory: File): ProjectConfig {
        val existing = File(directory, ProjectConfig.FILE_NAME).takeIf { it.isFile }?.let { file ->
            runCatching { ProjectJson.decodeFromString(ProjectConfig.serializer(), file.readText()) }.getOrNull()
        }
        return existing?.copy(name = directory.name) ?: ProjectDetector.detect(directory, directory.name)
    }

    private suspend fun remember(dir: File) {
        linkedMutex.withLock {
            val paths = readLinkedPaths()
            if (dir.path !in paths) writeLinkedPaths(paths + dir.path)
        }
    }

    private suspend fun linkedPaths(): List<String> = linkedMutex.withLock { readLinkedPaths() }

    private fun readLinkedPaths(): List<String> = runCatching {
        ProjectJson.decodeFromString(LinkedProjects.serializer(), linkedProjectsFile.readText()).paths
    }.getOrDefault(emptyList())

    private fun writeLinkedPaths(paths: List<String>) {
        linkedProjectsFile.parentFile?.mkdirs()
        val temp = File(linkedProjectsFile.path + ".tmp")
        temp.writeText(ProjectJson.encodeToString(LinkedProjects.serializer(), LinkedProjects(paths.distinct())))
        if (!temp.renameTo(linkedProjectsFile)) {
            linkedProjectsFile.writeText(temp.readText())
            temp.delete()
        }
    }

    /** Private build folder of an external project: readable name plus a hash of the path, so equal names never clash. */
    private fun buildRootFor(root: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(root.path.toByteArray())
        val hash = digest.take(6).joinToString("") { "%02x".format(it) }
        val readable = root.name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40).ifEmpty { "project" }
        return File(externalBuildRoot, "$readable-$hash")
    }

    private fun readProject(dir: File, location: ProjectLocation = ProjectLocation.WORKSPACE): Outcome<Project> =
        outcomeOf(errorFactory = { NfError.Io("Invalid ${ProjectConfig.FILE_NAME} in ${dir.name}: ${it.message}", it) }) {
            val config = ProjectJson.decodeFromString(
                ProjectConfig.serializer(),
                File(dir, ProjectConfig.FILE_NAME).readText(),
            )
            if (location == ProjectLocation.EXTERNAL) {
                Project(dir, config, ProjectLocation.EXTERNAL, buildRootFor(dir))
            } else {
                Project(dir, config)
            }
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

/** Folders outside the workspace that the user opened, as absolute canonical paths. */
@kotlinx.serialization.Serializable
internal data class LinkedProjects(val paths: List<String> = emptyList())

object ProjectNameValidator {
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}$")

    /** Returns an error message, or null when [name] is valid. */
    fun validate(name: String): String? = when {
        name.isBlank() -> "Project name must not be empty"
        !VALID.matches(name) -> "Use letters, digits, spaces, '.', '_' or '-' (max 64 characters)"
        else -> null
    }
}
