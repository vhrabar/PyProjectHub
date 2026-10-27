package com.github.vhrabar.pyprojecthub.discovery

import com.github.vhrabar.pyprojecthub.model.ProjectEntry
import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.github.vhrabar.pyprojecthub.model.StandaloneProject
import com.github.vhrabar.pyprojecthub.model.UvWorkspace
import com.github.vhrabar.pyprojecthub.model.UvWorkspaceDecl
import com.github.vhrabar.pyprojecthub.parsing.ProjectMarkers
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Groups discovered projects into uv workspaces the way uv itself resolves them:
 * a root is a `pyproject.toml` with `[tool.uv.workspace]`; its members are the folders matched by
 * the `members` globs (relative to the root) and not matched by `exclude`.
 */
internal class WorkspaceResolver(
    private val scanner: ProjectScanner,
) {
    fun resolve(
        found: MutableMap<Path, PyProject>,
        isCancelled: () -> Boolean,
    ): List<ProjectEntry> {
        // Bottom-up: a member found first (or a root outside the scan folders) still reaches its root
        for (project in found.values.toList()) {
            if (isCancelled()) return emptyList()
            if (!project.isWorkspaceRoot) findRootAbove(project.dir)?.let { found.putIfAbsent(it.dir, it) }
        }

        val roots = found.values.filter { it.isWorkspaceRoot }

        // Top-down: members deeper than the scan depth are still listed under their root
        for (root in roots) {
            val decl = root.uvWorkspace ?: continue
            scanner.walk(root.dir, memberDepth(decl), isCancelled) { dir ->
                if (dir != root.dir && claims(root, dir)) found.getOrPut(dir) { scanner.read(dir) }
            }
        }
        if (isCancelled()) return emptyList()

        val members = roots.associateWith { mutableListOf<PyProject>() }
        val claimed = mutableSetOf<Path>()
        for (project in found.values) {
            if (project.isWorkspaceRoot) continue
            // uv doesn't nest workspaces; if roots do overlap, the nearest one wins
            val owner = roots.filter { claims(it, project.dir) }.maxByOrNull { it.dir.nameCount } ?: continue
            members.getValue(owner) += project.copy(tools = project.tools + PythonTool.UV)
            claimed.add(project.dir) // not +=: Path is itself an Iterable<Path>
        }

        val workspaces =
            roots.map { root ->
                UvWorkspace(
                    project = root.copy(tools = root.tools + PythonTool.UV),
                    members = members.getValue(root).sortedBy { it.displayName.lowercase() },
                )
            }
        val standalone = found.values.filter { !it.isWorkspaceRoot && it.dir !in claimed }.map(::StandaloneProject)
        return workspaces + standalone
    }

    private fun findRootAbove(dir: Path): PyProject? {
        var current = dir.parent
        var levels = 0
        while (current != null && levels++ < MAX_LEVELS_UP) {
            if (Files.isRegularFile(current.resolve(ProjectMarkers.PYPROJECT))) {
                val candidate = scanner.read(current)
                // Like uv, stop at the first workspace root above; it either claims the project or nobody does
                if (candidate.isWorkspaceRoot) return candidate.takeIf { claims(it, dir) }
            }
            current = current.parent
        }
        return null
    }

    companion object {
        private const val MAX_LEVELS_UP = 32
        private const val RECURSIVE_GLOB_DEPTH = 8

        fun claims(
            root: PyProject,
            dir: Path,
        ): Boolean {
            val decl = root.uvWorkspace ?: return false
            if (dir == root.dir || !dir.startsWith(root.dir)) return false
            val relative = root.dir.relativize(dir)
            return decl.members.any { glob(it).matches(relative) } && decl.exclude.none { glob(it).matches(relative) }
        }

        fun memberDepth(decl: UvWorkspaceDecl): Int =
            decl.members.maxOfOrNull { pattern ->
                if ("**" in pattern) RECURSIVE_GLOB_DEPTH else pattern.trim('/').split('/').size
            } ?: 0

        private fun glob(pattern: String) =
            FileSystems.getDefault().getPathMatcher("glob:${pattern.trim().removePrefix("./").trimEnd('/')}")
    }
}
