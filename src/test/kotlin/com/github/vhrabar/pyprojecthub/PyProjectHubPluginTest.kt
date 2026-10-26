package com.github.vhrabar.pyprojecthub

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PyProjectHubPluginTest : BasePlatformTestCase() {
    fun testPluginIsLoaded() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.github.vhrabar.pyprojecthub"))
        assertNotNull("PyProjectHub should be loaded in the test IDE", plugin)
        assertTrue(PluginManagerCore.isLoaded(plugin!!.pluginId))
    }
}
