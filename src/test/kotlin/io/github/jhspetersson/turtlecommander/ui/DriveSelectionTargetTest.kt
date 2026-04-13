package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DriveSelectionTargetTest {

    @Test
    fun `returns opposite panel path when it is under selected drive`() {
        val drive = Path.of("/home")
        val otherPanel = Path.of("/home/user/Documents")

        val result = FileTab.resolveDriveSelectionTarget(drive, otherPanel)

        assertEquals(otherPanel, result)
    }

    @Test
    fun `returns selected drive when opposite panel is on different drive`() {
        val drive = Path.of("/mnt/data")
        val otherPanel = Path.of("/home/user/Documents")

        val result = FileTab.resolveDriveSelectionTarget(drive, otherPanel)

        assertEquals(drive, result)
    }

    @Test
    fun `returns selected drive when opposite panel is null`() {
        val drive = Path.of("/home")

        val result = FileTab.resolveDriveSelectionTarget(drive, null)

        assertEquals(drive, result)
    }

    @Test
    fun `returns selected drive when opposite panel is at the same path`() {
        val drive = Path.of("/home")
        val otherPanel = Path.of("/home")

        val result = FileTab.resolveDriveSelectionTarget(drive, otherPanel)

        assertEquals(drive, result)
    }

    @Test
    fun `works with nested subdirectory paths`() {
        val drive = Path.of(System.getProperty("user.home"))
        val otherPanel = drive.resolve("Documents").resolve("Projects")

        val result = FileTab.resolveDriveSelectionTarget(drive, otherPanel)

        assertEquals(otherPanel, result)
    }

    @Test
    fun `returns drive when opposite panel is under unrelated root`() {
        val tempDir1 = Files.createTempDirectory("drive-a-")
        val tempDir2 = Files.createTempDirectory("drive-b-")
        try {
            val otherPanel = tempDir2.resolve("somedir")

            val result = FileTab.resolveDriveSelectionTarget(tempDir1, otherPanel)

            assertEquals(tempDir1, result)
        } finally {
            tempDir1.toFile().deleteRecursively()
            tempDir2.toFile().deleteRecursively()
        }
    }

    @Test
    fun `handles user home as selected directory`() {
        val userHome = Path.of(System.getProperty("user.home"))
        val subDir = userHome.resolve("Documents").resolve("work")

        val result = FileTab.resolveDriveSelectionTarget(userHome, subDir)

        assertEquals(subDir, result)
    }

    @Test
    fun `uses archive parent directory when opposite panel is in VFS`() {
        // When the opposite panel is inside an archive, the otherPanelPathProvider now
        // returns realFilesystemPath (the directory containing the archive) instead of the
        // VFS-internal currentPath. So the resolver sees a real path under the selected drive.
        val tempDir = Files.createTempDirectory("test-vfs-drive-")
        try {
            val archiveParent = tempDir.resolve("projects")
            Files.createDirectories(archiveParent)
            // Simulate: opposite panel browsing archive at projects/data.zip
            // realFilesystemPath would be the archive's parent directory
            val drive = tempDir

            val result = FileTab.resolveDriveSelectionTarget(drive, archiveParent)

            // Should navigate to the archive's parent directory, not the drive root
            assertEquals(archiveParent, result)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `selecting root drive does not swap to other panel when a deeper root covers it`() {
        // Bug: user on /home/user, other panel on /home/user/Documents, picks "/" from
        // the drive selector. Without availableRoots the resolver always swaps to the
        // other panel because every absolute path starts with "/".
        val root = Path.of("/")
        val otherPanel = Path.of("/home/user/Documents")
        val roots = listOf(Path.of("/"), Path.of("/home/user"))

        val result = FileTab.resolveDriveSelectionTarget(root, otherPanel, roots)

        assertEquals(root, result)
    }

    @Test
    fun `selecting root drive swaps to other panel when no deeper root covers it`() {
        val root = Path.of("/")
        val otherPanel = Path.of("/etc/nginx")
        val roots = listOf(Path.of("/"))

        val result = FileTab.resolveDriveSelectionTarget(root, otherPanel, roots)

        assertEquals(otherPanel, result)
    }

    @Test
    fun `selecting a deeper root swaps to other panel when that root is the deepest match`() {
        val deeper = Path.of("/home/user")
        val otherPanel = Path.of("/home/user/Documents")
        val roots = listOf(Path.of("/"), Path.of("/home/user"))

        val result = FileTab.resolveDriveSelectionTarget(deeper, otherPanel, roots)

        assertEquals(otherPanel, result)
    }

    @Test
    fun `falls back to drive when VFS archive is on different drive`() {
        val drive = Path.of(System.getProperty("user.home"))
        // Simulate opposite panel's archive parent being on a completely different path
        val archiveParent = drive.resolve("..").resolve("other").normalize()

        val result = FileTab.resolveDriveSelectionTarget(drive, archiveParent)

        // archiveParent is not under drive, so falls back to drive
        if (!archiveParent.startsWith(drive)) {
            assertEquals(drive, result)
        }
    }
}
