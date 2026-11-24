package io.nativeforge.core.project

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.BuildType
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.ProjectLocation
import io.nativeforge.core.project.template.ProjectTemplates
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Projects in folders outside the workspace ("Open folder" and "New project › Folder on the phone"). */
class ExternalProjectsTest {

    private val appFiles: File = Files.createTempDirectory("nf-files").toFile()
    private val workspace = File(appFiles, "projects").apply { mkdirs() }
    private val shared: File = Files.createTempDirectory("nf-shared").toFile().canonicalFile
    private val repository = FileSystemProjectRepository(
        workspaceRoot = workspace,
        linkedProjectsFile = File(appFiles, "linked-projects.json"),
        externalBuildRoot = File(appFiles, "external-builds"),
    )

    private fun folderWithMakefile(name: String) = File(shared, name).apply {
        mkdirs()
        File(this, "Makefile").writeText("all:\n\tcc main.c -o app\n")
        File(this, "main.c").writeText("int main(void) { return 0; }\n")
    }

    @Test
    fun `an opened folder is detected, listed and reopened by its key`() = runBlocking<Unit> {
        val folder = folderWithMakefile("My Game (copy)")
        val opened = repository.openFolder(folder)
        assertIs<Outcome.Success<*>>(opened)
        val project = opened.getOrNull()!!
        assertEquals(ProjectLocation.EXTERNAL, project.location)
        assertEquals(BuildSystemKind.MAKE, project.config.buildSystem)
        assertTrue(File(folder, ProjectConfig.FILE_NAME).isFile, "the configuration is saved in the folder")
        assertEquals(folder.path, project.key)

        val listed = repository.listProjects().single()
        assertEquals(folder.path, listed.root.path)
        val reopened = repository.open(project.key).getOrNull()!!
        assertEquals(project.root, reopened.root)
        assertTrue(reopened.isExternal)
    }

    @Test
    fun `external projects build into private storage`() = runBlocking<Unit> {
        val project = repository.openFolder(folderWithMakefile("game")).getOrNull()!!
        val build = project.buildDirectory(BuildType.DEBUG).canonicalPath
        assertTrue(build.startsWith(File(appFiles, "external-builds").canonicalPath), build)
        assertFalse(build.startsWith(shared.path))

        // Two folders with the same name never share a build folder.
        val other = repository.openFolder(File(shared, "elsewhere").apply { mkdirs() }.let { folderWithMakefile("elsewhere/game") })
            .getOrNull()!!
        assertNotEquals(project.buildDirectory, other.buildDirectory)
    }

    @Test
    fun `an existing configuration is kept`() = runBlocking<Unit> {
        val folder = folderWithMakefile("configured")
        File(folder, ProjectConfig.FILE_NAME).writeText("""{"name":"Configured","buildSystem":"single","packages":["raylib"]}""")
        val project = repository.openFolder(folder).getOrNull()!!
        assertEquals(BuildSystemKind.SINGLE, project.config.buildSystem)
        assertEquals(listOf("raylib"), project.config.packages)
    }

    @Test
    fun `forgetting keeps the files and removes the build folder`() = runBlocking<Unit> {
        val folder = folderWithMakefile("keep me")
        val project = repository.openFolder(folder).getOrNull()!!
        project.buildDirectory.mkdirs()
        File(project.buildDirectory, "main.o").writeText("x")

        assertIs<Outcome.Failure>(repository.delete(project), "delete never removes a folder of the device")
        assertIs<Outcome.Success<*>>(repository.forget(project))
        assertTrue(File(folder, "main.c").isFile)
        assertFalse(project.buildDirectory.exists())
        assertTrue(repository.listProjects().isEmpty())
        assertIs<Outcome.Failure>(repository.open(project.key))
    }

    @Test
    fun `unknown paths and missing folders cannot be opened by key`() = runBlocking<Unit> {
        assertIs<Outcome.Failure>(repository.open(shared.path))
        val folder = folderWithMakefile("removable")
        val project = repository.openFolder(folder).getOrNull()!!
        folder.deleteRecursively()
        assertTrue(repository.listProjects().isEmpty(), "unavailable folders are hidden")
        assertIs<Outcome.Failure>(repository.open(project.key))
    }

    @Test
    fun `the workspace and its parents are refused`() = runBlocking<Unit> {
        assertIs<Outcome.Failure>(repository.openFolder(appFiles))
        assertIs<Outcome.Failure>(repository.openFolder(File(shared, "missing")))
        // A folder inside the workspace is an ordinary project.
        val inside = File(workspace, "inside").apply { mkdirs(); File(this, "main.c").writeText("int main(){}") }
        val project = repository.openFolder(inside).getOrNull()!!
        assertEquals(ProjectLocation.WORKSPACE, project.location)
    }

    @Test
    fun `new projects can be created in a folder of the device`() = runBlocking<Unit> {
        val template = ProjectTemplates.byId("lvgl")!!
        val created = repository.create("Phone app", template, template.defaultOptions(), parent = shared)
        assertIs<Outcome.Success<*>>(created)
        val project = created.getOrNull()!!
        assertTrue(project.isExternal)
        assertEquals(File(shared, "Phone app").path, project.root.path)
        assertTrue(File(project.root, "src/main.c").readText().contains("lv_nativeforge_init"))
        assertEquals(listOf(project.key), repository.listProjects().map { it.key })
        assertIs<Outcome.Failure>(repository.create("Phone app", template, template.defaultOptions(), parent = shared))
    }
}
