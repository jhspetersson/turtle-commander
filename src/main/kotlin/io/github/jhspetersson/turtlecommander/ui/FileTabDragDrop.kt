package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.VfsTempCleanup
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.resolveEntryPath

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.JComponent
import javax.swing.TransferHandler

internal class FileEntryTransferHandler(private val tab: FileTab) : TransferHandler() {
    override fun getSourceActions(c: JComponent): Int = COPY

    override fun createTransferable(c: JComponent): Transferable? {
        val entries = tab.getSelectedEntries()
        if (entries.isEmpty()) return null
        return FileEntryTransferable(entries, tab.project)
    }

    override fun canImport(support: TransferSupport): Boolean {
        if (!support.isDrop) return false
        if (tab.currentVfs?.isReadOnly == true) return false
        return support.isDataFlavorSupported(FileTab.FILE_ENTRY_FLAVOR)
            || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        if (tab.currentVfs?.isReadOnly == true) {
            tab.fileErrorNotification("Cannot copy into a read-only archive")
            return false
        }
        try {
            when {
                support.isDataFlavorSupported(FileTab.FILE_ENTRY_FLAVOR) -> {
                    @Suppress("UNCHECKED_CAST")
                    val entries = support.transferable.getTransferData(FileTab.FILE_ENTRY_FLAVOR) as List<FileEntry>
                    tab.performCopyEntries(entries, tab.currentPath, tab.getDisplayPath())
                    return true
                }
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                    // The transferable must be read here, while the drop is live — but the
                    // per-file stats (isDirectory / isFile / length) can block on a network
                    // share, so entry building happens off the EDT and only the dialog hops back.
                    @Suppress("UNCHECKED_CAST")
                    val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    val destination = tab.currentPath
                    val displayPath = tab.getDisplayPath()
                    tab.fileOps.launch {
                        val entries = withContext(Dispatchers.IO) {
                            files.map { file ->
                                val path = file.toPath()
                                FileEntry(
                                    name = file.name,
                                    path = path,
                                    isDirectory = file.isDirectory,
                                    size = if (file.isFile) file.length() else 0,
                                    lastModified = null,
                                    permissions = "",
                                )
                            }
                        }
                        withContext(Dispatchers.EDT) {
                            tab.performCopyEntries(entries, destination, displayPath)
                        }
                    }
                    return true
                }
                else -> return false
            }
        } catch (e: Exception) {
            thisLogger().warn("Drop failed: ${fileErrorMessage(e)}")
            return false
        }
    }
}

internal class FileEntryTransferable(
    private val entries: List<FileEntry>,
    private val project: Project? = null,
) : Transferable {
    private val supportedFlavors = arrayOf(FileTab.FILE_ENTRY_FLAVOR, DataFlavor.javaFileListFlavor)

    /**
     * Cached [DataFlavor.javaFileListFlavor] payload: drop targets may request the same
     * flavor several times during one drop, and archive-backed entries must not be
     * re-extracted on every request. A cancelled extraction is not cached, so a later
     * request starts over.
     */
    private var exportedFiles: List<File>? = null

    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in supportedFlavors
    override fun getTransferData(flavor: DataFlavor): Any {
        return when (flavor) {
            FileTab.FILE_ENTRY_FLAVOR -> entries
            DataFlavor.javaFileListFlavor -> exportedFiles ?: exportFilesWithProgress().also { exportedFiles = it }
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    /**
     * Runs [exportFiles] under a cancellable modal progress when that is both needed and
     * possible. Needed: only when some entry lives on a non-default filesystem and real
     * extraction work is coming. Possible: only on the EDT — same-JVM drop targets (the IDE
     * project tree, the editor) request the flavor there, and a synchronous progress dialog
     * is exactly the tool that keeps the UI painting while the work runs on a pooled thread.
     * Requests from external applications (OS file manager) arrive on the AWT toolkit thread
     * instead, where the EDT is free anyway — extraction runs inline and the UI stays live.
     *
     * Cancelling surfaces as [IOException], the failure mode [Transferable.getTransferData]
     * defines; the drop target treats the drop as failed and partially extracted temp files
     * are left to the usual delete-on-exit / stale-sweep cleanup.
     */
    private fun exportFilesWithProgress(): List<File> {
        val needsExtraction = entries.any { it.path.fileSystem != FileSystems.getDefault() }
        val application = ApplicationManager.getApplication()
        if (!needsExtraction || application == null || !application.isDispatchThread) {
            return exportFiles(null)
        }
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable {
                    exportFiles(ProgressManager.getInstance().progressIndicator)
                },
                "Extracting files from archive",
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            throw IOException("Extraction cancelled")
        }
    }

    /**
     * Builds the real-file list an external drop target (IDE project tree, OS file manager,
     * editor) receives. Plain local files and temp-dir-backed VFS entries already live on the
     * default filesystem — they only need lazy stubs streamed ([OpenVfsRegistry]) before the
     * target copies them. Entries from a non-default filesystem ([ZipVirtualFileSystem]'s zip
     * FS) have no on-disk form and `Path.toFile()` would throw, so they are extracted into a
     * fresh temp directory first and the temp copies handed out instead.
     *
     * Runs at drop time, so only accepted drops pay the extraction cost.
     */
    private fun exportFiles(indicator: ProgressIndicator?): List<File> {
        var tempDir: Path? = null
        val progress = ExportProgress(indicator, countExportSteps())
        return entries.map { entry ->
            if (entry.path.fileSystem == FileSystems.getDefault()) {
                progress.step(entry.name)
                OpenVfsRegistry.materializeTreeIfNeeded(entry.path)
                entry.path.toFile()
            } else {
                val dir = tempDir ?: createDndTempDir().also { tempDir = it }
                extractEntryForExport(entry, dir, progress)
            }
        }
    }

    /**
     * Total [ExportProgress.step] calls the export will make: one per file to copy (directory
     * entries on an archive filesystem are pre-walked to count their files) and one per
     * default-filesystem entry. The pre-walk is cheap — the zip filesystem answers it from
     * the in-memory central directory.
     */
    private fun countExportSteps(): Int {
        var count = 0
        for (entry in entries) {
            if (entry.path.fileSystem != FileSystems.getDefault() && Files.isDirectory(entry.path)) {
                Files.walkFileTree(entry.path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                count++
            }
        }
        return count
    }
}

/**
 * Per-file progress reporting for the export: cancellation check, current file name, and
 * fraction over the precounted total. All calls are no-ops when the export runs without an
 * indicator (small selections, off-EDT requests, tests).
 */
internal class ExportProgress(private val indicator: ProgressIndicator?, private val total: Int) {
    private var done = 0

    fun step(name: String) {
        val indicator = indicator ?: return
        indicator.checkCanceled()
        indicator.text2 = name
        done++
        if (total > 0) {
            indicator.isIndeterminate = false
            indicator.fraction = done.toDouble() / total
        }
    }
}

/**
 * Temp directory holding archive entries extracted for one drag-out. Deletion follows the
 * same two-tier scheme as the other `turtle-*` temp dirs: delete-on-exit for normal JVM
 * shutdown (the drop target's copy has long finished by then) plus the [VfsTempCleanup]
 * stale sweep as the crash safety net — the extraction can't be deleted eagerly because
 * there is no callback for when the drop target has finished reading the files.
 */
private fun createDndTempDir(): Path {
    VfsTempCleanup.cleanupOnce()
    val dir = Files.createTempDirectory("turtle-dnd-")
    dir.toFile().deleteOnExit()
    return dir
}

private fun extractEntryForExport(entry: FileEntry, tempDir: Path, progress: ExportProgress): File {
    val target = resolveEntryPath(tempDir, entry.name)
        ?: throw IOException("Cannot export archive entry: ${entry.name}")
    if (Files.isDirectory(entry.path)) {
        copyTreeForExport(entry.path, target, progress)
    } else {
        progress.step(entry.name)
        Files.copy(entry.path, target, StandardCopyOption.REPLACE_EXISTING)
        target.toFile().deleteOnExit()
    }
    return target.toFile()
}

/**
 * Recursive copy from an archive filesystem to the export temp dir. Parents are registered
 * for delete-on-exit before their children — deletion runs in reverse registration order,
 * so files go first and the directories are empty when their turn comes. Relative names are
 * funneled through [resolveEntryPath] like a real extraction, so hostile entry names can't
 * escape the temp dir and characters illegal on the host OS are sanitized.
 */
private fun copyTreeForExport(source: Path, target: Path, progress: ExportProgress) {
    Files.createDirectories(target)
    target.toFile().deleteOnExit()
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (dir == source) return FileVisitResult.CONTINUE
            val resolved = resolveChild(dir) ?: return FileVisitResult.SKIP_SUBTREE
            Files.createDirectories(resolved)
            resolved.toFile().deleteOnExit()
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            resolveChild(file)?.let {
                progress.step(file.fileName.toString())
                Files.copy(file, it, StandardCopyOption.REPLACE_EXISTING)
                it.toFile().deleteOnExit()
            }
            return FileVisitResult.CONTINUE
        }

        private fun resolveChild(path: Path): Path? =
            resolveEntryPath(target, source.relativize(path).toString())
    })
}
