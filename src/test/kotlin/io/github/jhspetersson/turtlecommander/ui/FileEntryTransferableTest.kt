package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileEntryTransferableTest {

    private lateinit var zipPath: Path
    private lateinit var zipFs: FileSystem
    private val tempCopies = mutableListOf<Path>()

    @Before
    fun setUp() {
        zipPath = Files.createTempFile("test-dnd-archive-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("Hello World".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subdir/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subdir/nested.txt"))
            zos.write("Nested file".toByteArray())
            zos.closeEntry()
        }
        zipFs = FileSystems.newFileSystem(zipPath, mapOf<String, Any>("create" to "false"))
    }

    @After
    fun tearDown() {
        zipFs.close()
        Files.deleteIfExists(zipPath)
        // deleteOnExit only fires at JVM exit; clean the extracted copies now so
        // repeated test runs don't accumulate turtle-dnd-* dirs.
        for (path in tempCopies) {
            path.toFile().deleteRecursively()
        }
        tempCopies.clear()
    }

    private fun entry(path: Path, isDirectory: Boolean = false) = FileEntry(
        name = path.fileName.toString(),
        path = path,
        isDirectory = isDirectory,
        size = 0,
        lastModified = null,
        permissions = "",
    )

    @Suppress("UNCHECKED_CAST")
    private fun exportedFiles(transferable: FileEntryTransferable): List<File> {
        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
        // Track the temp dirs holding extracted archive entries for cleanup.
        for (file in files) {
            val parent = file.parentFile ?: continue
            if (parent.name.startsWith("turtle-dnd-")) tempCopies.add(parent.toPath())
        }
        return files
    }

    @Test
    fun `local files are exported as themselves`() {
        val local = Files.createTempFile("test-dnd-local-", ".txt")
        try {
            val files = exportedFiles(FileEntryTransferable(listOf(entry(local))))
            assertEquals(listOf(local.toFile()), files)
        } finally {
            Files.deleteIfExists(local)
        }
    }

    @Test
    fun `zip entry is extracted to a real temp file`() {
        val files = exportedFiles(FileEntryTransferable(listOf(entry(zipFs.getPath("/hello.txt")))))
        assertEquals(1, files.size)
        val exported = files[0]
        assertEquals("hello.txt", exported.name)
        assertTrue(exported.isFile)
        assertEquals("Hello World", exported.readText())
    }

    @Test
    fun `zip directory is extracted recursively`() {
        val files = exportedFiles(
            FileEntryTransferable(listOf(entry(zipFs.getPath("/subdir"), isDirectory = true))),
        )
        assertEquals(1, files.size)
        val exported = files[0]
        assertTrue(exported.isDirectory)
        assertEquals("Nested file", exported.resolve("nested.txt").readText())
    }

    @Test
    fun `mixed selection exports local file directly and zip entry via temp copy`() {
        val local = Files.createTempFile("test-dnd-local-", ".txt")
        try {
            val transferable = FileEntryTransferable(
                listOf(entry(local), entry(zipFs.getPath("/hello.txt"))),
            )
            val files = exportedFiles(transferable)
            assertEquals(2, files.size)
            assertEquals(local.toFile(), files[0])
            assertEquals("Hello World", files[1].readText())
        } finally {
            Files.deleteIfExists(local)
        }
    }

    @Test
    fun `repeated requests reuse the same extraction`() {
        val transferable = FileEntryTransferable(listOf(entry(zipFs.getPath("/hello.txt"))))
        val first = exportedFiles(transferable)
        val second = exportedFiles(transferable)
        assertEquals(first, second)
    }

    @Test
    fun `file entry flavor returns the entries unchanged`() {
        val entries = listOf(entry(zipFs.getPath("/hello.txt")))
        val transferable = FileEntryTransferable(entries)
        assertSame(entries, transferable.getTransferData(FileTab.FILE_ENTRY_FLAVOR))
    }

    @Test
    fun `export progress step aborts when the indicator is cancelled`() {
        // EmptyProgressIndicator needs a running IntelliJ application; this plain unit test
        // has none, so a minimal fake covers the two methods ExportProgress relies on.
        val indicator = FakeProgressIndicator()
        val progress = ExportProgress(indicator, total = 2)
        progress.step("first.txt")
        indicator.cancel()
        try {
            progress.step("second.txt")
            fail("Expected ProcessCanceledException")
        } catch (_: ProcessCanceledException) {
            // expected: extraction stops between files once the user cancels
        }
    }

    private class FakeProgressIndicator : ProgressIndicator {
        private var cancelled = false
        private var text: String? = null
        private var text2: String? = null
        private var fraction = 0.0
        private var indeterminate = true

        override fun start() {}
        override fun stop() {}
        override fun isRunning(): Boolean = true
        override fun cancel() { cancelled = true }
        override fun isCanceled(): Boolean = cancelled
        override fun checkCanceled() {
            if (cancelled) throw ProcessCanceledException()
        }
        override fun setText(text: String?) { this.text = text }
        override fun getText(): String? = text
        override fun setText2(text: String?) { this.text2 = text }
        override fun getText2(): String? = text2
        override fun getFraction(): Double = fraction
        override fun setFraction(fraction: Double) { this.fraction = fraction }
        override fun pushState() {}
        override fun popState() {}
        override fun isModal(): Boolean = false
        override fun getModalityState(): ModalityState = ModalityState.nonModal()
        override fun setModalityProgress(modalityProgress: ProgressIndicator?) {}
        override fun isIndeterminate(): Boolean = indeterminate
        override fun setIndeterminate(indeterminate: Boolean) { this.indeterminate = indeterminate }
        override fun isPopupWasShown(): Boolean = false
        override fun isShowing(): Boolean = false
    }
}
