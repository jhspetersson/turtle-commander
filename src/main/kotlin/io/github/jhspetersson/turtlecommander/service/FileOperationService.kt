package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.model.DirectoryType
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.util.readFileGroup
import io.github.jhspetersson.turtlecommander.util.readFileOwner
import io.github.jhspetersson.turtlecommander.util.readFilePermissions
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.Files.walkFileTree
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class FileOperationService(
    private val cs: CoroutineScope,
) {
    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac")

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
                        val owner = readOwner(entry)
                        val group = readGroup(entry)
                        val permissions = readPermissions(entry)
                        val dirType = if (attrs.isDirectory) detectDirectoryType(entry) else DirectoryType.NONE
                        val fileEntry = FileEntry(
                            name = entry.name,
                            path = entry,
                            isDirectory = attrs.isDirectory,
                            size = attrs.size(),
                            creationTime = attrs.creationTime(),
                            lastModified = attrs.lastModifiedTime(),
                            owner = owner,
                            group = group,
                            permissions = permissions,
                            directoryType = dirType,
                        )
                        if (attrs.isDirectory) dirs.add(fileEntry) else files.add(fileEntry)
                    } catch (e: Exception) {
                        thisLogger().debug("Cannot read attributes for $entry: ${e.message}")
                    }
                }

                val sortWithDirs = TurtleCommanderSettings.getInstance().state.sortWithDirectories
                if (sortWithDirs) {
                    val all = (dirs + files).sortedBy { it.name.lowercase() }
                    result.addAll(all)
                } else {
                    dirs.sortBy { it.name.lowercase() }
                    files.sortBy { it.name.lowercase() }
                    result.addAll(dirs)
                    result.addAll(files)
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Cannot list directory $directory: ${e.message}")
        }

        result
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
                    val copied = copyFileWithOverwrite(
                        source, target, autoOverwrite, autoSkip, onOverwriteConfirm,
                        { autoOverwrite = true },
                        { autoSkip = true },
                    )
                    if (copied) {
                        copiedCount++
                        onProgress(copiedCount, source.name)
                    }
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to copy $source to $destination: ${e.message}")
                onError(source, e)
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
    ): Boolean {
        if (target.exists()) {
            if (autoOverwrite) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                return true
            }
            if (autoSkip) return false

            when (onOverwriteConfirm(target)) {
                OverwriteResponse.YES -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                OverwriteResponse.NO -> return false
                OverwriteResponse.YES_TO_ALL -> {
                    setAutoOverwrite()
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
                OverwriteResponse.NO_TO_ALL -> {
                    setAutoSkip()
                    return false
                }
            }
        } else {
            Files.copy(source, target)
        }
        return true
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
            copiedCount++
            onProgress(copiedCount, source.name)
        } catch (e: Exception) {
            thisLogger().warn("Failed to create directory $target: ${e.message}")
            onError(source, e)
            return copiedCount
        }

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
                            val copied = copyFileWithOverwrite(
                                entry, entryTarget,
                                currentAutoOverwrite, currentAutoSkip,
                                onOverwriteConfirm,
                                { currentAutoOverwrite = true; setAutoOverwrite() },
                                { currentAutoSkip = true; setAutoSkip() },
                            )
                            if (copied) {
                                copiedCount++
                                onProgress(copiedCount, entry.name)
                            }
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to copy $entry: ${e.message}")
                            onError(entry, e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to list directory $source: ${e.message}")
            onError(source, e)
        }
        return copiedCount
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
                        continue
                    }
                    if (!autoOverwrite) {
                        when (onOverwriteConfirm(target)) {
                            OverwriteResponse.YES -> {}
                            OverwriteResponse.NO -> {
                                continue
                            }
                            OverwriteResponse.YES_TO_ALL -> { autoOverwrite = true }
                            OverwriteResponse.NO_TO_ALL -> {
                                autoSkip = true
                                continue
                            }
                        }
                    }
                    crossFileSystemMove(source, target, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    crossFileSystemMove(source, target)
                }
                movedCount++
                onProgress(movedCount, source.name)
            } catch (e: Exception) {
                thisLogger().warn("Failed to move $source to $destination: ${e.message}")
                onError(source, e)
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
                    // Collect the file tree first to avoid runBlocking inside walkFileTree callbacks
                    val entries = mutableListOf<Path>()
                    val walkErrors = mutableListOf<Pair<Path, IOException>>()
                    walkFileTree(path, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            entries.add(file)
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            walkErrors.add(file to exc)
                            return FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            if (exc != null) {
                                walkErrors.add(dir to exc)
                            }
                            entries.add(dir)
                            return FileVisitResult.CONTINUE
                        }
                    })
                    for ((errorPath, errorExc) in walkErrors) {
                        onError(errorPath, errorExc)
                    }
                    for (entry in entries) {
                        if (isCancelled()) break
                        try {
                            Files.delete(entry)
                            deletedCount++
                            onProgress(deletedCount, entry.name)
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to delete $entry: ${e.message}")
                            onError(entry, e)
                        }
                    }
                } else {
                    try {
                        Files.deleteIfExists(path)
                        deletedCount++
                        onProgress(deletedCount, path.name)
                    } catch (e: Exception) {
                        thisLogger().warn("Failed to delete $path: ${e.message}")
                        onError(path, e)
                    }
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to process $path: ${e.message}")
                onError(path, e)
            }
        }
    }

    suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        Files.move(source, target)
    }

    suspend fun createDirectory(parent: Path, name: String): Path = withContext(Dispatchers.IO) {
        Files.createDirectory(parent.resolve(name))
    }

    fun getRoots(): List<String> {
        return if (isWindows) {
            val roots = File.listRoots().map { it.absolutePath }.toMutableList()
            val home = System.getProperty("user.home")
            if (home != null) {
                val homePath = Path.of(home)
                if (homePath.exists() && homePath.isDirectory()) {
                    roots.add(homePath.toString())
                }
            }
            roots
        } else {
            val roots = mutableListOf("/")

            val home = System.getProperty("user.home")
            if (home != null) {
                val homePath = Path.of(home)
                if (homePath.exists() && homePath.isDirectory()) {
                    roots.add(homePath.toString())
                }
            }

            if (isMac) {
                addSubdirectories(roots, Path.of("/Volumes"))
            } else {
                addSubdirectories(roots, Path.of("/media"))
                addSubdirectories(roots, Path.of("/mnt"))
            }

            roots
        }
    }

    private fun addSubdirectories(roots: MutableList<String>, dir: Path) {
        if (dir.exists() && dir.isDirectory()) {
            try {
                Files.newDirectoryStream(dir).use { stream ->
                    stream.filter { it.isDirectory() }.forEach { roots.add(it.toString()) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readOwner(path: Path): String = readFileOwner(path)
    private fun readGroup(path: Path): String = readFileGroup(path)
    private fun readPermissions(path: Path): String = readFilePermissions(path, isWindows)

    private fun copyDirectoryRecursive(source: Path, target: Path) {
        walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relativePath = source.relativize(dir).toString()
                val targetDir = if (relativePath.isEmpty()) target else target.resolve(relativePath)
                Files.createDirectories(targetDir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relativePath = source.relativize(file).toString()
                Files.copy(file, target.resolve(relativePath), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                thisLogger().warn("Failed to copy $file: ${exc.message}")
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun crossFileSystemMove(source: Path, target: Path, vararg options: CopyOption) {
        if (source.fileSystem == target.fileSystem) {
            Files.move(source, target, *options)
        } else {
            val replaceExisting = StandardCopyOption.REPLACE_EXISTING in options
            if (source.isDirectory()) {
                copyDirectoryRecursive(source, target)
                deleteDirectoryRecursive(source)
            } else {
                if (replaceExisting) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.copy(source, target)
                }
                Files.delete(source)
            }
        }
    }

    private fun detectDirectoryType(dir: Path): DirectoryType {
        return try {
            when {
                // IntelliJ IDEA / JetBrains project
                Files.isDirectory(dir.resolve(".idea")) -> DirectoryType.IDEA_PROJECT
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
                // Git repository
                Files.isDirectory(dir.resolve(".git")) -> DirectoryType.GIT
                else -> DirectoryType.NONE
            }
        } catch (_: Exception) {
            DirectoryType.NONE
        }
    }

    private fun deleteDirectoryRecursive(path: Path) {
        walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                thisLogger().warn("Failed to delete $file: ${exc.message}")
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    thisLogger().warn("Failed to fully iterate $dir: ${exc.message}")
                }
                try {
                    Files.delete(dir)
                } catch (e: Exception) {
                    thisLogger().warn("Failed to delete directory $dir: ${e.message}")
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}

