package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class FileOperationService(
    @Suppress("unused") private val project: Project,
    private val cs: CoroutineScope,
) {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(block = block)

    suspend fun listFiles(directory: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry>()

        val parent = directory.parent
        if (parent != null) {
            result.add(
                FileEntry(
                    name = "..",
                    path = parent,
                    isDirectory = true,
                    size = 0,
                    lastModified = null,
                    permissions = "",
                    isParentLink = true,
                )
            )
        }

        try {
            Files.newDirectoryStream(directory).use { stream ->
                val dirs = mutableListOf<FileEntry>()
                val files = mutableListOf<FileEntry>()

                for (entry in stream) {
                    try {
                        val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
                        val permissions = readPermissions(entry)
                        val dirType = if (attrs.isDirectory) detectDirectoryType(entry) else DirectoryType.NONE
                        val fileEntry = FileEntry(
                            name = entry.name,
                            path = entry,
                            isDirectory = attrs.isDirectory,
                            size = attrs.size(),
                            lastModified = attrs.lastModifiedTime(),
                            permissions = permissions,
                            directoryType = dirType,
                        )
                        if (attrs.isDirectory) dirs.add(fileEntry) else files.add(fileEntry)
                    } catch (e: Exception) {
                        thisLogger().debug("Cannot read attributes for $entry: ${e.message}")
                    }
                }

                dirs.sortBy { it.name.lowercase() }
                files.sortBy { it.name.lowercase() }
                result.addAll(dirs)
                result.addAll(files)
            }
        } catch (e: Exception) {
            thisLogger().warn("Cannot list directory $directory: ${e.message}")
        }

        result
    }

    enum class OverwriteResponse { YES, NO, YES_TO_ALL, NO_TO_ALL }

    suspend fun countFiles(sources: List<Path>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (source in sources) {
            if (source.isDirectory()) {
                Files.walkFileTree(source, object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                        count++
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                        count++
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            } else {
                count++
            }
        }
        count
    }

    suspend fun copyFilesWithProgress(
        sources: List<Path>,
        destination: Path,
        overwriteAll: Boolean,
        onProgress: suspend (copiedCount: Int, currentFile: String) -> Unit,
        onOverwriteConfirm: suspend (path: Path) -> OverwriteResponse,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        var copiedCount = 0
        var autoOverwrite = overwriteAll
        var autoSkip = false

        for (source in sources) {
            if (isCancelled()) break
            val target = destination.resolve(source.name)
            try {
                if (source.isDirectory()) {
                    copiedCount = copyDirectoryWithProgress(
                        source, target, copiedCount,
                        autoOverwrite, autoSkip,
                        onProgress, onOverwriteConfirm, onError, isCancelled,
                        { autoOverwrite = true },
                        { autoSkip = true },
                    )
                } else {
                    copyFileWithOverwrite(
                        source, target, autoOverwrite, autoSkip, onOverwriteConfirm,
                        { autoOverwrite = true },
                        { autoSkip = true },
                    )
                    copiedCount++
                    onProgress(copiedCount, source.name)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to copy $source to $destination: ${e.message}")
                onError(source, e)
                copiedCount++
                onProgress(copiedCount, source.name)
            }
        }
    }

    private suspend fun copyFileWithOverwrite(
        source: Path,
        target: Path,
        autoOverwrite: Boolean,
        autoSkip: Boolean,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
        setAutoOverwrite: () -> Unit,
        setAutoSkip: () -> Unit,
    ) {
        if (target.exists()) {
            if (autoOverwrite) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                return
            }
            if (autoSkip) return

            when (onOverwriteConfirm(target)) {
                OverwriteResponse.YES -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                OverwriteResponse.NO -> return
                OverwriteResponse.YES_TO_ALL -> {
                    setAutoOverwrite()
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
                OverwriteResponse.NO_TO_ALL -> {
                    setAutoSkip()
                    return
                }
            }
        } else {
            Files.copy(source, target)
        }
    }

    private suspend fun copyDirectoryWithProgress(
        source: Path,
        target: Path,
        startCount: Int,
        autoOverwrite: Boolean,
        autoSkip: Boolean,
        onProgress: suspend (Int, String) -> Unit,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
        onError: suspend (Path, Exception) -> Unit,
        isCancelled: () -> Boolean,
        setAutoOverwrite: () -> Unit,
        setAutoSkip: () -> Unit,
    ): Int {
        var copiedCount = startCount
        var currentAutoOverwrite = autoOverwrite
        var currentAutoSkip = autoSkip

        try {
            Files.createDirectories(target)
        } catch (e: Exception) {
            thisLogger().warn("Failed to create directory $target: ${e.message}")
            onError(source, e)
        }
        copiedCount++
        onProgress(copiedCount, source.name)

        try {
            Files.newDirectoryStream(source).use { stream ->
                for (entry in stream) {
                    if (isCancelled()) break
                    val entryTarget = target.resolve(entry.name)
                    if (entry.isDirectory()) {
                        copiedCount = copyDirectoryWithProgress(
                            entry, entryTarget, copiedCount,
                            currentAutoOverwrite, currentAutoSkip,
                            onProgress, onOverwriteConfirm, onError, isCancelled,
                            { currentAutoOverwrite = true; setAutoOverwrite() },
                            { currentAutoSkip = true; setAutoSkip() },
                        )
                    } else {
                        try {
                            copyFileWithOverwrite(
                                entry, entryTarget,
                                currentAutoOverwrite, currentAutoSkip,
                                onOverwriteConfirm,
                                { currentAutoOverwrite = true; setAutoOverwrite() },
                                { currentAutoSkip = true; setAutoSkip() },
                            )
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to copy $entry: ${e.message}")
                            onError(entry, e)
                        }
                        copiedCount++
                        onProgress(copiedCount, entry.name)
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to list directory $source: ${e.message}")
            onError(source, e)
        }
        return copiedCount
    }

    @Suppress("unused")
    suspend fun copyFiles(sources: List<Path>, destination: Path): Unit = withContext(Dispatchers.IO) {
        for (source in sources) {
            try {
                val target = destination.resolve(source.name)
                if (source.isDirectory()) {
                    copyDirectoryRecursive(source, target)
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to copy $source to $destination: ${e.message}")
                throw e
            }
        }
    }

    suspend fun moveFilesWithProgress(
        sources: List<Path>,
        destination: Path,
        overwriteAll: Boolean,
        onProgress: suspend (movedCount: Int, currentFile: String) -> Unit,
        onOverwriteConfirm: suspend (path: Path) -> OverwriteResponse,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        var movedCount = 0
        var autoOverwrite = overwriteAll
        var autoSkip = false

        for (source in sources) {
            if (isCancelled()) break
            val target = destination.resolve(source.name)
            try {
                if (target.exists()) {
                    if (autoSkip) {
                        movedCount++
                        onProgress(movedCount, source.name)
                        continue
                    }
                    if (!autoOverwrite) {
                        when (onOverwriteConfirm(target)) {
                            OverwriteResponse.YES -> {}
                            OverwriteResponse.NO -> {
                                movedCount++
                                onProgress(movedCount, source.name)
                                continue
                            }
                            OverwriteResponse.YES_TO_ALL -> { autoOverwrite = true }
                            OverwriteResponse.NO_TO_ALL -> {
                                autoSkip = true
                                movedCount++
                                onProgress(movedCount, source.name)
                                continue
                            }
                        }
                    }
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(source, target)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to move $source to $destination: ${e.message}")
                onError(source, e)
            }
            movedCount++
            onProgress(movedCount, source.name)
        }
    }

    @Suppress("unused")
    suspend fun moveFiles(sources: List<Path>, destination: Path): Unit = withContext(Dispatchers.IO) {
        for (source in sources) {
            try {
                val target = destination.resolve(source.name)
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                thisLogger().warn("Failed to move $source to $destination: ${e.message}")
                throw e
            }
        }
    }

    suspend fun deleteFilesWithProgress(
        paths: List<Path>,
        onProgress: suspend (deletedCount: Int, currentFile: String) -> Unit,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        var deletedCount = 0

        for (path in paths) {
            if (isCancelled()) break
            try {
                if (path.isDirectory()) {
                    Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                            if (isCancelled()) return java.nio.file.FileVisitResult.TERMINATE
                            try {
                                Files.delete(file)
                            } catch (e: Exception) {
                                thisLogger().warn("Failed to delete file $file: ${e.message}")
                                kotlinx.coroutines.runBlocking { onError(file, e) }
                            }
                            deletedCount++
                            kotlinx.coroutines.runBlocking { onProgress(deletedCount, file.name) }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                            if (isCancelled()) return java.nio.file.FileVisitResult.TERMINATE
                            try {
                                Files.delete(dir)
                            } catch (e: Exception) {
                                thisLogger().warn("Failed to delete directory $dir: ${e.message}")
                                kotlinx.coroutines.runBlocking { onError(dir, e) }
                            }
                            deletedCount++
                            kotlinx.coroutines.runBlocking { onProgress(deletedCount, dir.name) }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                    })
                } else {
                    try {
                        Files.deleteIfExists(path)
                    } catch (e: Exception) {
                        thisLogger().warn("Failed to delete $path: ${e.message}")
                        onError(path, e)
                    }
                    deletedCount++
                    onProgress(deletedCount, path.name)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to process $path: ${e.message}")
                onError(path, e)
            }
        }
    }

    @Suppress("unused")
    suspend fun deleteFiles(paths: List<Path>): Unit = withContext(Dispatchers.IO) {
        for (path in paths) {
            try {
                if (path.isDirectory()) {
                    deleteDirectoryRecursive(path)
                } else {
                    Files.deleteIfExists(path)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to delete $path: ${e.message}")
                throw e
            }
        }
    }

    suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val target = source.parent.resolve(newName)
        Files.move(source, target)
    }

    suspend fun createDirectory(parent: Path, name: String): Path = withContext(Dispatchers.IO) {
        Files.createDirectory(parent.resolve(name))
    }

    fun getRoots(): List<String> {
        return if (isWindows) {
            File.listRoots().map { it.absolutePath }
        } else {
            val roots = mutableListOf("/")
            val mediaDir = Path.of("/media")
            val mntDir = Path.of("/mnt")
            if (mediaDir.exists() && mediaDir.isDirectory()) {
                try {
                    Files.newDirectoryStream(mediaDir).use { stream ->
                        stream.filter { it.isDirectory() }.forEach { roots.add(it.toString()) }
                    }
                } catch (_: Exception) {
                }
            }
            if (mntDir.exists() && mntDir.isDirectory()) {
                try {
                    Files.newDirectoryStream(mntDir).use { stream ->
                        stream.filter { it.isDirectory() }.forEach { roots.add(it.toString()) }
                    }
                } catch (_: Exception) {
                }
            }
            roots
        }
    }

    private fun readPermissions(path: Path): String {
        return try {
            if (isWindows) {
                val attrs = Files.readAttributes(path, DosFileAttributes::class.java)
                buildString {
                    if (attrs.isReadOnly) append('R')
                    if (attrs.isHidden) append('H')
                    if (attrs.isSystem) append('S')
                    if (attrs.isArchive) append('A')
                }
            } else {
                val perms = Files.getPosixFilePermissions(path)
                PosixFilePermissions.toString(perms)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun copyDirectoryRecursive(source: Path, target: Path) {
        Files.walkFileTree(source, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                val targetDir = target.resolve(source.relativize(dir))
                Files.createDirectories(targetDir)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun detectDirectoryType(dir: Path): DirectoryType {
        return try {
            when {
                // IntelliJ IDEA / JetBrains project
                Files.isDirectory(dir.resolve(".idea")) -> DirectoryType.IDEA_PROJECT
                // Git repository
                Files.isDirectory(dir.resolve(".git")) -> DirectoryType.GIT
                // Gradle project
                Files.exists(dir.resolve("build.gradle.kts"))
                    || Files.exists(dir.resolve("build.gradle"))
                    || Files.exists(dir.resolve("settings.gradle.kts"))
                    || Files.exists(dir.resolve("settings.gradle")) -> DirectoryType.GRADLE
                // Maven project
                Files.exists(dir.resolve("pom.xml")) -> DirectoryType.MAVEN
                // Rust / Cargo project
                Files.exists(dir.resolve("Cargo.toml")) -> DirectoryType.CARGO
                // Node.js / npm project
                Files.exists(dir.resolve("package.json")) -> DirectoryType.NPM
                // Python project
                Files.exists(dir.resolve("pyproject.toml"))
                    || Files.exists(dir.resolve("setup.py"))
                    || Files.exists(dir.resolve("requirements.txt"))
                    || Files.isDirectory(dir.resolve(".venv"))
                    || Files.isDirectory(dir.resolve("venv")) -> DirectoryType.PYTHON
                // CMake project
                Files.exists(dir.resolve("CMakeLists.txt")) -> DirectoryType.CMAKE
                // .NET project
                dir.toFile().list()?.any {
                    it.endsWith(".sln") || it.endsWith(".csproj") || it.endsWith(".fsproj")
                } == true -> DirectoryType.DOTNET
                else -> DirectoryType.NONE
            }
        } catch (_: Exception) {
            DirectoryType.NONE
        }
    }

    private fun deleteDirectoryRecursive(path: Path) {
        Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
