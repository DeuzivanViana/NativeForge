package io.nativeforge.app.ui.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nativeforge.app.di.AppContainer
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.packages.model.PackageInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PackageRow(val info: PackageInfo, val installed: Boolean) {
    /** Libraries built for Android graphics (SDL3, raylib, SFML...), as opposed to Termux console packages. */
    val isAndroidLibrary: Boolean get() = info.sourceId.startsWith("nativeforge")
}

enum class PackageFilter(val label: String) { ALL("All"), ANDROID("Android graphics"), TERMUX("Termux"), INSTALLED("Installed") }

data class PackagesUiState(
    val query: String = "",
    val filter: PackageFilter = PackageFilter.ALL,
    val rows: List<PackageRow> = emptyList(),
    val loading: Boolean = false,
    val busyPackage: String? = null,
    val status: String? = null,
)

class PackagesViewModel(private val container: AppContainer) : ViewModel() {
    private val manager = container.packageManager
    private val ui = MutableStateFlow(PackagesUiState())

    val state: StateFlow<PackagesUiState> = combine(ui, manager.catalog, manager.installed) { uiState, catalog, installed ->
        val installedNames = installed.map { it.info.name }.toSet()
        val query = uiState.query.trim().lowercase()
        val rows = catalog
            .map { PackageRow(it, it.name in installedNames) }
            .filter { query.isEmpty() || query in it.info.name.lowercase() || query in it.info.description.lowercase() }
            .filter { row ->
                when (uiState.filter) {
                    PackageFilter.ALL -> true
                    PackageFilter.ANDROID -> row.isAndroidLibrary
                    PackageFilter.TERMUX -> !row.isAndroidLibrary
                    PackageFilter.INSTALLED -> row.installed
                }
            }
            // Installed first, then the bundled Android graphics libraries, then by name.
            .sortedWith(compareBy({ !it.installed }, { !it.isAndroidLibrary }, { it.info.name }))
            .take(MAX_ROWS)
        uiState.copy(rows = rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackagesUiState())

    init {
        viewModelScope.launch { manager.loadInstalled() }
        if (!manager.allSourcesLoaded) refresh()
    }

    fun setQuery(query: String) = ui.update { it.copy(query = query) }

    fun setFilter(filter: PackageFilter) = ui.update { it.copy(filter = filter) }

    fun refresh() {
        viewModelScope.launch {
            ui.update { it.copy(loading = true) }
            val result = manager.refresh()
            ui.update { it.copy(loading = false, status = (result as? Outcome.Failure)?.error?.message) }
        }
    }

    fun install(name: String) {
        viewModelScope.launch {
            ui.update { it.copy(busyPackage = name) }
            manager.install(listOf(name)).collect { event ->
                val status = when (event) {
                    is InstallEvent.Resolved -> "Installing ${event.packages.joinToString { it.name }}"
                    is InstallEvent.Extracting -> "Extracting ${event.pkg.name}…"
                    is InstallEvent.Failed -> event.error.message
                    InstallEvent.Completed -> "$name installed"
                    else -> null
                }
                if (status != null) ui.update { it.copy(status = status) }
            }
            ui.update { it.copy(busyPackage = null) }
        }
    }

    fun uninstall(name: String) {
        viewModelScope.launch {
            ui.update { it.copy(busyPackage = name) }
            val result = manager.uninstall(name)
            ui.update { it.copy(busyPackage = null, status = (result as? Outcome.Failure)?.error?.message ?: "$name removed") }
        }
    }

    private companion object {
        const val MAX_ROWS = 300
    }
}
