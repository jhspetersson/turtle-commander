<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Turtle Commander Changelog

## [Unreleased]

### Added

- Associate `.tctheme` files with the plugin

## [0.7.8] - 2026-07-02

### Added

- Colorization rules for named pipes

## [0.7.7] - 2026-07-01

### Added

- Recognize Unix named pipes (FIFOs) as a distinct file type

## [0.7.6] - 2026-06-30

### Added

- Option to open directories with one click

## [0.7.5] - 2026-06-29

### Changed

- Saving command history is now configurable

## [0.7.4] - 2026-06-26

### Changed

- Keep shell commands history

## [0.7.3] - 2026-06-25

### Added

- Command line (`Ctrl+E`) to run a shell command in the active panel's directory

## [0.7.2] - 2026-06-24

### Added

- Rename files/directories with one click

## [0.7.1] - 2026-06-23

### Added

- Custom icons for Favorites

## [0.7.0] - 2026-06-22

### Added

- Ability to replace file/directory icons depending on the matching rules

### Changed

- Improved colorization rules UI

### Fixed

- Command bar styling bug
- Breadcrumbs width is now calculated more accurately

## [0.6.11] - 2026-06-19

### Added

- Strikethrough effect for colorization rules (e.g., to mark broken symlinks)

## [0.6.10] - 2026-06-18

### Added

- "Show all nested files" directory view mode

## [0.6.9] - 2026-06-17

### Added

- Configurable export datetime format

## [0.6.8] - 2026-06-16

### Added

- Natural sort order (e.g., `file2.txt` comes before `file10.txt`) by default

## [0.6.7] - 2026-06-15

### Added

- Links column showing the hard-link count (Unix only, hidden by default — enable it in Settings)

### Changed

- Hide some columns on Windows completely, instead of showing empty values

### Fixed

- The UI no longer freezes on slow or disconnected network paths

## [0.6.6] - 2026-06-12

### Added

- Quick filter (`Ctrl-S`) in the search results panel

### Changed

- A tab whose directory disappeared now reopens at the nearest existing ancestor or the home directory when the whole drive is gone
- "Delete to Recycle Bin" on a system without a usable trash now asks for a permanent delete

### Fixed

- Extracting an `.iso` image produced zero-filled files instead of the real content
- Moving a symbolic link to a directory now moves the link itself instead of draining the linked directory's contents

## [0.6.4] - 2026-06-11

### Added

- Speed search (type to find, with match highlighting) in the search results panel

## [0.6.3] - 2026-06-10

### Changed

- Improved UX of the Search dialog

## [0.6.2] - 2026-06-09

### Added

- Inode column (Unix only, hidden by default — enable it in Settings)

## [0.6.1] - 2026-06-08

### Added

- Optionally create hardlinks

### Fixed

- `Alt-F6` button is now visible on the command bar

## [0.6.0] - 2026-06-05

### Added

- Symbolic link support (also, create them with `Alt+F6`)

### Changed

- Improved thumbnails generation

## [0.5.21] - 2026-06-04

### Added

- Read support for RAR archives (`.rar`, `.cbr`)

## [0.5.20] - 2026-06-03

### Added

- Context menu for the Favorites

## [0.5.19] - 2026-06-02

### Added

- Support Python `.whl` (wheel) and `.egg` packages as browsable archives

## [0.5.18] - 2026-05-29

### Added

- Open `.iso` disc images (ISO 9660 and UDF) as browsable archives (read-only)

## [0.5.16] - 2026-05-28

### Added

- Search by the setuid/setgid/sticky bits (Linux and macOS)

## [0.5.15] - 2026-05-27

### Added

- Show setuid/setgid/sticky bits in the permissions string (`ls`-style) on Linux and macOS

## [0.5.14] - 2026-05-26

### Added

- Color rules can now match on created/modified date, owner, group, and permissions
- Support Quake / Quake 2 `.pak` files as archives
- Support Quake 3 `.pk3` files as ZIP archives

## [0.5.13] - 2026-05-22

### Added

- Support more OOXML and OpenDocument variants as archives
- Add the `User Manual` menu item

## [0.5.12] - 2026-05-21

### Added

- Readonly support for `.rpm` files as archives

## [0.5.11] - 2026-05-20

### Added

- `Refresh` action in the tab context menu

## [0.5.10] - 2026-05-19

### Added

- Support Chrome (`.crx`, read-only) and Firefox (`.xpi`) extensions as browsable archives

## [0.5.9] - 2026-05-16

### Added

- Various selection actions

## [0.5.8] - 2026-05-15

### Added

- `Open in Explorer` action for tabs

## [0.5.7] - 2026-05-15

### Added

- Copy As CSV and JSON actions

## [0.5.6] - 2026-05-13

### Added

- Recognize ZIP-based document, eBook, and package formats as browsable archives: Office Open XML
  (`.docx`, `.xlsx`, `.pptx`), OpenDocument (`.odt`, `.ods`, `.odp`, `.odg`), eBooks (`.epub`),
  comic-book archives (`.cbz`), and NuGet packages (`.nupkg`)

## [0.5.5] - 2026-05-12

### Added

- Configurable datetime and file size formats

## [0.5.4] - 2026-05-11

### Added

- Properties / Get Info context menu
- More overwrite options for `Copy` and `Move` actions

### Fixed

- Improved macOS keys support
- Improved handling of 7zip archives

## [0.5.3] - 2026-05-08

### Changed

- Thumbnail size is now configurable
- Improved thumbnail quality

## [0.5.2] - 2026-04-27

### Added

- `Extract to Subdir` action
- `Shift-F2` is now a default shortcut for extracting files
- `Back to Root` action (`Ctrl-\`) to navigate to the root of the current filesystem or archive

### Fixed

- Improved directory cache behavior

## [0.5.1] - 2026-04-24

### Added

- Vertical and Single-panel layouts

## [0.5.0] - 2026-04-23

### Changed

- Project directory colors are now fully customizable

## [0.4.13] - 2026-04-22

### Changed

- Improved Multi-Rename tool

## [0.4.12] - 2026-04-21

### Added

- Multi-Rename tool (`Ctrl+M`) with single-level undo (`Ctrl+Shift+M`)
- Support `.aar` files as archives

## [0.4.11] - 2026-04-20

### Added

- Reopen closed tab action (`Ctrl+Shift+T`), per-panel closed-tab history

### Changed

- Improved column width calculation

### Fixed

- Fixed resizing cursor for the table column headers
- Drive selector no longer steals focus when switching tabs

## [0.4.10] - 2026-04-17

### Added

- Duplicate tab action
- Close duplicate tabs action
- Option to delete to Recycle Bin

### Changed

- Improved Copy/Move dialogs
- Improved cursor movement
- Reworked theme configuration UI

### Fixed

- Restoring tab view mode

## [0.4.9] - 2026-04-16

### Added

- Search by permissions
- Search filter by file/directory

### Changed

- Selection logic mimics existing dual-pane file managers

## [0.4.8] - 2026-04-15

### Added

- Speed search in file panels (just start typing)

### Changed

- The standard delete dialog is used for files in the project directory
- Split files by 1 MB by default

### Fixed

- Stale cache issues 😭

## [0.4.7] - 2026-04-14

### Added

- Copy as hash actions

### Fixed

- Stale cache issues

## [0.4.6] - 2026-04-13

### Added

- Search by file content
- Open and modify `.apkg` files (Anki decks)

### Fixed

- Drag'n'drop now works for every view mode
- Selection with SPACE and INSERT works for list and thumbnail view modes

## [0.4.5] - 2026-04-10

### Added

- Save filename search history

### Fixed

- Improved directory listing times
- Show an error notification when a directory cannot be listed (e.g., access denied)

## [0.4.4] - 2026-04-09

### Added

- Search by user and group

### Changed

- Improved search UI

### Fixed

- Default column width now is applied correctly

## [0.4.3] - 2026-04-08

### Added

- User and Group columns (hidden on Windows by default)
- drive free space dialog

## [0.4.2] - 2026-04-07

### Added

- open and modify `.deb` files (thanks to `ar` and `xz` archives support)
- open and modify `.apk` files as archives

### Fixed

- some `.zip` archives failed to open

## [0.4.1] - 2026-04-02

### Added

- Panel path input now has a context menu
- About dialog

### Changed

- Don't restart the IDE after plugin installation/update

### Fixed

- Focus bugs

## [0.4.0] - 2026-04-01

### Added

- Initial themes support
- Labels for the drives on Windows

## [0.3.2] - 2026-03-29

### Added

- Copy file or directory name, path, and parent to the clipboard from the context menu
- `Search in Directory` for tabs

### Changed

- Switch to the same directory as the opposite panel when selecting a drive if the drives are the same

### Fixed

- Fix renaming a file while it is being edited

## [0.3.1] - 2026-03-27

### Fixed

- Fix the issue with the `Rename` action

## [0.3.0] - 2026-03-27

### Added

- Thumbnail view mode
- periodically update drive selector
- add a user's home to the drive selector on Windows
- add Open in -> Turtle Commander action for Project files
- calculate the directory size on its selection
- make all UI elements configurable
- split and combine files

## [0.2.1] - 2026-03-26

### Added

- Hotkeys (Ctrl-1..9) and optional colors to favorite directories
- Quick filter (Ctrl-S) for the directory list
- Open in Terminal
- Option to sort directories together with files
- `.tar.gz` archives creation
- edit files inside archives and even nested archives

### Changed

- Rename now uses Shift-F6 hotkey by default
- The minimal IntelliJ version is now 2025.3

## [0.1.0] - 2026-03-23

### Added

- Initial release
