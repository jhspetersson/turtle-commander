package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import javax.swing.JComponent
import javax.swing.TransferHandler

internal class FileEntryTransferHandler(private val tab: FileTab) : TransferHandler() {
    override fun getSourceActions(c: JComponent): Int = COPY

    override fun createTransferable(c: JComponent): Transferable? {
        val entries = tab.getSelectedEntries()
        if (entries.isEmpty()) return null
        return FileEntryTransferable(entries)
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

internal class FileEntryTransferable(private val entries: List<FileEntry>) : Transferable {
    private val supportedFlavors = arrayOf(FileTab.FILE_ENTRY_FLAVOR, DataFlavor.javaFileListFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in supportedFlavors
    override fun getTransferData(flavor: DataFlavor): Any {
        return when (flavor) {
            FileTab.FILE_ENTRY_FLAVOR -> entries
            DataFlavor.javaFileListFlavor -> entries.map { it.path.toFile() }
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
