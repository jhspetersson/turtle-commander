<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Turtle Commander Changelog

## [Unreleased]

### Added

- Speed search in file panels (just start typing)

### Changed

- Split files by 1 MB by default

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
