package com.github.vhrabar.pyprojecthub.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.sdk.PythonSdkType
import java.nio.file.Files
import java.nio.file.Path

class MemberInterpreterConfiguratorTest : BasePlatformTestCase() {
    private lateinit var workspace: Path
    private lateinit var interpreter: Path
    private var previousProjectSdk: Sdk? = null
    private val addedSdks = mutableListOf<Sdk>()

    override fun setUp() {
        super.setUp()
        workspace = Files.createTempDirectory("pyprojecthub-ws")
        interpreter = Files.createDirectories(workspace.resolve(".venv/bin")).resolve("python")
        Files.writeString(interpreter, "")
        previousProjectSdk = ProjectRootManager.getInstance(project).projectSdk
        setProjectSdk(null)
    }

    override fun tearDown() {
        try {
            setProjectSdk(previousProjectSdk)
            ModuleRootModificationUtil.setSdkInherited(module)
            val table = ProjectJdkTable.getInstance()
            addedSdks.filter { it in table.allJdks }.forEach(SdkConfigurationUtil::removeSdk)
            workspace.toFile().deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun setProjectSdk(sdk: Sdk?) =
        ApplicationManager.getApplication().runWriteAction { ProjectRootManager.getInstance(project).projectSdk = sdk }

    private fun registeredSdk(
        name: String,
        home: Path,
    ): Sdk {
        val sdk = ProjectJdkTable.getInstance().createSdk(name, PythonSdkType.getInstance())
        sdk.sdkModificator.apply { homePath = home.toString() }.also {
            ApplicationManager.getApplication().runWriteAction { it.commitChanges() }
        }
        SdkConfigurationUtil.addSdk(sdk)
        addedSdks += sdk
        return sdk
    }

    fun testAKnownInterpreterIsReusedAndSetOnTheProject() {
        val sdk = registeredSdk("ws venv", interpreter)
        val applied = MemberInterpreterConfigurator.configure(project, interpreter)

        assertNotNull(applied)
        assertSame(sdk, applied!!.sdk)
        assertFalse(applied.createdSdk)
        assertSame(sdk, ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testAnExistingProjectInterpreterIsNotReplaced() {
        val own = registeredSdk("own", workspace.resolve("own/bin/python"))
        registeredSdk("ws venv", interpreter)
        setProjectSdk(own)

        assertNull(MemberInterpreterConfigurator.configure(project, interpreter))
        assertSame(own, ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testAnExistingModuleInterpreterIsNotReplaced() {
        val own = registeredSdk("own", workspace.resolve("own/bin/python"))
        registeredSdk("ws venv", interpreter)
        ModuleRootModificationUtil.setModuleSdk(module, own)

        assertNull(MemberInterpreterConfigurator.configure(project, interpreter))
        assertNull(ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testUndoClearsTheInterpreterAndKeepsAReusedSdk() {
        val sdk = registeredSdk("ws venv", interpreter)
        val applied = MemberInterpreterConfigurator.configure(project, interpreter)!!

        MemberInterpreterConfigurator.undo(project, applied)

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
        assertTrue(sdk in ProjectJdkTable.getInstance().allJdks)
    }

    fun testUndoKeepsAnInterpreterTheUserPickedSince() {
        registeredSdk("ws venv", interpreter)
        val applied = MemberInterpreterConfigurator.configure(project, interpreter)!!
        val picked = registeredSdk("picked", workspace.resolve("picked/bin/python"))
        setProjectSdk(picked)

        MemberInterpreterConfigurator.undo(project, applied)

        assertSame(picked, ProjectRootManager.getInstance(project).projectSdk)
    }
}
