package io.github.jhspetersson.turtlecommander.vfs

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SharedVfsRegistryTest {

    private fun createZip(name: String): Path {
        val dir = Files.createTempDirectory("shared-vfs-test-")
        val zip = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("content".toByteArray())
            zos.closeEntry()
        }
        return zip
    }

    @Test
    fun acquireReturnsSameInstanceForSamePath() {
        val zip = createZip("same.zip")
        val vfs1 = SharedVfsRegistry.acquire(zip)
        val vfs2 = SharedVfsRegistry.acquire(zip)
        try {
            assertSame(vfs1, vfs2)
        } finally {
            SharedVfsRegistry.release(vfs1)
            SharedVfsRegistry.release(vfs2)
        }
    }

    @Test
    fun instanceSurvivesFirstReleaseAndClosesOnLast() {
        val zip = createZip("refcount.zip")
        val vfs1 = SharedVfsRegistry.acquire(zip)
        SharedVfsRegistry.acquire(zip)
        assertTrue(SharedVfsRegistry.release(vfs1))
        assertTrue(Files.exists(vfs1.getPath("/a.txt")))
        assertTrue(SharedVfsRegistry.release(vfs1))
        val vfs3 = SharedVfsRegistry.acquire(zip)
        try {
            assertNotSame(vfs1, vfs3)
        } finally {
            SharedVfsRegistry.release(vfs3)
        }
    }

    @Test
    fun releaseReturnsFalseForUnknownInstance() {
        val zip = createZip("unknown.zip")
        val privateVfs = VirtualFileSystemRegistry.create(zip)
        try {
            assertFalse(SharedVfsRegistry.release(privateVfs))
        } finally {
            privateVfs.close()
        }
    }

    @Test
    fun mutationListenersNotifyOthersButNotExcluded() {
        val zip = createZip("listen.zip")
        val vfs = SharedVfsRegistry.acquire(zip)
        try {
            var aCount = 0
            var bCount = 0
            val a: () -> Unit = { aCount++ }
            val b: () -> Unit = { bCount++ }
            SharedVfsRegistry.addMutationListener(vfs, a)
            SharedVfsRegistry.addMutationListener(vfs, b)
            SharedVfsRegistry.notifyMutated(vfs, except = a)
            assertEquals(0, aCount)
            assertEquals(1, bCount)
            SharedVfsRegistry.removeMutationListener(vfs, b)
            SharedVfsRegistry.notifyMutated(vfs)
            assertEquals(1, aCount)
            assertEquals(1, bCount)
        } finally {
            SharedVfsRegistry.release(vfs)
        }
    }

    @Test
    fun differentCasePathsShareWhenFilesystemIsCaseInsensitive() {
        val zip = createZip("case.zip")
        val upper = zip.parent.resolve(zip.fileName.toString().uppercase())
        if (!Files.exists(upper)) return
        val vfs1 = SharedVfsRegistry.acquire(zip)
        val vfs2 = SharedVfsRegistry.acquire(upper)
        try {
            assertSame(vfs1, vfs2)
        } finally {
            SharedVfsRegistry.release(vfs1)
            SharedVfsRegistry.release(vfs2)
        }
    }
    private class SlowCloseVfs(override val archivePath: Path) : VirtualFileSystem {
        override val root: Path = archivePath.parent
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        @Volatile var closed = false
        override suspend fun listFiles(directory: Path): List<FileEntry> = emptyList()
        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.trimStart('/'))
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {
            closeEntered.countDown()
            allowClose.await(10, TimeUnit.SECONDS)
            closed = true
        }
    }

    private object SlowCloseProvider : VirtualFileSystemProvider {
        val created = mutableListOf<SlowCloseVfs>()
        val previousClosedAtCreate = mutableListOf<Boolean>()
        override fun supportsExtension(ext: String): Boolean = ext == "tcslowclose"
        override fun create(archivePath: Path): VirtualFileSystem = synchronized(this) {
            previousClosedAtCreate += created.lastOrNull()?.closed ?: true
            SlowCloseVfs(archivePath).also { created += it }
        }

        init {
            VirtualFileSystemRegistry.register(this)
        }
    }

    @Test
    fun acquireWaitsForAnInFlightCloseOfTheSameArchive() {
        val archive = Files.createTempDirectory("shared-vfs-test-").resolve("a.tcslowclose")
        Files.write(archive, byteArrayOf(1))
        SlowCloseProvider.created.clear()
        SlowCloseProvider.previousClosedAtCreate.clear()
        val first = SharedVfsRegistry.acquire(archive) as SlowCloseVfs
        val releaser = thread { SharedVfsRegistry.release(first) }
        assertTrue(first.closeEntered.await(10, TimeUnit.SECONDS))
        var second: VirtualFileSystem? = null
        val acquirer = thread { second = SharedVfsRegistry.acquire(archive) }
        acquirer.join(300)
        first.allowClose.countDown()
        releaser.join(10_000)
        acquirer.join(10_000)
        try {
            assertNotSame(first, second)
            assertEquals(listOf(true, true), SlowCloseProvider.previousClosedAtCreate)
        } finally {
            (second as? SlowCloseVfs)?.allowClose?.countDown()
            second?.let { SharedVfsRegistry.release(it) }
        }
    }
}
