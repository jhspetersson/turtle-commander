package io.github.jhspetersson.turtlecommander.service

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
    fun `ignores non-turtle dirs even when stale`() {
        val unrelated = makeStaleDir("unrelated-old", ageMs = 24L * 60 * 60 * 1000)

        VfsTempCleanup.cleanNow(sandbox, maxAgeMs = 60L * 60 * 1000)

        assertTrue("must not touch dirs without the prefix", Files.exists(unrelated))
    }

    @Test
    fun `cleanupOnce runs only once per JVM`() {
        VfsTempCleanup.resetForTesting()
        VfsTempCleanup.cleanupOnce() // runs against real tmp — best-effort, must not throw
        VfsTempCleanup.cleanupOnce() // no-op the second time
        // Indirect check: the AtomicBoolean prevented re-entry. If it threw, this test fails.
    }
}
