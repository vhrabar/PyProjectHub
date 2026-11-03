package com.github.vhrabar.pyprojecthub.filter

import com.github.vhrabar.pyprojecthub.model.ProjectEntry
import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.github.vhrabar.pyprojecthub.model.StandaloneProject
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import com.github.vhrabar.pyprojecthub.parsing.RequiresPython

enum class SortOrder {
    NAME,
    LAST_MODIFIED,
    RECENTLY_OPENED,
    PATH,
}

data class FilterCriteria(
    val query: String = "",
    val tool: PythonTool? = null,
    /** "3.12" style minor version the project must support, or null for any. */
    val python: String? = null,
    val sort: SortOrder = SortOrder.NAME,
    val grouped: Boolean = true,
)

/** What the browser tree shows: workspace groups and project rows. */
sealed interface BrowserNode {
    val project: PyProject

    data class Workspace(
        val workspace: UvWorkspace,
        val members: List<Project>,
    ) : BrowserNode {
        override val project: PyProject get() = workspace.project
    }

    data class Project(
        override val project: PyProject,
        /** The workspace this project is a member of, if any. */
        val workspace: UvWorkspace? = null,
    ) : BrowserNode
}

object ProjectFilter {
    /** A `3.X` version inside a `requires-python` specifier. */
    private val SPEC_MINOR = Regex("""(?<![\d.])3\.(\d{1,3})(?!\d)""")

    /** `Programming Language :: Python :: 3.X` trove classifiers. */
    private val CLASSIFIER_MINOR = Regex("""^Programming Language :: Python :: 3\.(\d{1,3})$""")

    /**
     * Python versions for the filter, derived only from the scanned projects: every `3.X` from the lowest
     * to the highest one mentioned in a `requires-python` or a Python version classifier, plus [selected]
     * (so a saved choice still restores). Empty when no project declares a version.
     */
    fun pythonVersions(
        entries: List<ProjectEntry>,
        selected: String? = null,
    ): List<String> {
        val minors =
            entries
                .flatMap { entry -> if (entry is UvWorkspace) listOf(entry.project) + entry.members else listOf(entry.project) }
                .flatMap { project ->
                    val fromSpec = project.requiresPython?.let { spec -> SPEC_MINOR.findAll(spec).map { it.groupValues[1] } }.orEmpty()
                    val fromClassifiers = project.classifiers.mapNotNull { CLASSIFIER_MINOR.find(it.trim())?.groupValues?.get(1) }
                    fromSpec + fromClassifiers
                }.mapNotNull(String::toIntOrNull)
        val selectedMinor = selected?.let(::minorOf)
        val all = minors + listOfNotNull(selectedMinor)
        val range = if (all.isEmpty()) emptyList() else (all.min()..all.max()).map { "3.$it" }
        // A saved value that isn't a 3.X version is kept, after the derived ones, so it still restores
        return range + listOfNotNull(selected?.takeIf { selectedMinor == null })
    }

    private fun minorOf(version: String): Int? = version.removePrefix("3.").takeIf { it != version }?.toIntOrNull()

    fun apply(
        entries: List<ProjectEntry>,
        criteria: FilterCriteria,
        openedAt: Map<String, Long> = emptyMap(),
    ): List<BrowserNode> {
        val order = comparator(criteria.sort, openedAt)
        return if (criteria.grouped) grouped(entries, criteria, order) else flat(entries, criteria, order)
    }

    private fun grouped(
        entries: List<ProjectEntry>,
        criteria: FilterCriteria,
        order: Comparator<PyProject>,
    ): List<BrowserNode> =
        entries
            .mapNotNull { entry ->
                when (entry) {
                    is StandaloneProject -> {
                        if (matches(entry.project, criteria)) BrowserNode.Project(entry.project) else null
                    }

                    is UvWorkspace -> {
                        val rootMatches = matches(entry.project, criteria)
                        val matchingMembers = entry.members.filter { matches(it, criteria) }
                        // Searching for the workspace itself shows all of its members
                        val shown = if (rootMatches && matchingMembers.isEmpty()) entry.members else matchingMembers
                        if (!rootMatches && shown.isEmpty()) {
                            null
                        } else {
                            BrowserNode.Workspace(entry, shown.sortedWith(order).map { BrowserNode.Project(it, entry) })
                        }
                    }
                }
            }.sortedWith(compareBy(order) { it.project })

    private fun flat(
        entries: List<ProjectEntry>,
        criteria: FilterCriteria,
        order: Comparator<PyProject>,
    ): List<BrowserNode> =
        entries
            .flatMap { entry ->
                when (entry) {
                    is StandaloneProject -> {
                        listOf(BrowserNode.Project(entry.project))
                    }

                    is UvWorkspace -> {
                        listOfNotNull(entry.project.takeIf { it.hasProjectTable }?.let { BrowserNode.Project(it) }) +
                            entry.members.map { BrowserNode.Project(it, entry) }
                    }
                }
            }.filter { matches(it.project, criteria) }
            .sortedWith(compareBy(order) { it.project })

    fun matches(
        project: PyProject,
        criteria: FilterCriteria,
    ): Boolean {
        if (criteria.tool != null && criteria.tool !in project.tools) return false
        if (criteria.python != null && !supportsPython(project, criteria.python)) return false
        val terms =
            criteria.query
                .lowercase()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)
        if (terms.isEmpty()) return true
        val haystack = searchText(project)
        return terms.all { it in haystack }
    }

    /** Projects without a usable `requires-python` are assumed to support any version. */
    fun supportsPython(
        project: PyProject,
        version: String,
    ): Boolean {
        val spec = project.requiresPython ?: return true
        val (major, minor) = version.split('.').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return true
        return RequiresPython.parse(spec)?.allowsMinor(major, minor) ?: true
    }

    private fun searchText(project: PyProject): String =
        buildList {
            add(project.displayName)
            add(project.description.orEmpty())
            add(project.dir.toString())
            addAll(project.keywords)
            addAll(project.authors)
            addAll(project.tools.map { it.displayName })
            addAll(project.allDependencies.map(::requirementName))
        }.joinToString("\n").lowercase()

    /** `"requests[socks]>=2.0 ; python_version<'3.12'"` -> `"requests"` */
    fun requirementName(requirement: String): String = requirement.trim().takeWhile { it.isLetterOrDigit() || it in "._-" }

    private fun comparator(
        sort: SortOrder,
        openedAt: Map<String, Long>,
    ): Comparator<PyProject> {
        val byName = compareBy<PyProject> { it.displayName.lowercase() }
        return when (sort) {
            SortOrder.NAME -> byName
            SortOrder.PATH -> compareBy { it.dir.toString() }
            SortOrder.LAST_MODIFIED -> compareByDescending<PyProject> { it.lastModified }.then(byName)
            SortOrder.RECENTLY_OPENED -> compareByDescending<PyProject> { openedAt[it.dir.toString()] ?: 0L }.then(byName)
        }
    }
}
