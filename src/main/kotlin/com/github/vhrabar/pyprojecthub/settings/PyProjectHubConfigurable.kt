package com.github.vhrabar.pyprojecthub.settings

import com.github.vhrabar.pyprojecthub.PyProjectHubBundle
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Settings | Tools | PyProjectHub: which folders to scan and how. */
class PyProjectHubConfigurable : Configurable {
    private val settings get() = PyProjectHubSettings.getInstance()

    private val rootsModel = CollectionListModel<String>()
    private val rootsList = JBList(rootsModel)
    private val depth = JBIntSpinner(PyProjectHubSettings.State().maxDepth, PyProjectHubSettings.MIN_DEPTH, PyProjectHubSettings.MAX_DEPTH)
    private val skipHidden = JBCheckBox(PyProjectHubBundle["settings.skipHidden"])
    private val followSymlinks = JBCheckBox(PyProjectHubBundle["settings.followSymlinks"])
    private val excludes = JBTextArea(6, 30)

    override fun getDisplayName(): String = PyProjectHubBundle["settings.displayName"]

    override fun createComponent(): JComponent {
        val roots =
            ToolbarDecorator
                .createDecorator(rootsList)
                .setAddAction { chooseRoot() }
                .disableUpDownActions()
                .createPanel()

        return panel {
            group(PyProjectHubBundle["settings.roots.group"]) {
                row {
                    cell(roots)
                        .align(Align.FILL)
                        .comment(PyProjectHubBundle["settings.roots.comment"])
                }.resizableRow()
            }
            group(PyProjectHubBundle["settings.scan.group"]) {
                row(PyProjectHubBundle["settings.depth"]) {
                    cell(depth).comment(PyProjectHubBundle["settings.depth.comment"])
                }
                row { cell(skipHidden) }
                row { cell(followSymlinks) }
                row(PyProjectHubBundle["settings.excludes"]) {
                    scrollCell(excludes)
                        .align(AlignX.FILL)
                        .comment(PyProjectHubBundle["settings.excludes.comment"])
                }
            }
        }.also { reset() }
    }

    private fun chooseRoot() {
        val descriptor =
            FileChooserDescriptorFactory
                .createSingleFolderDescriptor()
                .withTitle(PyProjectHubBundle["settings.roots.choose.title"])
        FileChooser.chooseFile(descriptor, null, rootsList, null) { folder ->
            if (folder.path !in rootsModel.items) rootsModel.add(folder.path)
        }
    }

    private fun editedExcludes(): List<String> =
        excludes.text
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)

    override fun isModified(): Boolean {
        val state = settings.state
        return rootsModel.items != state.roots ||
            depth.number != state.maxDepth ||
            skipHidden.isSelected != state.skipHidden ||
            followSymlinks.isSelected != state.followSymlinks ||
            editedExcludes() != state.excludes
    }

    override fun apply() {
        val state = settings.state
        state.roots = rootsModel.items.toMutableList()
        state.maxDepth = depth.number
        state.skipHidden = skipHidden.isSelected
        state.followSymlinks = followSymlinks.isSelected
        state.excludes = editedExcludes().toMutableList()
    }

    override fun reset() {
        val state = settings.state
        rootsModel.replaceAll(state.roots)
        depth.number = state.maxDepth
        skipHidden.isSelected = state.skipHidden
        followSymlinks.isSelected = state.followSymlinks
        excludes.text = state.excludes.joinToString("\n")
    }
}
