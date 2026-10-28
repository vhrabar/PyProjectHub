package com.github.vhrabar.pyprojecthub.settings

import com.github.vhrabar.pyprojecthub.discovery.ScanOptions
import com.github.vhrabar.pyprojecthub.filter.FilterCriteria
import com.github.vhrabar.pyprojecthub.filter.SortOrder
import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Application-level settings and browser state. Nothing sensitive is stored here. */
@Service(Service.Level.APP)
@State(name = "PyProjectHubSettings", storages = [Storage("pyprojecthub.xml")])
class PyProjectHubSettings : PersistentStateComponent<PyProjectHubSettings.State> {
    class State {
        var roots: MutableList<String> = mutableListOf()
        var maxDepth: Int = ScanOptions().maxDepth
        var excludes: MutableList<String> = ScanOptions.DEFAULT_EXCLUDES.toMutableList()
        var skipHidden: Boolean = true
        var followSymlinks: Boolean = false

        var query: String = ""
        var tool: String? = null
        var python: String? = null
        var sort: String = SortOrder.NAME.name
        var grouped: Boolean = true

        /** Project folder -> epoch millis of the last open through PyProjectHub, for "Recently opened" sorting. */
        var openedAt: MutableMap<String, Long> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun scanRoots(): List<Path> = state.roots.mapNotNull(::toPath)

    fun scanOptions(): ScanOptions =
        ScanOptions(
            maxDepth = state.maxDepth.coerceIn(MIN_DEPTH, MAX_DEPTH),
            excludes = state.excludes,
            skipHidden = state.skipHidden,
            followSymlinks = state.followSymlinks,
        )

    var criteria: FilterCriteria
        get() =
            FilterCriteria(
                query = state.query,
                tool = PythonTool.entries.firstOrNull { it.name == state.tool },
                python = state.python,
                sort = SortOrder.entries.firstOrNull { it.name == state.sort } ?: SortOrder.NAME,
                grouped = state.grouped,
            )
        set(value) {
            state.query = value.query
            state.tool = value.tool?.name
            state.python = value.python
            state.sort = value.sort.name
            state.grouped = value.grouped
        }

    fun recordOpened(dir: Path) {
        state.openedAt[dir.toString()] = System.currentTimeMillis()
        // Keep the map bounded
        if (state.openedAt.size > MAX_OPENED_ENTRIES) {
            state.openedAt.entries
                .sortedBy { it.value }
                .take(state.openedAt.size - MAX_OPENED_ENTRIES)
                .forEach { state.openedAt.remove(it.key) }
        }
    }

    companion object {
        const val MIN_DEPTH = 1
        const val MAX_DEPTH = 12
        private const val MAX_OPENED_ENTRIES = 500

        fun getInstance(): PyProjectHubSettings = service()

        /** Folder suggested on first use, when it exists: PyCharm's default location for new projects. */
        val defaultRoot: Path? =
            toPath("~/PycharmProjects")?.takeIf { Files.isDirectory(it) }

        fun toPath(text: String): Path? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val expanded = if (trimmed == "~" || trimmed.startsWith("~/")) System.getProperty("user.home") + trimmed.drop(1) else trimmed
            return try {
                Path.of(expanded)
            } catch (_: InvalidPathException) {
                null
            }
        }
    }
}
