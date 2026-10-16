package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class TempRootLocksDeleteTreeTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("turtle-ro-test-")
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { makeWritable(it); Files.deleteIfExists(it) }
            }
        }
    }

    private fun supportsDos(): Boolean = Files.getFileStore(root).supportsFileAttributeView("dos")

    private fun supportsPosix(): Boolean = Files.getFileStore(root).supportsFileAttributeView("posix")

    private fun makeReadOnly(path: Path) {
        if (supportsDos()) Files.setAttribute(path, "dos:readonly", true)
        if (supportsPosix()) {
            val perms = if (Files.isDirectory(path)) "r-xr-xr-x" else "r--r--r--"
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
        }
    }

    private fun makeWritable(path: Path) {
        if (supportsDos()) runCatching { Files.setAttribute(path, "dos:readonly", false) }
        if (supportsPosix()) runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx")) }
    }

    @Test
    fun `deleteTree removes a root holding a read-only file`() {
        val file = Files.writeString(Files.createDirectories(root.resolve("sub")).resolve("ro.txt"), "x")
        makeReadOnly(file)

        assertTrue(TempRootLocks.deleteTree(root))

        assertFalse(Files.exists(root))
    }

    @Test
    fun `deleteTree removes a root holding a non-writable directory with children`() {
        val dir = Files.createDirectories(root.resolve("locked"))
        Files.writeString(dir.resolve("child.txt"), "x")
        Files.createDirectories(dir.resolve("nested"))
        makeReadOnly(dir)

        assertTrue(TempRootLocks.deleteTree(root))

        assertFalse(Files.exists(root))
    }

    @Test
    fun `discard removes a claimed root whose content is read-only`() {
        TempRootLocks.claim(root)
        val file = Files.writeString(root.resolve("ro.txt"), "x")
        makeReadOnly(file)

        assertTrue(TempRootLocks.discard(root))

        assertFalse(Files.exists(root))
        assertFalse(Files.exists(TempRootLocks.lockFileFor(root)))
    }
}
