package com.github.vhrabar.pyprojecthub.ui

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import java.nio.file.Path

object ProjectOpener {
    /**
     * Opens [dir] as a project, or focuses it if it's already open. Runs after the browser dialog has
     * closed, so the IDE's own "this window / new window" prompt isn't stacked on a modal dialog.
     */
    fun open(
        dir: Path,
        current: Project?,
    ) {
        ApplicationManager.getApplication().invokeLater(
            { ProjectUtil.openOrImport(dir, current, false) },
            ModalityState.nonModal(),
        )
    }
}
