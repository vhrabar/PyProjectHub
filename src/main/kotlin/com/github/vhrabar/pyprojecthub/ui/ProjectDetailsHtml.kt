package com.github.vhrabar.pyprojecthub.ui

import com.github.vhrabar.pyprojecthub.PyProjectHubBundle
import com.github.vhrabar.pyprojecthub.filter.BrowserNode
import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import com.github.vhrabar.pyprojecthub.parsing.PyProjectParser
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Renders the details panel for the selected node. All text is escaped through [HtmlChunk]. */
internal object ProjectDetailsHtml {
    private const val MAX_LIST_ITEMS = 25
    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

    fun render(node: BrowserNode): String {
        val project = node.project
        val memberOf = (node as? BrowserNode.Project)?.workspace
        val workspace = (node as? BrowserNode.Workspace)?.workspace

        val body = HtmlBuilder()
        body.append(
            HtmlChunk.tag("h2").children(
                HtmlChunk.text(project.displayName),
                HtmlChunk.text(project.version?.let { "  $it" }.orEmpty()).wrapWith("font").attr("color", "gray"),
            ),
        )
        project.description?.let { body.append(HtmlChunk.p().addText(it)) }

        val rows = mutableListOf<HtmlChunk>()

        fun row(
            labelKey: String,
            value: String?,
        ) {
            if (!value.isNullOrBlank()) rows += tableRow(PyProjectHubBundle[labelKey], HtmlChunk.text(value))
        }

        row("details.kind", kind(project, memberOf))
        row("details.path", project.dir.toString())
        row(
            "details.version",
            project.version?.let {
                if (it ==
                    PyProjectParser.DYNAMIC
                ) {
                    PyProjectHubBundle["details.version.dynamic"]
                } else {
                    it
                }
            },
        )
        row("details.tools", project.tools.joinToString(", ") { it.displayName })
        row("details.backend", project.buildBackend)
        row("details.python", project.requiresPython)
        row("details.license", project.license)
        row("details.authors", project.authors.joinToString(", "))
        row("details.keywords", project.keywords.joinToString(", "))
        row("details.modified", project.lastModified?.let(dateFormat::format))
        row(
            "details.venv",
            when {
                memberOf != null -> PyProjectHubBundle["details.venv.shared"]
                project.hasVenv -> PyProjectHubBundle["details.venv.present"]
                else -> PyProjectHubBundle["details.venv.absent"]
            },
        )
        if (memberOf != null) row("details.workspaceDeps", project.workspaceSources.sorted().joinToString(", "))
        if (workspace != null) {
            val decl = workspace.project.uvWorkspace
            row("details.members.patterns", decl?.members?.joinToString(", "))
            row("details.members.exclude", decl?.exclude?.joinToString(", "))
        }
        body.append(HtmlChunk.tag("table").children(rows))

        if (workspace != null) section(body, "details.members", members(workspace))
        section(body, "details.dependencies", project.dependencies)
        project.optionalDependencies.forEach { (extra, deps) ->
            section(body, "details.optional", deps, suffix = " [$extra]")
        }
        project.dependencyGroups.forEach { (group, deps) ->
            section(body, "details.groups", deps, suffix = " ($group)")
        }
        if (project.problems.isNotEmpty()) {
            body.append(HtmlChunk.tag("h3").addText(PyProjectHubBundle["details.problems"]))
            body.append(list(project.problems).wrapWith("font").attr("color", "#C94F4F"))
        }
        if (memberOf != null) {
            body.append(
                HtmlChunk
                    .p()
                    .addText(PyProjectHubBundle["details.hint.member"])
                    .wrapWith("font")
                    .attr("color", "gray"),
            )
        }
        return body.wrapWithHtmlBody().toString()
    }

    private fun kind(
        project: PyProject,
        memberOf: UvWorkspace?,
    ): String =
        when {
            memberOf != null -> PyProjectHubBundle.message("details.kind.member", memberOf.project.displayName)
            project.isVirtualWorkspaceRoot -> PyProjectHubBundle["details.kind.workspace.virtual"]
            project.isWorkspaceRoot -> PyProjectHubBundle["details.kind.workspace"]
            else -> PyProjectHubBundle["details.kind.standalone"]
        }

    private fun members(workspace: UvWorkspace): List<String> =
        workspace.members.map { member ->
            val relative = workspace.project.dir.relativize(member.dir)
            listOfNotNull(member.displayName, member.version, "($relative)").joinToString(" ")
        }

    private fun section(
        body: HtmlBuilder,
        titleKey: String,
        items: List<String>,
        suffix: String = "",
    ) {
        if (items.isEmpty()) return
        body.append(HtmlChunk.tag("h3").addText(PyProjectHubBundle[titleKey] + suffix))
        body.append(list(items))
    }

    private fun list(items: List<String>): HtmlChunk {
        val shown = items.take(MAX_LIST_ITEMS).map { HtmlChunk.li().addText(it) }
        val more =
            if (items.size >
                MAX_LIST_ITEMS
            ) {
                listOf(HtmlChunk.li().addText(PyProjectHubBundle.message("details.more", items.size - MAX_LIST_ITEMS)))
            } else {
                emptyList()
            }
        return HtmlChunk.ul().children(shown + more)
    }

    private fun tableRow(
        label: String,
        value: HtmlChunk,
    ): HtmlChunk =
        HtmlChunk.tag("tr").children(
            HtmlChunk.tag("td").attr("valign", "top").child(HtmlChunk.text(label).wrapWith("font").attr("color", "gray")),
            HtmlChunk.tag("td").child(value),
        )
}
