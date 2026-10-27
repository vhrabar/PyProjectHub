package com.github.vhrabar.pyprojecthub.discovery

import com.github.vhrabar.pyprojecthub.model.PyProject
import com.github.vhrabar.pyprojecthub.model.ScanResult
import com.github.vhrabar.pyprojecthub.parsing.ProjectMarkers
import com.github.vhrabar.pyprojecthub.parsing.PyProjectParser
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class ScanOptions(
    /** Folder levels searched below each root; the root itself is level 0. */
    val maxDepth: Int = 4,
    val excludes: List<String> = DEFAULT_EXCLUDES,
    val skipHidden: Boolean = true,
    val followSymlinks: Boolean = false,
) {
    companion object {
        val DEFAULT_EXCLUDES =
            listOf(
                ".venv",
                "venv",
                "node_modules",
                "__pycache__",
                "__pypackages__",
                "site-packages",
                "build",
                "dist",
                "*.egg-info",
                ".tox",
                ".nox",
                ".git",
            )
    }
}

/**
 * Finds every folder holding a `pyproject.toml` below the given roots and groups uv workspace
 * members under their workspace root. Plain `java.nio`, so it runs on any background thread and
 * in unit tests; [isCancelled] is polled once per visited folder.
 */
class ProjectScanner(
    private val options: ScanOptions = ScanOptions(),
    private val parser: PyProjectParser = PyProjectParser(),
) {
    private val excludeMatchers: List<PathMatcher> =
        options.excludes.filter(String::isNotBlank).map { FileSystems.getDefault().getPathMatcher("glob:${it.trim()}") }

    fun scan(
        roots: List<Path>,
        isCancelled: () -> Boolean = { false },
    ): ScanResult {
        val started = System.nanoTime()
        val normalized = roots.map { it.toAbsolutePath().normalize() }.distinct()
        val (present, missing) = normalized.partition { Files.isDirectory(it) }

        val found = LinkedHashMap<Path, PyProject>()
        for (root in present) {
            walk(root, options.maxDepth, isCancelled) { dir -> found.getOrPut(dir) { read(dir) } }
            if (isCancelled()) break
        }
        val entries = if (isCancelled()) emptyList() else WorkspaceResolver(this).resolve(found, isCancelled)

        return ScanResult(
            entries = entries,
            scannedRoots = present,
            missingRoots = missing,
            durationMillis = (System.nanoTime() - started) / 1_000_000,
            cancelled = isCancelled(),
        )
    }

    /** Parses `dir/pyproject.toml`; I/O failures become a project with a problem instead of an exception. */
    internal fun read(dir: Path): PyProject {
        val file = dir.resolve(ProjectMarkers.PYPROJECT)
        return try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return PyProject(dir, problems = listOf("pyproject.toml is larger than ${MAX_FILE_SIZE / 1024} KB and was skipped"))
            }
            val markers = ProjectMarkers.ALL.filterTo(mutableSetOf()) { Files.exists(dir.resolve(it)) }
            parser.parse(dir, Files.readString(file), markers, Files.getLastModifiedTime(file).toInstant())
        } catch (e: IOException) {
            PyProject(dir, problems = listOf("Could not read pyproject.toml: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** Calls [onProjectDir] for [start] and every non-excluded folder below it (up to [maxDepth]) holding a `pyproject.toml`. */
    internal fun walk(
        start: Path,
        maxDepth: Int,
        isCancelled: () -> Boolean,
        onProjectDir: (Path) -> Unit,
    ) {
        val visitOptions = if (options.followSymlinks) setOf(FileVisitOption.FOLLOW_LINKS) else emptySet()
        // Folders at depth maxDepth + 1 reach visitFile instead of preVisitDirectory, so they are never searched
        Files.walkFileTree(
            start,
            visitOptions,
            maxDepth + 1,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (isCancelled()) return FileVisitResult.TERMINATE
                    if (dir != start && isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE
                    if (Files.isRegularFile(dir.resolve(ProjectMarkers.PYPROJECT))) onProjectDir(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ) = FileVisitResult.CONTINUE

                // Unreadable folders and broken links are skipped, not fatal
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ) = FileVisitResult.CONTINUE
            },
        )
    }

    private fun isExcluded(dir: Path): Boolean {
        val name = dir.fileName ?: return false
        if (options.skipHidden && name.toString().startsWith('.')) return true
        return excludeMatchers.any { it.matches(name) }
    }

    private companion object {
        const val MAX_FILE_SIZE = 1024L * 1024
    }
}
