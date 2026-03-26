# Turtle Commander

![Build](https://github.com/jhspetersson/turtle-commander/workflows/Build/badge.svg)

<!-- Plugin description -->
A dual-panel file manager integrated into IntelliJ-based IDEs, inspired by classic orthodox file managers like Total Commander and Far Manager.

**Features:**
- Two side-by-side file panels with tabbed browsing
- Table, list, thumbnail, and tree view modes
- File operations: view (F3), open (F4), copy (F5), move (F6), delete (F8), rename (Shift-F6), create directory (F7), create file (Shift+F4)
- File search
- Quick filter (Ctrl-S) for the directory list
- Create `.zip` and `.tar.gz` archives, browse and modify archive contents, edit files inside archives, and even inside nested archives, extract files
- Select files with space and insert
- Clipboard-style copy/cut/paste (Ctrl+C, Ctrl+X, Ctrl+V) with buffer indicator in the tool window header
- Drag-and-drop between panels and to/from the Project tool window
- Compare files (with the standard diff tool)
- Draggable and reorderable tabs with the context menu
- Session state persistence: open tabs, panel sizes, and column layouts are saved per project
- Middle-click to close tabs
- Favorite tabs (with Ctrl-1..9 shortcuts for quick access) with customizable colors
- Open in Terminal
- Colored directory icons for project types (IntelliJ, Git, Gradle, Maven, Cargo, npm, Python, CMake, .NET)
- Customizable fonts, hotkeys, other settings
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Turtle Commander"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30863-turtle-commander) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/30863-turtle-commander/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/jhspetersson/turtle-commander/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>