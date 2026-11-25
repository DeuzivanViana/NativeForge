package io.nativeforge.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nativeforge.core.project.ProjectNameValidator
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import io.nativeforge.core.project.template.ProjectTemplates
import io.nativeforge.core.project.template.TemplateCategory
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.FieldLabel
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.SegmentedChoice
import io.nativeforge.core.ui.component.Tag

/**
 * "New project": name, location, template (grouped by category) and the options the template
 * supports. [location] is a folder of the device chosen through [onChooseLocation], or null for
 * NativeForge's own storage.
 */
@Composable
fun NewProjectDialog(
    existingNames: Set<String>,
    location: java.io.File?,
    onChooseLocation: () -> Unit,
    onResetLocation: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, ProjectTemplate, ProjectOptions, java.io.File?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var templateId by rememberSaveable { mutableStateOf(ProjectTemplates.all.first().id) }
    val template = ProjectTemplates.byId(templateId) ?: ProjectTemplates.all.first()
    var languageName by rememberSaveable(templateId) { mutableStateOf(template.languages.first().name) }
    val language = Language.valueOf(languageName)
    var standard by rememberSaveable(templateId, languageName) { mutableStateOf(language.defaultStandard) }
    var buildSystemName by rememberSaveable(templateId) { mutableStateOf(template.buildSystems.first().name) }
    var orientationName by rememberSaveable { mutableStateOf(ExportSettings.Orientation.UNSPECIFIED.name) }

    val options = ProjectOptions(
        language = language,
        standard = standard,
        buildSystem = BuildSystemKind.valueOf(buildSystemName),
        orientation = ExportSettings.Orientation.valueOf(orientationName),
    )
    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> null
        location == null && trimmed in existingNames -> "A project with this name already exists"
        location != null && java.io.File(location, trimmed).exists() -> "This folder already has a '$trimmed' folder"
        else -> ProjectNameValidator.validate(trimmed)
    }
    val files = remember(template, options) { template.generate(trimmed.ifEmpty { "project" }, options).files.keys.sorted() }

    NfDialog(
        title = "New project",
        subtitle = "Pick a template, a language and how it builds",
        onDismiss = onDismiss,
        maxWidth = 640.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton(
                "Create",
                onClick = { onCreate(trimmed, template, options, location) },
                enabled = trimmed.isNotEmpty() && nameError == null,
            )
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NfTextField(
                value = name,
                onValueChange = { name = it },
                label = "Project name",
                placeholder = "my_game",
                error = nameError,
                supportingText = "Letters, digits, '-' and '_'. Saved in " +
                    (location?.path?.let { "$it/" } ?: "NativeForge's projects/") + trimmed.ifEmpty { "…" },
            )

            Column {
                FieldLabel("Location")
                SegmentedChoice(
                    options = listOf(false, true),
                    selected = location != null,
                    onSelect = { onDevice -> if (onDevice) onChooseLocation() else onResetLocation() },
                    label = { if (it) "Folder on the phone" else "NativeForge" },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (location == null) {
                    Hint("Private to the app: removed when NativeForge is uninstalled.")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint(
                            "Other apps (file managers, Termux, git) see it, and it stays after uninstalling.",
                            Modifier.weight(1f),
                        )
                        DialogSecondaryButton("Change", onChooseLocation)
                    }
                }
            }

            TemplateCategory.entries.forEach { category ->
                val templates = ProjectTemplates.all.filter { it.category == category }
                if (templates.isEmpty()) return@forEach
                Column {
                    FieldLabel(category.displayName)
                    if (category == TemplateCategory.EXAMPLE) {
                        Hint("Complete programs that combine several libraries. Start from one and make it yours.")
                        Spacer(Modifier.height(6.dp))
                        templates.forEach { item ->
                            ExampleCard(item, selected = item.id == templateId, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                templateId = item.id
                            }
                        }
                        return@Column
                    }
                    templates.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                TemplateCard(item, selected = item.id == templateId, modifier = Modifier.weight(1f)) {
                                    templateId = item.id
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            TemplateDetails(template, files)

            Column {
                FieldLabel("Language")
                SegmentedChoice(
                    options = listOf(Language.C, Language.CPP),
                    selected = language,
                    onSelect = { languageName = it.name },
                    label = { if (it == Language.C) "C" else "C++" },
                    enabled = { it in template.languages },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (template.languages.size == 1) {
                    Hint("${template.displayName} is ${if (template.languages.first() == Language.C) "C" else "C++"} only.")
                }
            }
            Column {
                FieldLabel("Standard")
                SegmentedChoice(
                    options = ProjectOptions.standardsFor(language),
                    selected = standard,
                    onSelect = { standard = it },
                    label = { it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column {
                FieldLabel("Build system")
                SegmentedChoice(
                    options = template.buildSystems,
                    selected = BuildSystemKind.valueOf(buildSystemName),
                    onSelect = { buildSystemName = it.name },
                    label = { it.displayName },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    when (BuildSystemKind.valueOf(buildSystemName)) {
                        BuildSystemKind.SINGLE -> "NativeForge compiles every file in src/; nothing to configure."
                        BuildSystemKind.CMAKE -> "A CMakeLists.txt you can extend; built with Ninja or Makefiles (Build settings)."
                        BuildSystemKind.MAKE -> "A plain Makefile run by GNU Make. Edit the rules freely."
                        BuildSystemKind.NINJA -> "Runs your own build.ninja."
                    },
                )
            }
            if (template.runtime.producesSharedLibrary) {
                Column {
                    FieldLabel("Screen orientation")
                    val choices = listOf(
                        ExportSettings.Orientation.UNSPECIFIED,
                        ExportSettings.Orientation.SENSOR_PORTRAIT,
                        ExportSettings.Orientation.SENSOR_LANDSCAPE,
                    )
                    SegmentedChoice(
                        options = choices,
                        selected = ExportSettings.Orientation.valueOf(orientationName),
                        onSelect = { orientationName = it.name },
                        label = {
                            when (it) {
                                ExportSettings.Orientation.SENSOR_PORTRAIT -> "Portrait"
                                ExportSettings.Orientation.SENSOR_LANDSCAPE -> "Landscape"
                                else -> "Auto"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Used when running and when exporting; change it later in Export APK.")
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: ProjectTemplate, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier.heightIn(min = 76.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(templateIcon(template), contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(template.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                template.description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                template.languages.forEach { Tag(if (it == Language.C) "C" else "C++", color = colors.secondary) }
            }
        }
    }
}

/** Full-width card for an example project: description and the libraries it combines. */
@Composable
private fun ExampleCard(template: ProjectTemplate, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.primary.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(templateIcon(template), contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(template.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(template.description, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    template.requiredPackages.forEach { Tag(it, color = colors.tertiary) }
                }
            }
        }
    }
}

@Composable
private fun TemplateDetails(template: ProjectTemplate, files: List<String>) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(template.details, style = MaterialTheme.typography.bodySmall)
            DetailLine("Runs", runsIn(template.runtime))
            DetailLine("Libraries", template.requiredPackages.joinToString().ifEmpty { "None" } +
                if (template.requiredPackages.isNotEmpty()) " (bundled, installed on first build)" else "")
            DetailLine("Files", files.joinToString())
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 4.dp))
}

private fun runsIn(runtime: RuntimeKind): String = when (runtime) {
    RuntimeKind.CONSOLE -> "In the built-in terminal"
    RuntimeKind.SDL3 -> "Full screen, in SDL 3's activity"
    RuntimeKind.NATIVE_ACTIVITY -> "Full screen, in a NativeActivity"
    RuntimeKind.SDL2 -> "Full screen, in SDL 2's activity"
}

private fun templateIcon(template: ProjectTemplate): ImageVector = when (template.id) {
    "console" -> Icons.Filled.Terminal
    "raylib" -> Icons.Filled.SportsEsports
    "sdl3", "sdl2" -> Icons.Filled.Layers
    "imgui" -> Icons.Filled.Dashboard
    "vulkan" -> Icons.Filled.Bolt
    "sfml" -> Icons.Filled.Brush
    "opengles" -> Icons.Filled.Gradient
    "raylib-physics" -> Icons.Filled.Category
    "raylib-3d-physics" -> Icons.Filled.ViewInAr
    "voxel-sandbox" -> Icons.Filled.Terrain
    "lvgl" -> Icons.Filled.Widgets
    "lvgl-widgets-demo" -> Icons.Filled.Apps
    "lvgl-tasks" -> Icons.Filled.Checklist
    "lvgl-calculator" -> Icons.Filled.Calculate
    "gles-cube" -> Icons.Filled._3dRotation
    "sdl3-synth" -> Icons.Filled.Piano
    "ecs-particles" -> Icons.Filled.AutoAwesome
    "lua-console" -> Icons.Filled.Code
    "sqlite-todo" -> Icons.Filled.Storage
    "json-contacts" -> Icons.Filled.Contacts
    else -> Icons.Filled.Terminal
}
