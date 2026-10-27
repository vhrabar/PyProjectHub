package com.github.vhrabar.pyprojecthub.parsing

import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.PythonTool
import com.github.vhrabar.pyprojecthub.model.UvWorkspaceDecl
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.nio.file.Path
import java.time.Instant

/** Files next to `pyproject.toml` that hint at the tooling or environment of a project. */
object ProjectMarkers {
    const val PYPROJECT = "pyproject.toml"
    const val UV_LOCK = "uv.lock"
    const val POETRY_LOCK = "poetry.lock"
    const val PDM_LOCK = "pdm.lock"
    const val SETUP_PY = "setup.py"
    const val VENV = ".venv"

    val ALL = listOf(UV_LOCK, POETRY_LOCK, PDM_LOCK, SETUP_PY, VENV)
}

/**
 * Reads PEP 621 `[project]` metadata (with a fallback to legacy `[tool.poetry]`), the build backend,
 * tool tables and the uv workspace declaration. Never throws: syntax errors and unexpected value
 * types end up in [PyProject.problems] next to whatever could still be read.
 */
class PyProjectParser {
    fun parse(
        dir: Path,
        text: String,
        markers: Set<String> = emptySet(),
        lastModified: Instant? = null,
    ): PyProject {
        val result = Toml.parse(text)
        val problems = result.errors().map { "Line ${it.position().line()}: ${it.message}" }.toMutableList()

        val project = result.table("project")
        val tool = result.table("tool")
        val poetry = tool?.table("poetry")
        val uv = tool?.table("uv")
        val backend = result.table("build-system")?.string("build-backend")
        val dynamic = project?.strings("dynamic").orEmpty().toSet()

        val name = project?.string("name") ?: poetry?.string("name")
        val version = project?.string("version") ?: poetry?.string("version")
        if (project != null && project.value("name") != null && project.string("name") == null) {
            problems += "[project].name is not a string"
        }

        return PyProject(
            dir = dir,
            hasProjectTable = project != null,
            name = name,
            version = version ?: if ("version" in dynamic) DYNAMIC else null,
            description = project?.string("description") ?: poetry?.string("description"),
            requiresPython = project?.string("requires-python") ?: poetry?.table("dependencies")?.string("python"),
            authors = project?.let(::pep621Authors) ?: poetry?.strings("authors").orEmpty(),
            license = project?.let(::license) ?: poetry?.string("license"),
            keywords = project?.strings("keywords") ?: poetry?.strings("keywords").orEmpty(),
            classifiers = project?.strings("classifiers") ?: poetry?.strings("classifiers").orEmpty(),
            dependencies = project?.strings("dependencies") ?: poetry?.let(::poetryDependencies).orEmpty(),
            optionalDependencies = project?.table("optional-dependencies")?.let(::stringLists).orEmpty(),
            dependencyGroups = result.table("dependency-groups")?.let(::dependencyGroups).orEmpty(),
            buildBackend = backend,
            tools = detectTools(markers, tool?.keySet().orEmpty(), backend),
            dynamic = dynamic,
            uvWorkspace =
                uv?.table("workspace")?.let {
                    UvWorkspaceDecl(members = it.strings("members"), exclude = it.strings("exclude"))
                },
            workspaceSources =
                uv
                    ?.table("sources")
                    ?.let { sources ->
                        sources.keySet().filter { sources.table(it)?.value("workspace") == true }
                    }.orEmpty()
                    .toSet(),
            hasVenv = ProjectMarkers.VENV in markers,
            lastModified = lastModified,
            problems = problems,
        )
    }

    private fun pep621Authors(project: TomlTable): List<String>? {
        val authors = project.value("authors") as? TomlArray ?: return null
        return authors.toList().mapNotNull { author ->
            val table = author as? TomlTable ?: return@mapNotNull null
            val name = table.string("name")
            val email = table.string("email")
            when {
                name != null && email != null -> "$name <$email>"
                else -> name ?: email
            }
        }
    }

    private fun license(project: TomlTable): String? =
        when (val license = project.value("license")) {
            is String -> {
                license
            }

            is TomlTable -> {
                license
                    .string("text")
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.trim() ?: license.string("file")?.let { "See $it" }
            }

            else -> {
                null
            }
        }

    private fun poetryDependencies(poetry: TomlTable): List<String> {
        val deps = poetry.table("dependencies") ?: return emptyList()
        return deps.keySet().filter { !it.equals("python", ignoreCase = true) }.map { name ->
            when (val spec = deps.value(name)) {
                is String -> if (spec == "*") name else "$name $spec"
                is TomlTable -> spec.string("version")?.let { "$name $it" } ?: name
                else -> name
            }
        }
    }

    private fun stringLists(table: TomlTable): Map<String, List<String>> = table.keySet().associateWith { table.strings(it) }

    private fun dependencyGroups(table: TomlTable): Map<String, List<String>> =
        table.keySet().associateWith { group ->
            (table.value(group) as? TomlArray)?.toList().orEmpty().mapNotNull { item ->
                when (item) {
                    is String -> item
                    is TomlTable -> item.string("include-group")?.let { "(includes $it)" }
                    else -> null
                }
            }
        }

    companion object {
        const val DYNAMIC = "dynamic"

        fun detectTools(
            markers: Set<String>,
            toolTables: Set<String>,
            backend: String?,
        ): Set<PythonTool> {
            fun backend(prefix: String) = backend?.startsWith(prefix) == true
            return buildSet {
                if (ProjectMarkers.UV_LOCK in markers || "uv" in toolTables || backend("uv_build")) add(PythonTool.UV)
                if (ProjectMarkers.POETRY_LOCK in markers || "poetry" in toolTables || backend("poetry")) add(PythonTool.POETRY)
                if (ProjectMarkers.PDM_LOCK in markers || "pdm" in toolTables || backend("pdm")) add(PythonTool.PDM)
                if ("hatch" in toolTables || backend("hatchling")) add(PythonTool.HATCH)
                if ("flit" in toolTables || backend("flit")) add(PythonTool.FLIT)
                if ("maturin" in toolTables || backend("maturin")) add(PythonTool.MATURIN)
                if (ProjectMarkers.SETUP_PY in markers || "setuptools" in toolTables || backend("setuptools")) add(PythonTool.SETUPTOOLS)
            }
        }
    }
}

// Single-segment lookups: keys such as "requires-python" or quoted package names must not be split on dots.
private fun TomlTable.value(key: String): Any? = get(listOf(key))

private fun TomlTable.string(key: String): String? = value(key) as? String

private fun TomlTable.table(key: String): TomlTable? = value(key) as? TomlTable

private fun TomlTable.strings(key: String): List<String> = (value(key) as? TomlArray)?.toList()?.filterIsInstance<String>().orEmpty()
