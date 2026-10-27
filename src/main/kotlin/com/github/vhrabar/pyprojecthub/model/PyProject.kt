package com.github.vhrabar.pyprojecthub.model

import java.nio.file.Path
import java.time.Instant

/** Packaging / project-management tools a project is set up for, in display priority order. */
enum class PythonTool(
    val displayName: String,
) {
    UV("uv"),
    POETRY("Poetry"),
    PDM("PDM"),
    HATCH("Hatch"),
    FLIT("Flit"),
    MATURIN("Maturin"),
    SETUPTOOLS("setuptools"),
}

/** The `[tool.uv.workspace]` table of a workspace root. */
data class UvWorkspaceDecl(
    val members: List<String>,
    val exclude: List<String>,
)

/** Metadata read from one `pyproject.toml`. Pure data: no platform types, no file handles. */
data class PyProject(
    val dir: Path,
    val hasProjectTable: Boolean = false,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val requiresPython: String? = null,
    val authors: List<String> = emptyList(),
    val license: String? = null,
    val keywords: List<String> = emptyList(),
    val classifiers: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val optionalDependencies: Map<String, List<String>> = emptyMap(),
    val dependencyGroups: Map<String, List<String>> = emptyMap(),
    val buildBackend: String? = null,
    val tools: Set<PythonTool> = emptySet(),
    val dynamic: Set<String> = emptySet(),
    val uvWorkspace: UvWorkspaceDecl? = null,
    /** Names of `[tool.uv.sources]` entries marked `workspace = true`. */
    val workspaceSources: Set<String> = emptySet(),
    val hasVenv: Boolean = false,
    val lastModified: Instant? = null,
    val problems: List<String> = emptyList(),
) {
    val displayName: String
        get() = name ?: dir.fileName?.toString() ?: dir.toString()

    val isWorkspaceRoot: Boolean
        get() = uvWorkspace != null

    /** A workspace root without a `[project]` table only groups its members. */
    val isVirtualWorkspaceRoot: Boolean
        get() = isWorkspaceRoot && !hasProjectTable

    val primaryTool: PythonTool?
        get() = PythonTool.entries.firstOrNull { it in tools }

    /** Every dependency requirement, including optional ones and dependency groups. */
    val allDependencies: List<String>
        get() = dependencies + optionalDependencies.values.flatten() + dependencyGroups.values.flatten()
}

/** A top-level row of the scan result: either a standalone project or a uv workspace with its members. */
sealed interface ProjectEntry {
    val project: PyProject
}

data class StandaloneProject(
    override val project: PyProject,
) : ProjectEntry

data class UvWorkspace(
    override val project: PyProject,
    val members: List<PyProject>,
) : ProjectEntry

data class ScanResult(
    val entries: List<ProjectEntry>,
    val scannedRoots: List<Path>,
    val missingRoots: List<Path> = emptyList(),
    val durationMillis: Long = 0,
    val cancelled: Boolean = false,
) {
    /** Openable packages: standalone projects, non-virtual workspace roots and workspace members. */
    val projectCount: Int
        get() =
            entries.sumOf { entry ->
                when (entry) {
                    is StandaloneProject -> 1
                    is UvWorkspace -> entry.members.size + if (entry.project.hasProjectTable) 1 else 0
                }
            }
}
