package io.github.jhspetersson.turtlecommander.dialog

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivePathValidationTest {

    private val base: Path = Files.createTempDirectory("archive-path-validation-").toAbsolutePath()

    @After
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    @Test
    fun packRejectsBlankInput() {
        assertNotNull(validatePackArchivePath("", base))
        assertNotNull(validatePackArchivePath("   ", base))
    }

    @Test
    fun packRejectsSyntacticallyInvalidPath() {
        assertNotNull(validatePackArchivePath("bad\u0000name.zip", base))
    }

    @Test
    fun packRejectsExistingDirectoryGivenAbsolutely() {
        val dir = Files.createDirectory(base.resolve("existing"))
        assertNotNull(validatePackArchivePath(dir.toString(), base))
    }

    @Test
    fun packRejectsExistingDirectoryGivenRelativeToBase() {
        Files.createDirectory(base.resolve("existing"))
        assertNotNull(validatePackArchivePath("existing", base))
    }

    @Test
    fun packAcceptsNewFileInsideExistingDirectory() {
        assertNull(validatePackArchivePath("new.zip", base))
        assertNull(validatePackArchivePath(base.resolve("new.zip").toString(), base))
    }

    @Test
    fun packAcceptsExistingRegularFileSoTheOverwritePromptCanHandleIt() {
        val file = Files.writeString(base.resolve("old.zip"), "x")
        assertNull(validatePackArchivePath(file.toString(), base))
    }

    @Test
    fun extractRejectsBlankInput() {
        assertNotNull(validateExtractDestination("", base))
        assertNotNull(validateExtractDestination("   ", base))
    }

    @Test
    fun extractRejectsSyntacticallyInvalidPath() {
        assertNotNull(validateExtractDestination("bad\u0000dir", base))
    }

    @Test
    fun extractRejectsExistingRegularFile() {
        val file = Files.writeString(base.resolve("plain.txt"), "x")
        assertNotNull(validateExtractDestination(file.toString(), base))
        assertNotNull(validateExtractDestination("plain.txt", base))
    }

    @Test
    fun extractAcceptsExistingAndMissingDirectories() {
        Files.createDirectory(base.resolve("out"))
        assertNull(validateExtractDestination("out", base))
        assertNull(validateExtractDestination("not-yet-created", base))
        assertNull(validateExtractDestination(base.toString(), base))
    }

    @Test
    fun relativeInputWithArchiveInternalBaseReportsInsteadOfThrowing() {
        val zip = base.resolve("inner.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("dir/file.txt"))
            zos.write(1)
            zos.closeEntry()
        }
        FileSystems.newFileSystem(zip).use { zipFs ->
            val inside = zipFs.getPath("/dir")
            assertNotNull(validatePackArchivePath("out.zip", inside))
            assertNotNull(validateExtractDestination("out", inside))
            assertNull(validatePackArchivePath(base.resolve("out.zip").toString(), inside))
            assertNull(validateExtractDestination(base.resolve("out").toString(), inside))
        }
    }
}
