<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# PyProjectHub Changelog

## [Unreleased]

### Added

- Supports PyCharm 2025.3 and later.
- **Python Projects…** on the Welcome screen and in the **Tools** menu opens a browser of the Python projects found below your chosen folders.
- **Settings | Tools | PyProjectHub** sets the folders to scan, the search depth, which folders to skip, whether hidden folders are skipped, and whether symbolic links are followed.
- Each project's `pyproject.toml` is read for its name, version, description, authors, license, `requires-python`, build backend and dependencies, including legacy `[tool.poetry]` metadata. Malformed files show what could still be read plus the problem.
- uv workspaces are grouped under their root, with members resolved from `[tool.uv.workspace]` `members` and `exclude` globs, even when they sit deeper than the search depth or the root sits outside the chosen folders.
- Search across names, descriptions, keywords, authors, dependencies and paths; filter by tool (uv, Poetry, PDM, Hatch, Flit, Maturin, setuptools) and by supported Python version; sort by name, last modified, recently opened or path. The browser remembers these choices.
- Opening a workspace member opens its workspace root by default, so the shared `uv.lock` and `.venv` are used. **Open Member Alone** opens just the package.
