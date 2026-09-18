package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenTargetTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var jarPath: Path
    private lateinit var jarRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-open-target-")
        jarPath = tempDir.resolve("lib.jar")
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zos ->
            zos.putNextEntry(ZipEntry("com/example/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("com/example/Foo.class"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }
        val local = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(jarPath)!!
        jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(local)!!
        jarRoot.refresh(false, true)
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun jarEntry(relativePath: String): VirtualFile =
        jarRoot.findFileByRelativePath(relativePath) ?: error("missing jar entry $relativePath")

    private fun archiveEntry(file: VirtualFile): OpenTarget.ArchiveEntry {
        val target = OpenTarget.of(file)
        assertTrue("expected archive entry, got $target", target is OpenTarget.ArchiveEntry)
        return target as OpenTarget.ArchiveEntry
    }

    fun testJarEntryFileMapsToContainingArchive() {
        val target = archiveEntry(jarEntry("com/example/Foo.class"))
        assertEquals(jarPath.toRealPath(), target.archivePath.toRealPath())
        assertEquals("com/example/Foo.class", target.entryPath)
        assertFalse(target.isDirectory)
    }

    fun testJarEntryDirectoryMapsToContainingArchive() {
        val target = archiveEntry(jarEntry("com/example"))
        assertEquals("com/example", target.entryPath)
        assertTrue(target.isDirectory)
    }

    fun testJarRootMapsToArchiveRoot() {
        val target = archiveEntry(jarRoot)
        assertEquals("", target.entryPath)
        assertTrue(target.isDirectory)
    }

    fun testLocalFileMapsToLocalTarget() {
        val file = Files.writeString(tempDir.resolve("notes.txt"), "x")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        val target = OpenTarget.of(vf)
        assertEquals(OpenTarget.Local(Path.of(vf.path), false), target)
    }

    fun testLocalDirectoryMapsToLocalTarget() {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempDir)!!
        val target = OpenTarget.of(vf)
        assertEquals(OpenTarget.Local(Path.of(vf.path), true), target)
    }

    fun testInMemoryFileHasNoTarget() {
        assertNull(OpenTarget.of(LightVirtualFile("Scratch.kt", "")))
    }

    fun testJdkHomeBacksOntoItsModulesImage() {
        val javaHome = Path.of(System.getProperty("java.home"))
        assertEquals(
            javaHome.resolve("lib").resolve("modules"),
            OpenTarget.archiveBackingFile(javaHome, isDirectory = true),
        )
    }

    fun testPlainDirectoryHasNoBackingArchive() {
        assertNull(OpenTarget.archiveBackingFile(tempDir, isDirectory = true))
    }

    fun testUnsupportedArchiveExtensionHasNoBackingArchive() {
        assertNull(OpenTarget.archiveBackingFile(tempDir.resolve("data.bin"), isDirectory = false))
    }

    fun testActionEnabledForJarEntry() {
        assertTrue(updated(jarEntry("com/example/Foo.class")).presentation.isEnabledAndVisible)
    }

    fun testActionHiddenForInMemoryFile() {
        assertFalse(updated(LightVirtualFile("Scratch.kt", "")).presentation.isEnabledAndVisible)
    }

    private fun updated(file: VirtualFile): AnActionEvent {
        val action = OpenInTurtleCommanderAction()
        val context = SimpleDataContext.getSimpleContext(CommonDataKeys.VIRTUAL_FILE, file)
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event
    }
}
