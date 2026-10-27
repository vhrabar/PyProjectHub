package com.github.vhrabar.pyprojecthub.discovery

import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.github.vhrabar.pyprojecthub.model.StandaloneProject
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ProjectScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root: Path get() = temp.root.toPath()

    private fun project(
        relative: String,
        toml: String = "[project]\nname = \"${relative.substringAfterLast('/')}\"\n",
    ): Path {
        val dir = root.resolve(relative)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("pyproject.toml"), toml)
        return dir
    }

    private fun workspace(
        relative: String,
        members: String,
        exclude: String = "",
        withProject: Boolean = true,
    ): Path =
        project(
            relative,
            buildString {
                if (withProject) append("[project]\nname = \"${relative.substringAfterLast('/')}\"\n")
                append("[tool.uv.workspace]\nmembers = [$members]\n")
                if (exclude.isNotEmpty()) append("exclude = [$exclude]\n")
            },
        )

    private fun scan(
        options: ScanOptions = ScanOptions(),
        roots: List<Path> = listOf(root),
    ) = ProjectScanner(options).scan(roots)

    private fun names(result: com.github.vhrabar.pyprojecthub.model.ScanResult) =
        result.entries
            .filterIsInstance<StandaloneProject>()
            .map { it.project.displayName }
            .sorted()

    @Test
    fun findsStandaloneProjectsAndSkipsExcludedAndHiddenFolders() {
        project("alpha")
        project("group/beta")
        project("alpha/.venv/lib/site-packages/pkg")
        project("node_modules/thing")
        project(".hidden/secret")
        project("gamma.egg-info")

        assertEquals(listOf("alpha", "beta"), names(scan()))
    }

    @Test
    fun respectsMaxDepth() {
        project("a/b/c/deep")

        assertEquals(emptyList<String>(), names(scan(ScanOptions(maxDepth = 3))))
        assertEquals(listOf("deep"), names(scan(ScanOptions(maxDepth = 4))))
    }

    @Test
    fun groupsUvWorkspaceMembersAndHonoursExclude() {
        workspace("mono", "\"packages/*\", \"apps/cli\"", exclude = "\"packages/legacy\"")
        project("mono/packages/core")
        project("mono/packages/utils")
        project("mono/packages/legacy")
        project("mono/apps/cli")
        project("mono/scripts/tooling") // under the root but matched by no glob

        val result = scan()
        val ws = result.entries.filterIsInstance<UvWorkspace>().single()

        assertEquals("mono", ws.project.displayName)
        assertEquals(listOf("cli", "core", "utils"), ws.members.map { it.displayName })
        assertTrue(ws.members.all { PythonTool.UV in it.tools })
        assertEquals(listOf("legacy", "tooling"), names(result))
        assertEquals(6, result.projectCount)
    }

    @Test
    fun virtualRootIsNotCountedAsAProject() {
        workspace("virtual", "\"libs/*\"", withProject = false)
        project("virtual/libs/one")

        val result = scan()
        val ws = result.entries.filterIsInstance<UvWorkspace>().single()

        assertTrue(ws.project.isVirtualWorkspaceRoot)
        assertEquals(listOf("one"), ws.members.map { it.displayName })
        assertEquals(1, result.projectCount)
    }

    @Test
    fun membersDeeperThanScanDepthAreStillFound() {
        workspace("mono", "\"packages/*/*\"")
        project("mono/packages/team-a/service")

        val ws = scan(ScanOptions(maxDepth = 1)).entries.filterIsInstance<UvWorkspace>().single()

        assertEquals(listOf("service"), ws.members.map { it.displayName })
    }

    @Test
    fun memberFindsItsRootOutsideTheScanFolder() {
        workspace("mono", "\"packages/*\"")
        val member = project("mono/packages/core")

        val ws = scan(roots = listOf(member)).entries.filterIsInstance<UvWorkspace>().single()

        assertEquals(root.resolve("mono"), ws.project.dir)
        assertEquals(listOf("core"), ws.members.map { it.displayName })
    }

    @Test
    fun nearestRootWinsWhenRootsOverlap() {
        workspace("outer", "\"**\"")
        workspace("outer/inner", "\"pkgs/*\"")
        project("outer/inner/pkgs/leaf")

        val workspaces = scan().entries.filterIsInstance<UvWorkspace>().associateBy { it.project.displayName }

        assertEquals(listOf("leaf"), workspaces.getValue("inner").members.map { it.displayName })
        assertTrue(workspaces.getValue("outer").members.none { it.displayName == "leaf" })
    }

    @Test
    fun reportsMissingRootsAndUnreadableFiles() {
        val broken = project("broken", "[project]\nname = \"broken\"\n")
        Files.writeString(broken.resolve("pyproject.toml"), "[project\nname=")
        val missing = root.resolve("does-not-exist")

        val result = scan(roots = listOf(root, missing))

        assertEquals(listOf(missing), result.missingRoots)
        val project = result.entries.single().project
        assertTrue(project.problems.isNotEmpty())
    }

    @Test
    fun cancellationStopsTheScan() {
        repeat(20) { project("p$it") }

        val result = ProjectScanner().scan(listOf(root)) { true }

        assertTrue(result.cancelled)
        assertTrue(result.entries.isEmpty())
    }
}
