package com.github.vhrabar.pyprojecthub.parsing

import com.github.vhrabar.pyprojecthub.model.PythonTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PyProjectParserTest {
    private val parser = PyProjectParser()
    private val dir = Path.of("/work/project")

    private fun fixture(name: String): String = Files.readString(Path.of("src/test/testData/pyproject/$name"))

    @Test
    fun readsPep621Metadata() {
        val project = parser.parse(dir, fixture("pep621-uv.toml"), setOf(ProjectMarkers.UV_LOCK, ProjectMarkers.VENV))

        assertEquals("acme-api", project.name)
        assertEquals("1.4.0", project.version)
        assertEquals("HTTP API for Acme", project.description)
        assertEquals(">=3.11", project.requiresPython)
        assertEquals(listOf("Ada <ada@example.com>", "Grace"), project.authors)
        assertEquals("MIT", project.license)
        assertEquals(listOf("api", "http"), project.keywords)
        assertEquals(2, project.dependencies.size)
        assertEquals(mapOf("docs" to listOf("mkdocs")), project.optionalDependencies)
        assertEquals(listOf("pytest>=8", "(includes lint)"), project.dependencyGroups["dev"])
        assertEquals("hatchling.build", project.buildBackend)
        assertEquals(setOf(PythonTool.UV, PythonTool.HATCH), project.tools)
        assertEquals(PythonTool.UV, project.primaryTool)
        assertTrue(project.hasVenv)
        assertTrue(project.hasProjectTable)
        assertFalse(project.isWorkspaceRoot)
        assertTrue(project.problems.isEmpty())
    }

    @Test
    fun fallsBackToLegacyPoetryTable() {
        val project = parser.parse(dir, fixture("poetry-legacy.toml"), setOf(ProjectMarkers.POETRY_LOCK))

        assertFalse(project.hasProjectTable)
        assertEquals("legacy-scraper", project.name)
        assertEquals("0.9.2", project.version)
        assertEquals("^3.9", project.requiresPython)
        assertEquals(listOf("Blackwell <blackwell@example.com>"), project.authors)
        assertEquals(listOf("requests ^2.31", "lxml >=5", "click"), project.dependencies)
        assertEquals(setOf(PythonTool.POETRY), project.tools)
    }

    @Test
    fun marksDynamicVersionAndReadsLicenseTable() {
        val project = parser.parse(dir, fixture("dynamic-setuptools.toml"), setOf(ProjectMarkers.SETUP_PY))

        assertEquals(PyProjectParser.DYNAMIC, project.version)
        assertEquals("Apache-2.0", project.license)
        assertEquals(setOf(PythonTool.SETUPTOOLS), project.tools)
    }

    @Test
    fun readsUvWorkspaceDeclarationAndWorkspaceSources() {
        val project = parser.parse(dir, fixture("workspace-root.toml"))

        assertTrue(project.isWorkspaceRoot)
        assertFalse(project.isVirtualWorkspaceRoot)
        assertEquals(listOf("packages/*", "apps/cli/"), project.uvWorkspace?.members)
        assertEquals(listOf("packages/legacy"), project.uvWorkspace?.exclude)
        assertEquals(setOf("core", "cli"), project.workspaceSources)
        assertTrue(PythonTool.UV in project.tools)
    }

    @Test
    fun virtualWorkspaceRootHasNoProjectTable() {
        val project = parser.parse(dir, "[tool.uv.workspace]\nmembers = [\"libs/*\"]\n")

        assertTrue(project.isVirtualWorkspaceRoot)
        assertEquals("project", project.displayName)
    }

    @Test
    fun malformedFileKeepsWhatCouldBeReadAndReportsProblems() {
        val project = parser.parse(dir, fixture("malformed.toml"))

        assertEquals("half-broken", project.name)
        assertTrue(project.problems.isNotEmpty())
        assertTrue(project.problems.first().startsWith("Line 4"))
    }

    @Test
    fun emptyFileFallsBackToFolderName() {
        val project = parser.parse(dir, "")

        assertNull(project.name)
        assertEquals("project", project.displayName)
        assertTrue(project.tools.isEmpty())
    }

    @Test
    fun wrongValueTypesAreIgnoredNotThrown() {
        val project = parser.parse(dir, "[project]\nname = 42\nkeywords = \"not-a-list\"\n")

        assertNull(project.name)
        assertTrue(project.keywords.isEmpty())
        assertTrue(project.problems.any { "name" in it })
    }

    @Test
    fun quotedKeysWithDotsAreNotSplit() {
        val project = parser.parse(dir, "[tool.uv.sources]\n\"zope.interface\" = { workspace = true }\n")

        assertEquals(setOf("zope.interface"), project.workspaceSources)
    }
}
