package com.github.vhrabar.pyprojecthub.settings

import com.github.vhrabar.pyprojecthub.settings.PyProjectHubSettings.Companion.suggestedRoots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SuggestedRootsTest {
    private lateinit var home: Path

    @Before
    fun setUp() {
        home = Files.createTempDirectory("pyprojecthub-home")
    }

    @After
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    private fun folders(vararg names: String) = names.forEach { Files.createDirectories(home.resolve(it)) }

    @Test
    fun nothingIsSuggestedForAnEmptyHome() = assertEquals(emptyList<Path>(), suggestedRoots(home))

    @Test
    fun existingFoldersAreSuggestedInPreferenceOrder() {
        folders("repos", "src", "PycharmProjects", "Documents")
        assertEquals(listOf("PycharmProjects", "src", "repos").map(home::resolve), suggestedRoots(home))
    }

    @Test
    fun filesWithASuggestedNameAreSkipped() {
        folders("code")
        Files.writeString(home.resolve("dev"), "not a folder")
        assertEquals(listOf(home.resolve("code")), suggestedRoots(home))
    }

    @Test
    fun theSameFolderIsSuggestedOnce() {
        folders("code")
        Files.createSymbolicLink(home.resolve("dev"), home.resolve("code"))
        assertEquals(listOf(home.resolve("code")), suggestedRoots(home))
    }

    @Test
    fun foldersCreatedLaterAreSuggested() {
        assertEquals(emptyList<Path>(), suggestedRoots(home))
        folders("dev")
        assertEquals(listOf(home.resolve("dev")), suggestedRoots(home))
    }
}
