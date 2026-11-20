package io.github.jhspetersson.turtlecommander.action

import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.TarVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddToFavoritesInArchiveTest : BasePlatformTestCase() {

    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("add-to-favorites-in-archive-")
        FileContextMenuState.clear()
    }

    override fun tearDown() {
        try {
            FileContextMenuState.clear()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun offeredFor(path: Path): Boolean {
        FileContextMenuState.clickedEntry = FileEntry(
            name = path.fileName.toString(),
            path = path,
            isDirectory = true,
            size = 0,
            lastModified = FileTime.fromMillis(0),
            permissions = "",
        )
        val action = AddToFavoritesAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    fun testOfferedForAPlainDirectory() {
        assertTrue(offeredFor(Files.createDirectories(dir.resolve("plain"))))
    }

    fun testNotOfferedForADirectoryInsideAZip() {
        val archive = dir.resolve("data.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(ZipEntry("sub/b.txt"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }

        ZipVirtualFileSystem(archive).use { vfs ->
            assertFalse(offeredFor(vfs.getPath("/sub")))
        }
    }

    fun testNotOfferedForADirectoryInsideATar() {
        val archive = dir.resolve("data.tar")
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            val entry = TarArchiveEntry("sub/a.txt")
            entry.size = 1
            out.putArchiveEntry(entry)
            out.write(byteArrayOf(1))
            out.closeArchiveEntry()
        }

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertFalse(offeredFor(vfs.root.resolve("sub")))
        }
    }
}
