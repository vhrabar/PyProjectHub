package com.github.vhrabar.pyprojecthub.settings

import com.github.vhrabar.pyprojecthub.settings.PyProjectHubSettings.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class ToPathTest {
    private val home = Path.of(System.getProperty("user.home"))

    @Test
    fun tildeAlone() = assertEquals(home, toPath("~"))

    @Test
    fun tildeSlash() = assertEquals(home.resolve("projects"), toPath("~/projects"))

    @Test
    fun tildeBackslash() = assertEquals(home.resolve("projects"), toPath("~\\projects"))

    @Test
    fun tildeSeparatorOnly() {
        assertEquals(home, toPath("~/"))
        assertEquals(home, toPath("~\\"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() = assertEquals(home.resolve("projects"), toPath("  ~/projects  "))

    @Test
    fun absolutePathIsKept() {
        val absolute = home.resolve("work").toAbsolutePath()
        assertEquals(absolute, toPath(absolute.toString()))
    }

    @Test
    fun tildeInsideNameIsNotExpanded() = assertEquals(Path.of("~projects"), toPath("~projects"))

    @Test
    fun blankIsNull() {
        assertNull(toPath(""))
        assertNull(toPath("   "))
    }

    @Test
    fun invalidPathIsNull() = assertNull(toPath("bad\u0000path"))
}
