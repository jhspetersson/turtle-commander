package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.ArchiveService
import io.github.jhspetersson.turtlecommander.service.OverwriteResponse
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for archive operations: pack/extract ZIP, VFS registry, archive browsing.
 */
class ArchiveIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var archiveService: ArchiveService

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-archive-")
        archiveService = project.service()
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // --- VFS Registry ---

    fun testRegistrySupportsZip() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("test.zip"))
    }

    fun testRegistrySupportsJar() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("lib.jar"))
    }

    fun testRegistrySupportsWar() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("app.war"))
    }

    fun testRegistrySupportsEar() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("app.ear"))
    }

    fun testRegistrySupportsGz() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("file.gz"))
    }

    fun testRegistrySupportsTarGz() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.tar.gz"))
    }

    fun testRegistrySupportsTar() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.tar"))
    }

    fun testRegistrySupports7z() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.7z"))
    }

    fun testRegistrySupportsBz2() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("file.bz2"))
    }

    fun testRegistryDoesNotSupportUnknown() {
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("file.xyz"))
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("file.txt"))
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("file.pdf"))
    }

    // --- Pack ZIP ---

    fun testPackZipSingleFile() = runBlocking {
        val file = Files.writeString(tempDir.resolve("hello.txt"), "hello world")
        val archivePath = tempDir.resolve("test.zip")

        val count = archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(file),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        assertTrue("Archive should be created", Files.exists(archivePath))
        assertTrue("Archive should have content", Files.size(archivePath) > 0)
        assertEquals("Should pack 1 file", 1, count)
    }

    fun testPackZipDirectoryRecursive() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("mydir"))
        Files.writeString(dir.resolve("a.txt"), "aaa")
        val sub = Files.createDirectory(dir.resolve("sub"))
        Files.writeString(sub.resolve("b.txt"), "bbb")
        val archivePath = tempDir.resolve("dir.zip")

        val count = archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(dir),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        assertTrue(Files.exists(archivePath))
        assertTrue("Should pack multiple items", count >= 2) // at least a.txt and b.txt
    }

    fun testPackAndExtractZipRoundtrip() = runBlocking {
        // Create source files
        val file1 = Files.writeString(tempDir.resolve("one.txt"), "first")
        val file2 = Files.writeString(tempDir.resolve("two.txt"), "second")
        val archivePath = tempDir.resolve("roundtrip.zip")

        // Pack
        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(file1, file2),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        // Extract to a different directory
        val extractDir = Files.createDirectory(tempDir.resolve("extracted"))
        archiveService.extractArchiveWithProgress(
            archivePath = archivePath,
            destination = extractDir,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.YES },
            onError = { _, e -> fail("Extract error: $e") },
            isCancelled = { false },
        )

        assertTrue(Files.exists(extractDir.resolve("one.txt")))
        assertTrue(Files.exists(extractDir.resolve("two.txt")))
        assertEquals("first", Files.readString(extractDir.resolve("one.txt")))
        assertEquals("second", Files.readString(extractDir.resolve("two.txt")))
    }

    // --- VFS browsing ---

    fun testVfsBrowseZipArchive() = runBlocking {
        // Create a zip with known contents
        val file = Files.writeString(tempDir.resolve("content.txt"), "inside archive")
        val archivePath = tempDir.resolve("browse.zip")

        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(file),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        // Open VFS and browse
        val vfs = VirtualFileSystemRegistry.create(archivePath)
        vfs.use { vfs ->
            assertNotNull("VFS root should not be null", vfs.root)
            assertFalse("Archive should not be read-only for zip", vfs.isReadOnly)

            val entries = vfs.listFiles(vfs.root)
            val names = entries.map { it.name }

            assertTrue("Should find content.txt in archive", "content.txt" in names)

            val contentEntry = entries.find { it.name == "content.txt" }
            assertNotNull(contentEntry)
            assertFalse("content.txt should not be a directory", contentEntry!!.isDirectory)
        }
    }

    fun testVfsBrowseZipWithDirectories() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("folder"))
        Files.writeString(dir.resolve("inner.txt"), "data")
        val archivePath = tempDir.resolve("withdir.zip")

        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(dir),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        val vfs = VirtualFileSystemRegistry.create(archivePath)
        vfs.use { vfs ->
            val rootEntries = vfs.listFiles(vfs.root)
            val folderEntry = rootEntries.find { it.name == "folder" && it.isDirectory }
            assertNotNull("Should find folder directory", folderEntry)

            val folderContents = vfs.listFiles(folderEntry!!.path)
            assertTrue("Folder should contain inner.txt", folderContents.any { it.name == "inner.txt" })
        }
    }

    fun testVfsIsRoot() = runBlocking {
        val file = Files.writeString(tempDir.resolve("test.txt"), "test")
        val archivePath = tempDir.resolve("root.zip")

        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(file),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        val vfs = VirtualFileSystemRegistry.create(archivePath)
        vfs.use { vfs ->
            assertTrue("Root should be identified as root", vfs.isRoot(vfs.root))
        }
    }

    fun testVfsGetPath() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("adir"))
        Files.writeString(dir.resolve("file.txt"), "x")
        val archivePath = tempDir.resolve("path.zip")

        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(dir),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Pack error: $e") },
            isCancelled = { false },
        )

        val vfs = VirtualFileSystemRegistry.create(archivePath)
        vfs.use { vfs ->
            val path = vfs.getPath("/adir/file.txt")
            assertNotNull(path)
            assertTrue("Path should end with expected file", path.toString().endsWith("file.txt"))
        }
    }

    // --- Pack ZIP cancellation ---

    fun testPackZipCancellation() = runBlocking {
        // Create many files
        for (i in 1..20) {
            Files.writeString(tempDir.resolve("file$i.txt"), "content $i")
        }
        val sources = (1..20).map { tempDir.resolve("file$it.txt") }
        val archivePath = tempDir.resolve("cancel.zip")

        var packed = 0
        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = sources,
            appendToExisting = false,
            archiveExists = false,
            onProgress = { count, _ -> packed = count },
            onError = { _, _ -> },
            isCancelled = { packed >= 3 }, // cancel after 3
        )

        assertTrue("Should have packed some but not all", packed < 20)
    }

    // --- Extract overwrite handling ---

    fun testExtractOverwriteSkip() = runBlocking {
        val file = Files.writeString(tempDir.resolve("x.txt"), "original")
        val archivePath = tempDir.resolve("ow.zip")

        archiveService.packZip(
            archivePath = archivePath,
            sourcePaths = listOf(file),
            appendToExisting = false,
            archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        // Pre-create file at destination with different content
        val extractDir = Files.createDirectory(tempDir.resolve("extract"))
        Files.writeString(extractDir.resolve("x.txt"), "existing")

        archiveService.extractArchiveWithProgress(
            archivePath = archivePath,
            destination = extractDir,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        assertEquals("existing", Files.readString(extractDir.resolve("x.txt")))
    }
}
