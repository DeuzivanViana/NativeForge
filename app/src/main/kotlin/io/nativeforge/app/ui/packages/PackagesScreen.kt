package io.nativeforge.app.ui.packages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.nativeforge.app.di.appContainer
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.Tag
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.LocalDiagnosticColors

@Composable
fun PackagesScreen(onBack: () -> Unit) {
    val container = appContainer()
    val viewModel: PackagesViewModel = viewModel(factory = viewModelFactory { initializer { PackagesViewModel(container) } })
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(
            title = "Packages",
            subtitle = "Libraries for your projects",
            navigation = { ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
            actions = { ToolbarIconButton(Icons.Filled.Refresh, "Refresh", viewModel::refresh) },
        )
        SearchField(state.query, viewModel::setQuery)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PackageFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(state.rows, key = { it.info.name }) { row ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Long names shrink with an ellipsis; version and tags never wrap.
                            Text(
                                row.info.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                row.info.version,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 96.dp),
                            )
                            if (row.isAndroidLibrary) {
                                Spacer(Modifier.width(6.dp))
                                Tag("Android")
                            }
                            if (row.installed) {
                                Spacer(Modifier.width(6.dp))
                                Tag("Installed", color = LocalDiagnosticColors.current.success)
                            }
                        }
                        Text(
                            row.info.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when {
                        state.busyPackage == row.info.name -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        row.installed -> TextButton(onClick = { viewModel.uninstall(row.info.name) }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                        }
                        else -> TextButton(onClick = { viewModel.install(row.info.name) }, enabled = state.busyPackage == null) {
                            Text("Install", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(34.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search (sdl3, raylib, sfml, zlib, ncurses…)", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
