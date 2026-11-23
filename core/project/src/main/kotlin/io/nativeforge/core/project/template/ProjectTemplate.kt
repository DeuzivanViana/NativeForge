package io.nativeforge.core.project.template

import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind

/** Files and configuration produced by a [ProjectTemplate]. Paths are relative to the project root. */
data class GeneratedProject(
    val config: ProjectConfig,
    val files: Map<String, String>,
)

/** Groups templates in the "New project" screen. */
enum class TemplateCategory(val displayName: String) {
    CONSOLE("Console"),
    GRAPHICS("Graphics"),

    /** Larger, complete programs that combine several libraries. */
    EXAMPLE("Example projects"),
}

/** Choices made in the "New project" screen. Every template accepts its own [ProjectTemplate.defaultOptions]. */
data class ProjectOptions(
    val language: Language,
    val standard: String = language.defaultStandard,
    val buildSystem: BuildSystemKind = BuildSystemKind.SINGLE,
    val orientation: ExportSettings.Orientation = ExportSettings.Orientation.UNSPECIFIED,
) {
    companion object {
        /** Language standards offered for each language; the first is the oldest. */
        fun standardsFor(language: Language): List<String> = when (language) {
            Language.C -> listOf("c99", "c11", "c17", "c23")
            Language.CPP -> listOf("c++17", "c++20", "c++23")
        }
    }
}

/**
 * A starting point for a new project. Add a template by implementing this
 * interface and registering it in [ProjectTemplates.all].
 */
interface ProjectTemplate {
    val id: String
    val displayName: String
    /** One line shown in the template list. */
    val description: String
    /** Longer text shown when the template is selected: what it shows and how it runs. */
    val details: String get() = description
    val category: TemplateCategory
    val runtime: RuntimeKind

    /** Languages the template has sources for; the first is the default. */
    val languages: List<Language>

    /** Build systems the template supports; the first is the default. */
    val buildSystems: List<BuildSystemKind> get() = listOf(BuildSystemKind.SINGLE)

    /** Package names (from the package manager) the template needs. */
    val requiredPackages: List<String> get() = emptyList()

    fun defaultOptions(): ProjectOptions = ProjectOptions(language = languages.first(), buildSystem = buildSystems.first())

    fun generate(projectName: String, options: ProjectOptions = defaultOptions()): GeneratedProject
}
