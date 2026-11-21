package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.nio.file.Files
import java.nio.file.Path

class PackExtractDialogValidationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var sources: List<FileEntry>

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-dialog-validation-")
        val file = Files.writeString(tempDir.resolve("plain.txt"), "x")
        sources = listOf(FileEntry("plain.txt", file, false, 1, lastModified = null, permissions = ""))
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testPackDialogRejectsBlankArchivePath() {
        val dialog = PackDialog(project, sources, "", tempDir)
        try {
            assertNotNull(dialog.doValidate())
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogRejectsCurrentDirectoryAsArchive() {
        val dialog = PackDialog(project, sources, tempDir.toString(), tempDir)
        try {
            assertNotNull(dialog.doValidate())
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogAcceptsNewArchiveName() {
        val dialog = PackDialog(project, sources, tempDir.resolve("out.zip").toString(), tempDir)
        try {
            assertNull(dialog.doValidate())
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogSwapsAnUppercaseExtension() {
        val dialog = PackDialog(project, sources, tempDir.resolve("backup.ZIP").toString(), tempDir)
        try {
            dialog.formatCombo.selectedItem = ArchiveFormat.TAR_GZ

            assertEquals(tempDir.resolve("backup.tar.gz").toString(), dialog.archiveField.text)
            assertEquals(tempDir.resolve("backup.tar.gz").toString(), dialog.archivePath)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogKeepsTheTypedCaseOfAMatchingExtension() {
        val dialog = PackDialog(project, sources, tempDir.resolve("backup.ZIP").toString(), tempDir)
        try {
            assertEquals(tempDir.resolve("backup.ZIP").toString(), dialog.archivePath)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogFollowsATypedExtension() {
        val dialog = PackDialog(project, sources, tempDir.resolve("out.zip").toString(), tempDir)
        try {
            dialog.archiveField.text = tempDir.resolve("out.tar.gz").toString()

            assertEquals(ArchiveFormat.TAR_GZ, dialog.archiveFormat)
            assertEquals(tempDir.resolve("out.tar.gz").toString(), dialog.archivePath)

            dialog.archiveField.text = tempDir.resolve("out.ZIP").toString()

            assertEquals(ArchiveFormat.ZIP, dialog.archiveFormat)
            assertEquals(tempDir.resolve("out.ZIP").toString(), dialog.archivePath)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogStartsOnTheFormatOfItsDefaultPath() {
        val dialog = PackDialog(project, sources, tempDir.resolve("out.tar.gz").toString(), tempDir)
        try {
            assertEquals(ArchiveFormat.TAR_GZ, dialog.archiveFormat)
            assertEquals(tempDir.resolve("out.tar.gz").toString(), dialog.archivePath)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testPackDialogKeepsTheComboFormatForANameWithoutAKnownExtension() {
        val dialog = PackDialog(project, sources, tempDir.resolve("out.zip").toString(), tempDir)
        try {
            dialog.formatCombo.selectedItem = ArchiveFormat.TAR_GZ
            dialog.archiveField.text = tempDir.resolve("plain").toString()

            assertEquals(ArchiveFormat.TAR_GZ, dialog.archiveFormat)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testExtractDialogRejectsBlankDestination() {
        val dialog = ExtractDialog(project, sources, "", tempDir)
        try {
            assertNotNull(dialog.doValidate())
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testExtractDialogRejectsRegularFileDestination() {
        val dialog = ExtractDialog(project, sources, tempDir.resolve("plain.txt").toString(), tempDir)
        try {
            assertNotNull(dialog.doValidate())
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testExtractDialogAcceptsDirectoryDestination() {
        val dialog = ExtractDialog(project, sources, tempDir.toString(), tempDir)
        try {
            assertNull(dialog.doValidate())
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
