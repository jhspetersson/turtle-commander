package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
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
}
