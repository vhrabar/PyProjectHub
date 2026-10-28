package com.github.vhrabar.pyprojecthub.ui

import com.github.vhrabar.pyprojecthub.PyProjectHubBundle
import com.github.vhrabar.pyprojecthub.discovery.ProjectScanner
import com.github.vhrabar.pyprojecthub.filter.BrowserNode
import com.github.vhrabar.pyprojecthub.filter.FilterCriteria
import com.github.vhrabar.pyprojecthub.filter.ProjectFilter
import com.github.vhrabar.pyprojecthub.filter.SortOrder
import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.github.vhrabar.pyprojecthub.model.ScanResult
import com.github.vhrabar.pyprojecthub.settings.PyProjectHubConfigurable
import com.github.vhrabar.pyprojecthub.settings.PyProjectHubSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The PyProjectHub browser: scans the configured folders in the background, then lets the user
 * search, filter and sort the projects and open one. uv workspaces are shown as groups.
 */
class ProjectBrowserDialog(
    private val currentProject: Project?,
) : DialogWrapper(currentProject, true) {
    private val settings = PyProjectHubSettings.getInstance()

    private val search = SearchTextField(false)
    private val toolCombo = ComboBox(arrayOf<PythonTool?>(null) + PythonTool.entries)
    private val pythonCombo = ComboBox(arrayOf<String?>(null) + ProjectFilter.PYTHON_VERSIONS)
    private val sortCombo = ComboBox(SortOrder.entries.toTypedArray())
    private val groupCheck = JBCheckBox(PyProjectHubBundle["browser.filter.group"])

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val details = JEditorPane()
    private val status = JBLabel()

    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private var scanResult: ScanResult? = null
    private var scanFuture: Future<*>? = null
    private var scanCancelled = AtomicBoolean(false)
    private var restoringState = false

    private val openMemberAction =
        object : DialogWrapperAction(PyProjectHubBundle["browser.open.member"]) {
            override fun doAction(e: ActionEvent) = openSelected(memberAlone = true)
        }

    init {
        title = PyProjectHubBundle["browser.title"]
        setOKButtonText(PyProjectHubBundle["browser.open"])
        init()
        restoreCriteria()
        installListeners()
        updateSelectionDependentState()
        startScan()
    }

    override fun getDimensionServiceKey(): String = "PyProjectHub.ProjectBrowser"

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun createActions(): Array<Action> = arrayOf(okAction, openMemberAction, cancelAction)

    override fun createCenterPanel(): JComponent {
        search.textEditor.emptyText.text = PyProjectHubBundle["browser.search.empty"]
        toolCombo.renderer = textRenderer { it?.displayName ?: PyProjectHubBundle["browser.filter.tool.all"] }
        pythonCombo.renderer = textRenderer { it ?: PyProjectHubBundle["browser.filter.python.any"] }
        sortCombo.renderer = textRenderer { it?.let { sort -> PyProjectHubBundle["sort.${sort.name}"] }.orEmpty() }

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = ProjectTreeCellRenderer(query = { search.text }, grouped = { groupCheck.isSelected })

        details.isEditable = false
        details.editorKit = HTMLEditorKitBuilder.simple()
        details.border = JBUI.Borders.empty(8)

        val filters =
            panel {
                row {
                    cell(search).resizableColumn().align(AlignX.FILL)
                    button(PyProjectHubBundle["browser.refresh"]) { startScan() }
                    button(PyProjectHubBundle["browser.settings"]) { openSettings() }
                }
                row {
                    label(PyProjectHubBundle["browser.filter.tool"])
                    cell(toolCombo)
                    label(PyProjectHubBundle["browser.filter.python"])
                    cell(pythonCombo)
                    label(PyProjectHubBundle["browser.filter.sort"])
                    cell(sortCombo)
                    cell(groupCheck)
                    link(PyProjectHubBundle["browser.filter.reset"]) { resetFilters() }
                }
            }

        val splitter = OnePixelSplitter(false, "PyProjectHub.ProjectBrowser.splitter", 0.5f)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
        splitter.secondComponent = ScrollPaneFactory.createScrollPane(details, true)

        status.foreground = UIUtil.getContextHelpForeground()
        status.border = JBUI.Borders.emptyTop(4)

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(filters, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
            preferredSize = JBUI.size(Dimension(960, 600))
        }
    }

    private fun installListeners() {
        search.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    searchAlarm.cancelAllRequests()
                    searchAlarm.addRequest({ rebuildTree() }, SEARCH_DELAY_MS)
                }
            },
        )
        listOf(toolCombo, pythonCombo, sortCombo).forEach { combo -> combo.addActionListener { rebuildTree() } }
        groupCheck.addActionListener { rebuildTree() }
        tree.addTreeSelectionListener { updateSelectionDependentState() }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedNode() == null) return false
                openSelected(memberAlone = false)
                return true
            }
        }.installOn(tree)
    }

    private fun criteria() =
        FilterCriteria(
            query = search.text,
            tool = toolCombo.item,
            python = pythonCombo.item,
            sort = sortCombo.item ?: SortOrder.NAME,
            grouped = groupCheck.isSelected,
        )

    private fun restoreCriteria() {
        restoringState = true
        val saved = settings.criteria
        search.text = saved.query
        toolCombo.item = saved.tool
        pythonCombo.item = saved.python
        sortCombo.item = saved.sort
        groupCheck.isSelected = saved.grouped
        restoringState = false
    }

    private fun resetFilters() {
        restoringState = true
        search.text = ""
        toolCombo.item = null
        pythonCombo.item = null
        sortCombo.item = SortOrder.NAME
        groupCheck.isSelected = true
        restoringState = false
        rebuildTree()
    }

    // region Scanning

    private fun startScan() {
        cancelScan()
        val roots = settings.scanRoots()
        if (roots.isEmpty()) {
            scanResult = null
            showNoRoots()
            return
        }
        val cancelled = AtomicBoolean(false)
        scanCancelled = cancelled
        val options = settings.scanOptions()
        status.text = PyProjectHubBundle["browser.status.scanning"]
        tree.emptyText.text = PyProjectHubBundle["browser.empty.scanning"]

        scanFuture =
            ApplicationManager.getApplication().executeOnPooledThread {
                val outcome = runCatching { ProjectScanner(options).scan(roots) { cancelled.get() } }
                // Only Swing state is touched, so any modality is fine (the dialog itself is modal)
                ApplicationManager.getApplication().invokeLater(
                    { if (!cancelled.get() && !isDisposed) onScanFinished(outcome) },
                    ModalityState.any(),
                )
            }
    }

    private fun cancelScan() {
        scanCancelled.set(true)
        scanFuture?.cancel(false)
        scanFuture = null
    }

    private fun onScanFinished(outcome: Result<ScanResult>) {
        outcome
            .onSuccess { result ->
                scanResult = result
                rebuildTree()
            }.onFailure { error ->
                thisLogger().warn("PyProjectHub scan failed", error)
                scanResult = null
                treeModel.setRoot(DefaultMutableTreeNode())
                status.text = PyProjectHubBundle.message("browser.status.failed", error.message ?: error.javaClass.simpleName)
            }
    }

    // endregion

    private fun rebuildTree() {
        if (restoringState) return
        val result = scanResult ?: return
        val previous = selectedNode()?.let { selectionKey(it) }
        val nodes = ProjectFilter.apply(result.entries, criteria(), settings.state.openedAt)

        val root = DefaultMutableTreeNode()
        for (node in nodes) {
            val treeNode = DefaultMutableTreeNode(node)
            if (node is BrowserNode.Workspace) node.members.forEach { treeNode.add(DefaultMutableTreeNode(it)) }
            root.add(treeNode)
        }
        treeModel.setRoot(root)
        TreeUtil.expandAll(tree)

        tree.emptyText.clear()
        tree.emptyText.text =
            PyProjectHubBundle[if (result.projectCount == 0) "browser.empty.noProjects" else "browser.empty.noMatches"]
        if (result.projectCount == 0) {
            tree.emptyText.appendSecondaryText(PyProjectHubBundle["browser.empty.configure"], SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                openSettings()
            }
        }

        val treeNodes = TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).toList()
        val toSelect =
            treeNodes.firstOrNull { previous != null && (it.userObject as? BrowserNode)?.let(::selectionKey) == previous }
                ?: treeNodes.firstOrNull { it.userObject is BrowserNode }
        if (toSelect != null) TreeUtil.selectNode(tree, toSelect) else updateSelectionDependentState()

        val shown = nodes.sumOf { if (it is BrowserNode.Workspace) it.members.size + (if (it.project.hasProjectTable) 1 else 0) else 1 }
        status.text =
            buildString {
                append(
                    PyProjectHubBundle.message(
                        "browser.status.result",
                        shown,
                        result.projectCount,
                        result.scannedRoots.size,
                        result.durationMillis,
                    ),
                )
                if (result.missingRoots.isNotEmpty()) {
                    append("  ·  ")
                    append(
                        PyProjectHubBundle.message(
                            "browser.status.missing",
                            result.missingRoots.joinToString(", ") {
                                FileUtil.getLocationRelativeToUserHome(it.toString())
                            },
                        ),
                    )
                }
            }
    }

    private fun showNoRoots() {
        treeModel.setRoot(DefaultMutableTreeNode())
        status.text = ""
        tree.emptyText.clear()
        tree.emptyText.text = PyProjectHubBundle["browser.empty.noRoots"]
        PyProjectHubSettings.defaultRoot?.let { default ->
            tree.emptyText.appendLine(
                PyProjectHubBundle.message("browser.empty.addDefault", FileUtil.getLocationRelativeToUserHome(default.toString())),
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) {
                settings.state.roots.add(default.toString())
                startScan()
            }
        }
        tree.emptyText.appendLine(PyProjectHubBundle["browser.empty.configure"], SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            openSettings()
        }
        updateSelectionDependentState()
    }

    private fun openSettings() {
        if (ShowSettingsUtil.getInstance().editConfigurable(contentPanel, PyProjectHubConfigurable())) startScan()
    }

    private fun selectedNode(): BrowserNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? BrowserNode

    /** Identifies a node across rebuilds: the same folder, as a member or not. */
    private fun selectionKey(node: BrowserNode): String = "${node.javaClass.simpleName}:${node.project.dir}"

    private fun updateSelectionDependentState() {
        val node = selectedNode()
        val isMember = node is BrowserNode.Project && node.workspace != null
        okAction.isEnabled = node != null
        setOKButtonText(PyProjectHubBundle[if (isMember || node is BrowserNode.Workspace) "browser.open.workspace" else "browser.open"])
        openMemberAction.isEnabled = isMember
        details.text = node?.let(ProjectDetailsHtml::render).orEmpty()
        details.caretPosition = 0
    }

    override fun doOKAction() = openSelected(memberAlone = false)

    private fun openSelected(memberAlone: Boolean) {
        val node = selectedNode() ?: return
        val target: Path =
            when (node) {
                is BrowserNode.Workspace -> {
                    node.workspace.project.dir
                }

                // A member opens its workspace root unless asked otherwise: uv.lock and the shared .venv live there
                is BrowserNode.Project -> {
                    node.workspace
                        ?.takeUnless { memberAlone }
                        ?.project
                        ?.dir ?: node.project.dir
                }
            }
        if (!Files.isDirectory(target)) {
            Messages.showErrorDialog(contentPanel, PyProjectHubBundle.message("browser.open.missing", target), title)
            startScan()
            return
        }
        settings.recordOpened(target)
        close(OK_EXIT_CODE)
        ProjectOpener.open(target, currentProject)
    }

    @TestOnly
    internal fun awaitScanForTests() {
        scanFuture?.get()
    }

    @get:TestOnly
    internal val okButtonTextForTests: String? get() = okAction.getValue(Action.NAME) as? String

    @get:TestOnly
    internal val isOpenMemberEnabledForTests: Boolean get() = openMemberAction.isEnabled

    @get:TestOnly
    internal val treeForTests: Tree get() = tree

    @get:TestOnly
    internal val searchForTests: SearchTextField get() = search

    override fun dispose() {
        cancelScan()
        if (scanResult != null || settings.scanRoots().isEmpty()) settings.criteria = criteria()
        super.dispose()
    }

    private companion object {
        const val SEARCH_DELAY_MS = 150

        // SimpleListCellRenderer.create(String, Function) is scheduled for removal in 2026.2+
        fun <T> textRenderer(text: (T?) -> String): SimpleListCellRenderer<T> =
            object : SimpleListCellRenderer<T>() {
                override fun customize(
                    list: JList<out T>,
                    value: T?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    this.text = text(value)
                }
            }
    }
}
