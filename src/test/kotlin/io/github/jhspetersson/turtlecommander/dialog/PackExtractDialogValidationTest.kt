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
