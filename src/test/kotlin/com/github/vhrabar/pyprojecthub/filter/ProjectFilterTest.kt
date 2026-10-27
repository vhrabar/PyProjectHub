package com.github.vhrabar.pyprojecthub.filter

import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.github.vhrabar.pyprojecthub.model.StandaloneProject
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import com.github.vhrabar.pyprojecthub.model.UvWorkspaceDecl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class ProjectFilterTest {
    private fun project(
        name: String,
        dir: String = "/w/$name",
        tools: Set<PythonTool> = emptySet(),
        python: String? = null,
        deps: List<String> = emptyList(),
        modified: Long = 0,
        description: String? = null,
    ) = PyProject(
        dir = Path.of(dir),
        hasProjectTable = true,
        name = name,
        tools = tools,
        requiresPython = python,
        dependencies = deps,
        lastModified = Instant.ofEpochSecond(modified),
        description = description,
    )

    private val core = project("core", "/w/mono/packages/core", setOf(PythonTool.UV), ">=3.12", listOf("httpx>=0.27"), modified = 30)
    private val cli = project("cli", "/w/mono/apps/cli", setOf(PythonTool.UV), ">=3.10", listOf("typer"), modified = 10)
    private val mono =
        UvWorkspace(
            project(
                "mono",
                "/w/mono",
                setOf(PythonTool.UV),
            ).copy(uvWorkspace = UvWorkspaceDecl(listOf("packages/*", "apps/*"), emptyList())),
            listOf(cli, core),
        )
    private val scraper =
        StandaloneProject(
            project("scraper", tools = setOf(PythonTool.POETRY), python = "^3.9", deps = listOf("requests[socks]>=2"), modified = 20),
        )
    private val entries = listOf(scraper, mono)

    private fun render(nodes: List<BrowserNode>): List<String> =
        nodes.flatMap { node ->
            when (node) {
                is BrowserNode.Workspace -> listOf(node.project.displayName) + node.members.map { "  " + it.project.displayName }
                is BrowserNode.Project -> listOf(node.project.displayName + (node.workspace?.let { " @${it.project.displayName}" } ?: ""))
            }
        }

    @Test
    fun groupedShowsWorkspacesWithMembersSortedByName() =
        assertEquals(listOf("mono", "  cli", "  core", "scraper"), render(ProjectFilter.apply(entries, FilterCriteria())))

    @Test
    fun searchMatchingAMemberKeepsItsWorkspace() =
        assertEquals(listOf("mono", "  core"), render(ProjectFilter.apply(entries, FilterCriteria(query = "httpx"))))

    @Test
    fun searchMatchingTheRootShowsAllMembers() =
        assertEquals(listOf("mono", "  cli", "  core"), render(ProjectFilter.apply(entries, FilterCriteria(query = "mono"))))

    @Test
    fun allTermsMustMatch() =
        assertEquals(listOf("scraper"), render(ProjectFilter.apply(entries, FilterCriteria(query = "poetry requests"))))

    @Test
    fun toolFilter() = assertEquals(listOf("scraper"), render(ProjectFilter.apply(entries, FilterCriteria(tool = PythonTool.POETRY))))

    @Test
    fun pythonFilterHidesUnsupportedMembers() =
        assertEquals(listOf("mono", "  cli", "scraper"), render(ProjectFilter.apply(entries, FilterCriteria(python = "3.11"))))

    @Test
    fun flatModeListsMembersWithTheirWorkspace() =
        assertEquals(
            listOf("cli @mono", "core @mono", "mono", "scraper"),
            render(ProjectFilter.apply(entries, FilterCriteria(grouped = false))),
        )

    @Test
    fun sortByLastModified() =
        assertEquals(
            listOf("core @mono", "scraper", "cli @mono", "mono"),
            render(ProjectFilter.apply(entries, FilterCriteria(grouped = false, sort = SortOrder.LAST_MODIFIED))),
        )

    @Test
    fun sortByRecentlyOpened() =
        assertEquals(
            listOf("scraper", "cli @mono", "core @mono", "mono"),
            render(
                ProjectFilter.apply(entries, FilterCriteria(grouped = false, sort = SortOrder.RECENTLY_OPENED), mapOf("/w/scraper" to 5L)),
            ),
        )

    @Test
    fun requirementNames() {
        assertEquals("requests", ProjectFilter.requirementName("requests[socks]>=2 ; python_version<'3.12'"))
        assertEquals("zope.interface", ProjectFilter.requirementName("zope.interface"))
        assertEquals("my_pkg-x", ProjectFilter.requirementName(" my_pkg-x ==1.0"))
    }
}
