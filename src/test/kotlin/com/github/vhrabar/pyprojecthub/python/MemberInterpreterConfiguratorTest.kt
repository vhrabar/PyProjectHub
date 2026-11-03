package com.github.vhrabar.pyprojecthub.python

import com.github.vhrabar.pyprojecthub.discovery.ProjectScanner
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
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

    /** Stands in for PyCharm's SDK creation, which needs interpreter introspection the headless test IDE lacks. */
    private val createSdk: (Path) -> Sdk = { home -> registeredSdk("created", home) }

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

    fun testAnUnknownInterpreterIsRegisteredAsANewSdk() {
        val applied = MemberInterpreterConfigurator.configure(project, interpreter, createSdk)!!

        assertTrue(applied.createdSdk)
        assertEquals("created", applied.sdk.name)
        assertEquals(interpreter, Path.of(applied.sdk.homePath!!))
        assertSame(applied.sdk, ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testUndoRemovesASdkItCreated() {
        val applied = MemberInterpreterConfigurator.configure(project, interpreter, createSdk)!!

        MemberInterpreterConfigurator.undo(project, applied)

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
        assertFalse(applied.sdk in ProjectJdkTable.getInstance().allJdks)
    }

    fun testTheNotificationNamesTheInterpreterAndItsUndoWorks() {
        val sdk = registeredSdk("ws venv", interpreter)
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )

        MemberInterpreterConfigurator.configureAndNotify(project, interpreter)

        val notification = shown.single()
        assertTrue(notification.content.contains("ws venv"))
        assertTrue(notification.content.contains(interpreter.toString()))
        val undo = notification.actions.single()
        assertEquals("Undo", undo.templatePresentation.text)

        Notification.fire(notification, undo, SimpleDataContext.getSimpleContext(Notification.KEY, notification, DataContext.EMPTY_CONTEXT))

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
        assertTrue(sdk in ProjectJdkTable.getInstance().allJdks)
        assertTrue(notification.isExpired)
    }

    fun testAFailedSdkCreationChangesNothing() {
        assertNull(MemberInterpreterConfigurator.configure(project, interpreter) { null })
        assertNull(ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testNoNotificationWhenNothingWasConfigured() {
        val own = registeredSdk("own", workspace.resolve("own/bin/python"))
        setProjectSdk(own)
        var shown = 0
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown++
                }
            },
        )

        MemberInterpreterConfigurator.configureAndNotify(project, interpreter)

        assertEquals(0, shown)
    }

    fun testAFixtureWorkspaceMemberGetsTheRootVenv() {
        val fixture = Files.createDirectories(workspace.resolve("fixture"))
        Path.of("src/test/testData/workspace").toFile().copyRecursively(fixture.toFile())
        Files.writeString(Files.createDirectories(fixture.resolve(".venv/bin")).resolve("python"), "")
        Files.writeString(fixture.resolve(".venv/pyvenv.cfg"), "")
        val sdk = registeredSdk("mono venv", fixture.resolve(".venv/bin/python"))

        val result = ProjectScanner().scan(listOf(fixture))
        val mono = result.entries.filterIsInstance<UvWorkspace>().single()
        val core = mono.members.single { it.name == "core" }
        val python = WorkspaceInterpreter.locate(core.dir, mono.project.dir)
        assertEquals(fixture.resolve(".venv/bin/python"), python)

        val applied = MemberInterpreterConfigurator.configure(project, python!!)!!
        assertSame(sdk, applied.sdk)
        assertSame(sdk, ProjectRootManager.getInstance(project).projectSdk)
    }
}
