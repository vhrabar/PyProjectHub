package com.github.vhrabar.pyprojecthub.python

import com.github.vhrabar.pyprojecthub.parsing.ProjectMarkers
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/** Finds the interpreter of the shared `.venv` a uv workspace member uses when it is opened on its own. */
object WorkspaceInterpreter {
    private const val PYVENV_CFG = "pyvenv.cfg"

    /**
     * The Python executable of the `.venv` at [workspaceRoot] for the member at [memberDir], or null when
     * [memberDir] is not below [workspaceRoot] or the root has no usable environment. A `.venv` counts only
     * if it has `pyvenv.cfg` and an interpreter that resolves (a dangling symlink to a removed Python doesn't).
     */
    fun locate(
        memberDir: Path,
        workspaceRoot: Path,
        windows: Boolean = SystemInfo.isWindows,
    ): Path? {
        val member = memberDir.toAbsolutePath().normalize()
        val root = workspaceRoot.toAbsolutePath().normalize()
        if (member == root || !member.startsWith(root)) return null

        val venv = root.resolve(ProjectMarkers.VENV)
        if (!Files.isRegularFile(venv.resolve(PYVENV_CFG))) return null
        return executables(windows).map(venv::resolve).firstOrNull(Files::isRegularFile)
    }

    private fun executables(windows: Boolean): List<String> =
        if (windows) listOf("Scripts/python.exe") else listOf("bin/python", "bin/python3")
}
