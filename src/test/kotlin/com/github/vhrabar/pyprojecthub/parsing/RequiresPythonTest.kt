package com.github.vhrabar.pyprojecthub.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequiresPythonTest {
    private fun supported(spec: String): List<Int> {
        val parsed = requireNotNull(RequiresPython.parse(spec)) { "could not parse $spec" }
        return (8..14).filter { parsed.allowsMinor(3, it) }
    }

    @Test
    fun lowerBound() = assertEquals(listOf(11, 12, 13, 14), supported(">=3.11"))

    @Test
    fun range() = assertEquals(listOf(10, 11, 12), supported(">=3.10,<3.13"))

    @Test
    fun compatibleRelease() {
        assertEquals(listOf(11, 12, 13, 14), supported("~=3.11"))
        assertEquals(listOf(11), supported("~=3.11.2"))
    }

    @Test
    fun wildcardExclusion() = assertEquals(listOf(10, 11, 13, 14), supported(">=3.10, !=3.12.*"))

    @Test
    fun microLowerBoundStillAllowsThatMinor() = assertEquals(listOf(12, 13, 14), supported(">=3.12.1"))

    @Test
    fun exactWildcard() = assertEquals(listOf(12), supported("==3.12.*"))

    @Test
    fun poetryCaretAndTilde() {
        assertEquals(listOf(9, 10, 11, 12, 13, 14), supported("^3.9"))
        assertEquals(listOf(10), supported("~3.10"))
    }

    @Test
    fun poetryOr() = assertEquals(listOf(8, 12), supported("~3.8 || ~3.12"))

    @Test
    fun suffixesAreIgnored() = assertEquals(listOf(12, 13, 14), supported(">=3.12.0rc1"))

    @Test
    fun blankAllowsEverything() = assertEquals((8..14).toList(), supported(""))

    @Test
    fun garbageIsUnknown() {
        assertNull(RequiresPython.parse("python three"))
        assertNull(RequiresPython.parse("~=3"))
    }
}
