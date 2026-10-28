package com.github.vhrabar.pyprojecthub.ui

import com.github.vhrabar.pyprojecthub.filter.BrowserNode
import com.github.vhrabar.pyprojecthub.settings.PyProjectHubSettings
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SearchTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

/** Drives the real dialog (not shown) against a temp folder tree inside the headless test IDE. */
class ProjectBrowserDialogTest : BasePlatformTestCase() {
    private lateinit var workDir: Path
    private lateinit var savedState: PyProjectHubSettings.State
    private lateinit var dialog: ProjectBrowserDialog

    override fun setUp() {
        super.setUp()
        workDir = Files.createTempDirectory("pyprojecthub-ui")
        write("mono", "[project]\nname = \"mono\"\n[tool.uv.workspace]\nmembers = [\"packages/*\"]\n")
        write("mono/packages/core", "[project]\nname = \"core\"\nversion = \"0.3.1\"\nrequires-python = \">=3.12\"\n")
        write("mono/packages/cli", "[project]\nname = \"cli\"\ndependencies = [\"typer\"]\n")
        write("scraper", "[tool.poetry]\nname = \"scraper\"\n[build-system]\nbuild-backend = \"poetry.core.masonry.api\"\n")

        val settings = PyProjectHubSettings.getInstance()
        savedState = settings.state
        settings.loadState(PyProjectHubSettings.State().apply { roots = mutableListOf(workDir.toString()) })
        dialog = ProjectBrowserDialog(null)
    }

    override fun tearDown() {
        try {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            PyProjectHubSettings.getInstance().loadState(savedState)
            workDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun write(
        relative: String,
        toml: String,
    ) {
        val dir = workDir.resolve(relative)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("pyproject.toml"), toml)
    }

    private val tree: Tree get() = dialog.treeForTests

    private val search: SearchTextField get() = dialog.searchForTests

    private fun awaitScan() {
        dialog.awaitScanForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun shownRows(): List<String> =
        TreeUtil
            .treeNodeTraverser(tree.model.root as DefaultMutableTreeNode)
            .filter(DefaultMutableTreeNode::class.java)
            .mapNotNull { node ->
                when (val value = node.userObject) {
                    is BrowserNode.Workspace -> value.project.displayName
                    is BrowserNode.Project -> (if (value.workspace != null) "  " else "") + value.project.displayName
                    else -> null
                }
            }.toList()

    private fun select(name: String) {
        val node =
            TreeUtil
                .treeNodeTraverser(tree.model.root as DefaultMutableTreeNode)
                .filter(DefaultMutableTreeNode::class.java)
                .first { (it.userObject as? BrowserNode)?.project?.displayName == name }
        TreeUtil.selectNode(tree, node)
    }

    fun testScanFillsTheTreeWithWorkspacesAndProjects() {
        awaitScan()

        assertEquals(listOf("mono", "  cli", "  core", "scraper"), shownRows())
    }

    fun testSelectingAMemberOffersOpenWorkspaceAndOpenMemberAlone() {
        awaitScan()

        select("core")
        assertEquals("Open Workspace", dialog.okButtonTextForTests)
        assertTrue(dialog.isOpenMemberEnabledForTests)
        assertTrue(dialog.isOKActionEnabled)

        select("scraper")
        assertEquals("Open", dialog.okButtonTextForTests)
        assertFalse(dialog.isOpenMemberEnabledForTests)
    }

    fun testSearchFiltersTheTree() {
        awaitScan()

        search.text = "typer"
        // The search is debounced on the Swing thread
        PlatformTestUtil.waitWithEventsDispatching("search did not apply", { shownRows() == listOf("mono", "  cli") }, 5)

        search.text = ""
        PlatformTestUtil.waitWithEventsDispatching("search was not cleared", { shownRows().size == 4 }, 5)
    }
}
