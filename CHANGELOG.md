<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Turtle Commander Changelog

## [Unreleased]

### Added

- Support Java module files (`.jmod`) as browsable archives (read-only)

## [0.7.33] - 2026-08-08

### Added

- Support Microsoft Cabinet archives (`.cab`) as browsable archives

## [0.7.32] - 2026-08-07

### Added

- Support 7z and tar comic book archives (`.cb7`, `.cbt`)

## [0.7.31] - 2026-08-06

### Added

- Support Doom 3 / id Tech 4 game data (`.pk4`) as browsable ZIP archives

## [0.7.30] - 2026-08-05

### Added

- Support LibreOffice / OpenOffice extensions (`.oxt`) as browsable ZIP archives

## [0.7.29] - 2026-08-04

### Added

- Support Universal Scene Description archives (`.usdz`) as browsable ZIP archives

## [0.7.28] - 2026-08-03

### Added

- Support Google Earth KML archives (`.kmz`) as browsable ZIP archives

## [0.7.27] - 2026-07-24

### Added

- Support XPS / OpenXPS documents (`.xps`, `.oxps`) as browsable ZIP archives

## [0.7.26] - 2026-07-23

### Added

- Support Windows app packages (`.appx`, `.appxbundle`, `.msix`, `.msixbundle`) as browsable ZIP archives

## [0.7.25] - 2026-07-22

### Added

- Support iOS app packages (`.ipa`) as browsable ZIP archives

## [0.7.24] - 2026-07-21

### Added

- Support VS Code / Visual Studio extensions (`.vsix`) as browsable ZIP archives

## [0.7.23] - 2026-07-20

### Added

- Support Android App Bundles (`.aab`) as browsable ZIP archives

## [0.7.22] - 2026-07-19

### Added

- More `Copy as` options: Date Created, Date Modified, User, Group, User:Group

## [0.7.21] - 2026-07-18

### Fixed

- Skipping files during a move no longer shows a spurious "Failed to move" error for their directory

## [0.7.20] - 2026-07-17

### Fixed

- Packing progress for `tar.gz` no longer counts failed files

## [0.7.19] - 2026-07-16

### Fixed

- Don't leave a temp directory behind when opening a nested archive fails

## [0.7.18] - 2026-07-15

### Fixed

- Cut files are no longer dropped from the paste buffer when the move fails or is canceled

## [0.7.17] - 2026-07-14

### Fixed

- Quick filter works in "Show all nested files" mode

## [0.7.16] - 2026-07-13

### Changed

- Added search dialog fields validation

## [0.7.15] - 2026-07-12

### Added

- Special handling of `cd` command

### Fixed

- Support UNC paths in breadcrumbs

## [0.7.14] - 2026-07-10

### Fixed

- MultiRename tool improved

## [0.7.13] - 2026-07-09

### Changed

- Improved font selectors

## [0.7.12] - 2026-07-08

### Changed

- Improved validation UX of colorization rules

## [0.7.11] - 2026-07-07

### Added

- Allow drag-and-drop extraction from archives

## [0.7.10] - 2026-07-06

### Fixed

- Reject symlink and hard-link entries that escape the archive root when opening tar/RPM archives
- Saving a file edited inside an archive now writes back every save, not just the first
- Editing two files from the same archive no longer loses the second file's changes
- Repacking an archive is now atomic: a failure mid-write no longer corrupts the original
- Leaving an archive no longer briefly freezes the UI
- Keyboard shortcuts (Close Tab, Copy/Cut/Paste) now act on the focused tab instead of the last right-clicked one
- Turtle Commander shortcuts no longer contend with the IDE's own when the panel is open but not focused
- The command bar only tracks Ctrl/Alt/Shift while the tool window has focus, not globally
- Closing the last search tab no longer leaves an empty, unusable panel

## [0.7.9] - 2026-07-03

### Added

- Associate `.tctheme` files with the plugin

### Fixed

- Don't delete source directory if cross-filesystem move fails
- Refuse to move directory into itself or its subdirectory

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
