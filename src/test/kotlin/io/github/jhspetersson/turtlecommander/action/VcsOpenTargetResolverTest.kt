package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class VcsOpenTargetResolverTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-vcs-target-")
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun resolve(context: DataContext): OpenTarget? =
        VcsOpenTargetResolver().resolve(TestActionEvent.createTestEvent(OpenInTurtleCommanderAction(), context))

    fun testFilePathOfExistingFileMapsToLocalFile() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        val context = SimpleDataContext.getSimpleContext(VcsDataKeys.FILE_PATH, LocalFilePath(file.toString(), false))
        assertEquals(OpenTarget.Local(Path.of(vf.path), false), resolve(context))
    }

    fun testFilePathOfDeletedFileFallsBackToParentDirectory() {
        val missing = tempDir.resolve("deleted.txt")
        val context = SimpleDataContext.getSimpleContext(VcsDataKeys.FILE_PATH, LocalFilePath(missing.toString(), false))
        assertEquals(OpenTarget.Local(tempDir.toAbsolutePath(), true), resolve(context))
    }

    fun testChangeOfDeletedFileFallsBackToParentDirectory() {
        val missing = tempDir.resolve("deleted.txt")
        val change = Change(CurrentContentRevision(LocalFilePath(missing.toString(), false)), null)
        val context = SimpleDataContext.getSimpleContext(VcsDataKeys.CHANGES, arrayOf(change))
        assertEquals(OpenTarget.Local(tempDir.toAbsolutePath(), true), resolve(context))
    }

    fun testVcsVirtualFilesMapToLocalFile() {
        val file = Files.writeString(tempDir.resolve("b.txt"), "x")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        val context = SimpleDataContext.getSimpleContext(VcsDataKeys.VIRTUAL_FILES, listOf(vf))
        assertEquals(OpenTarget.Local(Path.of(vf.path), false), resolve(context))
    }

    fun testEmptyContextHasNoTarget() {
        assertNull(resolve(SimpleDataContext.EMPTY_CONTEXT))
    }

    fun testActionPicksUpResolverThroughExtensionPoint() {
        val missing = tempDir.resolve("deleted.txt")
        val context = SimpleDataContext.getSimpleContext(VcsDataKeys.FILE_PATH, LocalFilePath(missing.toString(), false))
        val action = OpenInTurtleCommanderAction()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }
}
