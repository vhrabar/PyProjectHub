package com.github.vhrabar.pyprojecthub

import com.github.vhrabar.pyprojecthub.filter.BrowserNode
import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import com.github.vhrabar.pyprojecthub.model.UvWorkspaceDecl
import com.github.vhrabar.pyprojecthub.settings.PyProjectHubSettings
import com.github.vhrabar.pyprojecthub.ui.OpenProjectBrowserAction
import com.github.vhrabar.pyprojecthub.ui.ProjectDetailsHtml
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class PyProjectHubPluginTest : BasePlatformTestCase() {
    fun testPluginIsLoaded() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.github.vhrabar.pyprojecthub"))
        assertNotNull("PyProjectHub should be loaded in the test IDE", plugin)
        assertTrue(PluginManagerCore.isLoaded(plugin!!.pluginId))
    }

    fun testBundleMessageResolves() {
        assertEquals("PyProjectHub", PyProjectHubBundle["name"])
    }

    fun testActionIsOnTheWelcomeScreenAndInTheToolsMenu() {
        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction("PyProjectHub.OpenBrowser")
        assertInstanceOf(action, OpenProjectBrowserAction::class.java)
        assertEquals("Python Projects…", action.templatePresentation.text)
        for (group in listOf("WelcomeScreen.QuickStart", "ToolsMenu")) {
            val children = (actionManager.getAction(group) as DefaultActionGroup).getChildren(actionManager)
            assertTrue("$group should contain the action", action in children)
        }
    }

    fun testSettingsRoundTripCriteria() {
        val settings = PyProjectHubSettings.getInstance()
        val original = settings.criteria
        try {
            settings.criteria = original.copy(query = "fastapi", python = "3.12", grouped = false)
            assertEquals("fastapi", settings.criteria.query)
            assertEquals("3.12", settings.criteria.python)
            assertFalse(settings.criteria.grouped)
        } finally {
            settings.criteria = original
        }
    }

    fun testDetailsEscapeUntrustedMetadata() {
        val root =
            PyProject(
                Path.of("/w/mono"),
                hasProjectTable = true,
                name = "mono",
                uvWorkspace = UvWorkspaceDecl(listOf("packages/*"), emptyList()),
            )
        val member =
            PyProject(Path.of("/w/mono/packages/core"), hasProjectTable = true, name = "core", description = "<script>alert(1)</script>")
        val html = ProjectDetailsHtml.render(BrowserNode.Project(member, UvWorkspace(root, listOf(member))))

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("Member of uv workspace mono"))
    }
}
