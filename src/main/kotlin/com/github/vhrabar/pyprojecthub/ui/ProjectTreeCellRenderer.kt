package com.github.vhrabar.pyprojecthub.ui

import com.github.vhrabar.pyprojecthub.PyProjectHubBundle
import com.github.vhrabar.pyprojecthub.filter.BrowserNode
import com.github.vhrabar.pyprojecthub.model.PyProject
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

internal class ProjectTreeCellRenderer(
    private val query: () -> String,
    private val grouped: () -> Boolean,
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
            is BrowserNode.Workspace -> {
                val root = node.workspace.project
                icon = AllIcons.Nodes.ModuleGroup
                appendHighlighted(root.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                appendVersion(root)
                append(
                    "  " + PyProjectHubBundle.message("tree.workspace.members", node.workspace.members.size),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
                appendPath(FileUtil.getLocationRelativeToUserHome(root.dir.toString()))
            }

            is BrowserNode.Project -> {
                val project = node.project
                val workspace = node.workspace
                icon = if (workspace != null) AllIcons.Nodes.Module else AllIcons.Nodes.Folder
                appendHighlighted(project.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                appendVersion(project)
                project.primaryTool?.let { append("  ${it.displayName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                when {
                    workspace != null && grouped() -> {
                        appendPath(
                            workspace.project.dir
                                .relativize(project.dir)
                                .toString(),
                        )
                    }

                    workspace != null -> {
                        append(
                            "  " + PyProjectHubBundle.message("tree.member.of", workspace.project.displayName),
                            SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                        )
                    }

                    else -> {
                        appendPath(FileUtil.getLocationRelativeToUserHome(project.dir.toString()))
                    }
                }
                if (project.problems.isNotEmpty()) append("  ⚠", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
        }
    }

    private fun appendVersion(project: PyProject) {
        project.version?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }

    private fun appendPath(path: String) {
        append("  $path", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    /** Appends [text], marking every occurrence of a search term the way speed search does. */
    private fun appendHighlighted(
        text: String,
        attributes: SimpleTextAttributes,
    ) {
        val terms = query().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val marked = BooleanArray(text.length)
        val lower = text.lowercase()
        for (term in terms) {
            var index = lower.indexOf(term)
            while (index >= 0) {
                for (i in index until index + term.length) marked[i] = true
                index = lower.indexOf(term, index + term.length)
            }
        }
        val highlight = SimpleTextAttributes.merge(attributes, SimpleTextAttributes(SimpleTextAttributes.STYLE_SEARCH_MATCH, null))
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && marked[end] == marked[start]) end++
            append(text.substring(start, end), if (marked[start]) highlight else attributes)
            start = end
        }
    }
}
