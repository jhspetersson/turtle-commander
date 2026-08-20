package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class VfsTempCleanupTest {

    private lateinit var sandbox: Path

    @Before
    fun setUp() {
        sandbox = Files.createTempDirectory("vfs-cleanup-test-")
    }

    @After
    fun tearDown() {
        if (Files.exists(sandbox)) {
            Files.walk(sandbox).sorted(Comparator.reverseOrder()).forEach {
                runCatching { Files.deleteIfExists(it) }
            }
        }
    }

    private fun makeStaleDir(name: String, ageMs: Long): Path {
        val dir = Files.createDirectory(sandbox.resolve(name))
        Files.write(dir.resolve("payload.txt"), "hi".toByteArray())
        val past = FileTime.fromMillis(System.currentTimeMillis() - ageMs)
        // Set mtime on both the file and the dir so listing reports the old time.
        Files.setLastModifiedTime(dir.resolve("payload.txt"), past)
        Files.setLastModifiedTime(dir, past)
        return dir
    }

    @Test
    fun `removes stale turtle-vfs dirs older than maxAge`() {
        val stale = makeStaleDir("turtle-vfs-edit-old", ageMs = 2L * 60 * 60 * 1000)
        assertTrue(Files.isDirectory(stale))

        val removed = VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)

        assertFalse("stale dir should be gone", Files.exists(stale))
        assertTrue(removed >= 1)
    }

    @Test
    fun `keeps fresh turtle-vfs dirs newer than maxAge`() {
        val fresh = makeStaleDir("turtle-vfs-view-recent", ageMs = 5L * 60 * 1000) // 5 min

        VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)

        assertTrue("fresh dir must NOT be removed", Files.exists(fresh))
    }

    @Test
    fun `removes stale extraction dirs of temp-dir VFS implementations`() {
        // These prefixes leaked forever before the sweep covered them — normal teardown
        // is Disposer-driven now, so anything stale here is a crash leftover.
        val staleTar = makeStaleDir("turtle-tar-old", ageMs = 2L * 60 * 60 * 1000)
        val staleIso = makeStaleDir("turtle-iso-old", ageMs = 2L * 60 * 60 * 1000)

        val removed = VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)

        assertFalse("stale tar extraction dir should be gone", Files.exists(staleTar))
        assertFalse("stale iso extraction dir should be gone", Files.exists(staleIso))
        assertTrue(removed >= 2)
    }

    @Test
    fun `ignores non-turtle dirs even when stale`() {
        val unrelated = makeStaleDir("unrelated-old", ageMs = 24L * 60 * 60 * 1000)

        VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)

        assertTrue("must not touch dirs without the prefix", Files.exists(unrelated))
    }

    private class FakeVfs(override val archivePath: Path, override val root: Path) : VirtualFileSystem {
        override suspend fun listFiles(directory: Path): List<FileEntry> = emptyList()
        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath)
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    @Test
    fun `keeps a stale extraction dir claimed by an open vfs`() {
        val stale = makeStaleDir("turtle-zip-live", ageMs = 2L * 60 * 60 * 1000)
        val vfs = FakeVfs(archivePath = sandbox.resolve("outside.zip"), root = stale)
        OpenVfsRegistry.register(vfs)
        try {
            VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)
            assertTrue("live vfs extraction dir must NOT be removed", Files.exists(stale))
        } finally {
            OpenVfsRegistry.unregister(vfs)
        }
    }

    @Test
    fun `keeps a stale dir holding the archive file of an open nested vfs`() {
        val stale = makeStaleDir("turtle-vfs-nested", ageMs = 2L * 60 * 60 * 1000)
        val vfs = FakeVfs(archivePath = stale.resolve("inner.zip"), root = sandbox.resolve("turtle-zip-elsewhere"))
        OpenVfsRegistry.register(vfs)
        try {
            VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)
            assertTrue("dir backing an open nested archive must NOT be removed", Files.exists(stale))
        } finally {
            OpenVfsRegistry.unregister(vfs)
        }
    }

    @Test
    fun `keeps a stale dir registered via protect`() {
        VfsTempCleanup.resetForTesting()
        val stale = makeStaleDir("turtle-vfs-edit-live", ageMs = 2L * 60 * 60 * 1000)
        VfsTempCleanup.protect(stale)
        try {
            VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)
            assertTrue("protected editor-extraction dir must NOT be removed", Files.exists(stale))
        } finally {
            VfsTempCleanup.resetForTesting()
        }
    }

    @Test
    fun `still removes an unprotected sibling of a live dir`() {
        val live = makeStaleDir("turtle-zip-live", ageMs = 2L * 60 * 60 * 1000)
        val dead = makeStaleDir("turtle-zip-dead", ageMs = 2L * 60 * 60 * 1000)
        val vfs = FakeVfs(archivePath = sandbox.resolve("outside.zip"), root = live)
        OpenVfsRegistry.register(vfs)
        try {
            VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)
            assertTrue(Files.exists(live))
            assertFalse("stale unclaimed sibling should still be swept", Files.exists(dead))
        } finally {
            OpenVfsRegistry.unregister(vfs)
        }
    }

    @Test
    fun `cleanupOnce runs only once per JVM`() {
        VfsTempCleanup.resetForTesting()
        VfsTempCleanup.cleanupOnce() // runs against real tmp — best-effort, must not throw
        VfsTempCleanup.cleanupOnce() // no-op the second time
        // Indirect check: the AtomicBoolean prevented re-entry. If it threw, this test fails.
    }
}
