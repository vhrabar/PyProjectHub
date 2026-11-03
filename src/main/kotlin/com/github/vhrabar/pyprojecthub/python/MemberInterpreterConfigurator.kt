package com.github.vhrabar.pyprojecthub.python

import com.github.vhrabar.pyprojecthub.PyProjectHubBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.PythonSdkUtil
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Points a workspace member that was opened on its own at the workspace's shared `.venv`. Only acts when
 * the project has no Python interpreter yet; an existing configuration is never replaced.
 */
object MemberInterpreterConfigurator {
    private const val NOTIFICATION_GROUP = "PyProjectHub"

    /** What [configure] changed, so it can be undone. */
    data class Applied(
        val sdk: Sdk,
        val createdSdk: Boolean,
    )

    /**
     * Must run on the EDT. Returns null when the project already has an interpreter or the SDK couldn't be set up.
     * [createSdk] registers an interpreter PyCharm doesn't know yet; tests replace it because the headless test
     * IDE can't introspect interpreters.
     */
    fun configure(
        project: Project,
        interpreter: Path,
        createSdk: (Path) -> Sdk? = ::createAndAddSdk,
    ): Applied? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (project.isDisposed || hasPythonInterpreter(project)) return null

        val existing = findSdk(interpreter)
        val sdk =
            existing
                ?: createSdk(interpreter)
                ?: return null.also { thisLogger().warn("Could not create a Python SDK for $interpreter") }
        SdkConfigurationUtil.setDirectoryProjectSdk(project, sdk)
        return Applied(sdk, createdSdk = existing == null)
    }

    /** [configure], then tells the user which interpreter was set, with an action to undo it. */
    fun configureAndNotify(
        project: Project,
        interpreter: Path,
    ) {
        val applied = configure(project, interpreter) ?: return
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                PyProjectHubBundle["notification.interpreter.title"],
                PyProjectHubBundle.message("notification.interpreter.set", applied.sdk.name, interpreter),
                NotificationType.INFORMATION,
            ).addAction(
                NotificationAction.createSimpleExpiring(PyProjectHubBundle["notification.interpreter.undo"]) {
                    undo(project, applied)
                },
            ).notify(project)
    }

    /** Clears the interpreter [configure] set, unless the user has picked another one since. */
    fun undo(
        project: Project,
        applied: Applied,
    ) {
        if (project.isDisposed) return
        val rootManager = ProjectRootManager.getInstance(project)
        if (rootManager.projectSdk == applied.sdk) {
            ApplicationManager.getApplication().runWriteAction { rootManager.projectSdk = null }
            ModuleManager.getInstance(project).modules.forEach(ModuleRootModificationUtil::setSdkInherited)
        }
        if (applied.createdSdk && !isUsedByOpenProjects(applied.sdk)) SdkConfigurationUtil.removeSdk(applied.sdk)
    }

    private fun createAndAddSdk(interpreter: Path): Sdk? =
        SdkConfigurationUtil.createAndAddSDK(interpreter.toString(), PythonSdkType.getInstance())

    private fun hasPythonInterpreter(project: Project): Boolean =
        ProjectRootManager.getInstance(project).projectSdk != null ||
            ModuleManager.getInstance(project).modules.any { PythonSdkUtil.findPythonSdk(it) != null }

    private fun findSdk(interpreter: Path): Sdk? {
        val target = interpreter.toAbsolutePath().normalize()
        return ProjectJdkTable.getInstance().getSdksOfType(PythonSdkType.getInstance()).firstOrNull { sdk ->
            val home = sdk.homePath ?: return@firstOrNull false
            try {
                Path.of(home).toAbsolutePath().normalize() == target
            } catch (_: InvalidPathException) {
                false // remote SDKs keep non-local home paths
            }
        }
    }

    private fun isUsedByOpenProjects(sdk: Sdk): Boolean =
        ProjectManager
            .getInstance()
            .openProjects
            .any { ProjectRootManager.getInstance(it).projectSdk == sdk }
}
