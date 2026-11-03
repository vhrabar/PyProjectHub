package com.github.vhrabar.pyprojecthub.python

import com.github.vhrabar.pyprojecthub.python.WorkspaceInterpreter.locate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceInterpreterTest {
    private lateinit var root: Path
    private lateinit var member: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pyprojecthub-workspace")
        member = Files.createDirectories(root.resolve("packages/core"))
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, "")
    }

    private fun venv(vararg executables: String) {
        file(".venv/pyvenv.cfg")
        executables.forEach { file(".venv/$it") }
    }

    @Test
    fun posixVenvInterpreterIsFound() {
        venv("bin/python", "bin/python3")
        assertEquals(root.resolve(".venv/bin/python"), locate(member, root, windows = false))
    }

    @Test
    fun python3IsUsedWhenPythonIsMissing() {
        venv("bin/python3")
        assertEquals(root.resolve(".venv/bin/python3"), locate(member, root, windows = false))
    }

    @Test
    fun windowsVenvInterpreterIsFound() {
        venv("Scripts/python.exe", "bin/python")
        assertEquals(root.resolve(".venv/Scripts/python.exe"), locate(member, root, windows = true))
    }

    @Test
    fun windowsIgnoresPosixLayout() {
        venv("bin/python")
        assertNull(locate(member, root, windows = true))
    }

    @Test
    fun noVenvMeansNoInterpreter() = assertNull(locate(member, root, windows = false))

    @Test
    fun venvWithoutPyvenvCfgIsIgnored() {
        file(".venv/bin/python")
        assertNull(locate(member, root, windows = false))
    }

    @Test
    fun danglingInterpreterSymlinkIsIgnored() {
        venv()
        Files.createDirectories(root.resolve(".venv/bin"))
        Files.createSymbolicLink(root.resolve(".venv/bin/python"), root.resolve("removed/python3.12"))
        assertNull(locate(member, root, windows = false))
    }

    @Test
    fun theRootItselfIsNotAMember() {
        venv("bin/python")
        assertNull(locate(root, root, windows = false))
    }

    @Test
    fun aFolderOutsideTheRootIsNotAMember() {
        venv("bin/python")
        val outside = Files.createTempDirectory("pyprojecthub-outside")
        try {
            assertNull(locate(outside, root, windows = false))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun unnormalizedMemberPathIsAccepted() {
        venv("bin/python")
        assertEquals(root.resolve(".venv/bin/python"), locate(member.resolve("../core"), root, windows = false))
    }
}
