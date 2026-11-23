package io.nativeforge.core.project

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplates
import io.nativeforge.core.project.template.TemplateSources
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemProjectRepositoryTest {

    private val workspace: File = Files.createTempDirectory("nf-workspace").toFile()
    private val repository = FileSystemProjectRepository(workspace)

    @Test
    fun `every template creates a project that can be reopened`() = runBlocking<Unit> {
        ProjectTemplates.all.forEachIndexed { index, template ->
            val name = "Project $index"
            val created = repository.create(name, template)
            assertIs<Outcome.Success<*>>(created)

            val reopened = repository.open(name).getOrNull()!!
            assertEquals(name, reopened.config.name)
            assertTrue(reopened.root.walkTopDown().any { it.extension in setOf("c", "cpp") })
            assertTrue(reopened.root.walkTopDown().none { it.readTextOrEmpty().contains("@PROJECT_NAME@") })
        }
        assertEquals(ProjectTemplates.all.size, repository.listProjects().size)
    }

    @Test
    fun `templates generate sources for every language and build system they offer`() {
        ProjectTemplates.all.forEach { template ->
            template.languages.forEach { language ->
                template.buildSystems.forEach { buildSystem ->
                    val options = ProjectOptions(language = language, buildSystem = buildSystem)
                    val generated = template.generate("demo", options)
                    val extension = if (language == Language.C) ".c" else ".cpp"
                    assertTrue(generated.files.keys.any { it.endsWith(extension) }, "${template.id} $language")
                    assertEquals(language, generated.config.language)
                    assertEquals(buildSystem, generated.config.buildSystem)
                    assertEquals(template.runtime, generated.config.runtime)
                    if (buildSystem == BuildSystemKind.CMAKE) {
                        val cmake = generated.files.getValue("CMakeLists.txt")
                        assertTrue(("CMAKE_" + (if (language == Language.C) "C" else "CXX") + "_STANDARD") in cmake)
                        val target = if (template.runtime.producesSharedLibrary) "add_library(main SHARED" else "add_executable(main"
                        assertTrue(target in cmake, template.id)
                    }
                    if (buildSystem == BuildSystemKind.MAKE) {
                        val makefile = generated.files.getValue("Makefile")
                        assertTrue("\t\$(CC) \$(CFLAGS)" in makefile, "recipes are indented with tabs")
                        assertEquals(TemplateSources.makeArtifact(template.runtime), generated.config.artifactPath)
                    }
                }
            }
        }
    }

    @Test
    fun `unsupported options fall back to the template defaults`() {
        val sfml = ProjectTemplates.byId("sfml")!!
        val generated = sfml.generate("demo", ProjectOptions(language = Language.C, standard = "c99", buildSystem = BuildSystemKind.NINJA))
        assertEquals(Language.CPP, generated.config.language)
        assertEquals(Language.CPP.defaultStandard, generated.config.standard)
        assertEquals(BuildSystemKind.SINGLE, generated.config.buildSystem)
        assertTrue(generated.files.keys.none { it == "CMakeLists.txt" || it == "Makefile" })
    }

    @Test
    fun `duplicate and invalid names are rejected`() = runBlocking<Unit> {
        val template = ProjectTemplates.all.first()
        assertIs<Outcome.Success<*>>(repository.create("demo", template))
        assertIs<Outcome.Failure>(repository.create("demo", template))
        assertIs<Outcome.Failure>(repository.create("../escape", template))
        assertIs<Outcome.Failure>(repository.create("", template))
    }

    @Test
    fun `config round trips and unknown keys are ignored`() {
        val json = """{"name":"x","runtime":"sdl2","futureField":42}"""
        val config = ProjectJson.decodeFromString(ProjectConfig.serializer(), json)
        assertEquals(RuntimeKind.SDL2, config.runtime)
        assertEquals("io.nativeforge.user.x", config.export.packageId)
    }

    @Test
    fun `default package id is a valid java package`() {
        assertEquals("io.nativeforge.user.my_game", ProjectConfig.defaultPackageId("My Game"))
        assertEquals("io.nativeforge.user.app_3d", ProjectConfig.defaultPackageId("3D"))
        assertEquals("io.nativeforge.user.app", ProjectConfig.defaultPackageId("!!!"))
    }

    private fun File.readTextOrEmpty() = if (isFile) readText() else ""
}
