# PyProjectHub

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/vhrabar/PyProjectHub/build.yml?style=for-the-badge)
[![Version](https://img.shields.io/jetbrains/plugin/v/35577.svg?style=for-the-badge)](https://plugins.jetbrains.com/plugin/35577)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/35577.svg?style=for-the-badge)](https://plugins.jetbrains.com/plugin/35577)


<!-- Plugin description -->
**PyProjectHub** lets you browse, search and filter your Python projects and workspaces from the
35577E Welcome screen, using the metadata in each project's `pyproject.toml`.

- Finds every `pyproject.toml` below the folders you choose, in the background
- Shows name, version, description, authors, license, `requires-python`, build backend and dependencies
- Recognises projects set up with uv, Poetry, PDM, Hatch, Flit, Maturin or setuptools
- Groups uv workspaces: the root `pyproject.toml` with its member packages beneath it
- Search by name, description, keywords, authors, dependencies or path; filter by tool and supported Python version; sort by name, modification time, last opened or path
- Opens a workspace member through its workspace root, where `uv.lock` and the shared `.venv` live, or on its own if you prefer

PyProjectHub is not affiliated with Astral or JetBrains.
<!-- Plugin description end -->

## Installation

[![Install from JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Install-blue?logo=jetbrains&style=for-the-badge)](https://plugins.jetbrains.com/plugin/35577)

- Using the 35577E built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "IssueHub"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35577) and install it by clicking the <kbd>Install to ...</kbd> button in case your 35577E is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/35577/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/vhrabar/IssueHub/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## LLM Usage Disclaimer
- Plugin icons were created using Claude Design tool powered by the Opus 5.5 model

## License

This project is licensed under the Apache License v2 - see the [LICENSE](LICENSE) file for details.

Copyright © 2026 Vedran Hrabar

PyProjectHub is not affiliated with Astral or JetBrains.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
