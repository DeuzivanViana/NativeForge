package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.FieldLabel
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.Tag
import io.nativeforge.core.ui.component.ToolbarIconButton

/**
 * Chooses the libraries a project links against (any number, e.g. raylib +
 * raygui + box2d + lua) and extra compiler/linker flags. Dependencies such as
 * sdl3 for sdl3-image are added and linked automatically.
 */
@Composable
fun LibrariesDialog(
    config: ProjectConfig,
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadCatalog() }
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<String>().apply { addAll(config.packages) } }
    var query by rememberSaveable { mutableStateOf("") }
    var compilerFlags by rememberSaveable { mutableStateOf(config.compilerFlags.joinToString(" ")) }
    var linkerFlags by rememberSaveable { mutableStateOf(config.linkerFlags.joinToString(" ")) }
    val byName = catalog.associateBy { it.name }

    val candidates = catalog
        .filter { it.name !in selected }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.description.contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareBy<PackageInfo>({ !it.isBundled() }, { it.name }))
        .take(if (query.isBlank()) 40 else 80)

    NfDialog(
        title = "Project libraries",
        subtitle = "Link any number of libraries; their dependencies come along",
        onDismiss = onDismiss,
        maxWidth = 640.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton("Save", onClick = {
                viewModel.updateLibraries(selected.toList(), compilerFlags.splitFlags(), linkerFlags.splitFlags())
                onDismiss()
            })
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                FieldLabel("In this project (${selected.size})")
                if (selected.isEmpty()) {
                    Text("No libraries yet. Add some below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                selected.forEach { name ->
                    val info = byName[name]
                    LibraryRow(name, info, trailing = { ToolbarIconButton(Icons.Filled.Close, "Remove $name", { selected.remove(name) }) })
                    info?.depends?.map { it.substringBefore(' ') }?.filter { it.isNotBlank() && it !in selected && byName[it]?.isBundled() == true }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { deps ->
                            Text(
                                "   also links ${deps.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }
            Column {
                FieldLabel("Add a library")
                NfTextField(value = query, onValueChange = { query = it }, placeholder = "Search: box2d, sdl3-image, lua, glm, ncurses…")
                Spacer(Modifier.size(6.dp))
                if (catalog.isEmpty()) {
                    Text("Loading the package list…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                candidates.forEach { info ->
                    LibraryRow(info.name, info, onClick = { selected.add(info.name) }, trailing = {
                        Icon(Icons.Filled.Add, contentDescription = "Add ${info.name}", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    })
                }
            }
            NfTextField(
                value = compilerFlags,
                onValueChange = { compilerFlags = it },
                label = "Extra compiler flags",
                placeholder = "-Wall -DDEBUG_DRAW",
                monospace = true,
            )
            NfTextField(
                value = linkerFlags,
                onValueChange = { linkerFlags = it },
                label = "Extra linker flags",
                placeholder = "-lncursesw -lz",
                monospace = true,
                supportingText = "Termux libraries need their -l flag here; bundled Android libraries link automatically.",
            )
        }
    }
}

@Composable
private fun LibraryRow(
    name: String,
    info: PackageInfo?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.heightIn(min = 44.dp).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (info != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(info.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(6.dp))
                        if (info.isBundled()) Tag("Android") else Tag("Termux", color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (info != null) {
                    Text(info.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
    }
}

private fun PackageInfo.isBundled() = sourceId.startsWith("nativeforge")

private fun String.splitFlags(): List<String> = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
