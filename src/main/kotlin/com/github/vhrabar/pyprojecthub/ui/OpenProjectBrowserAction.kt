package com.github.vhrabar.pyprojecthub.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** "Python Projects…" on the Welcome screen and in the Tools menu. */
class OpenProjectBrowserAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ProjectBrowserDialog(e.project).show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
