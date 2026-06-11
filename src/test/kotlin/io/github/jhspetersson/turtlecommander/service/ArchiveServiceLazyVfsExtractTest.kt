package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.vfs.AbstractTempDirVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression test for extracting archives backed by a lazily-materialising VFS
 * (the real-world case is `.iso`): [ArchiveService.extractArchiveWithProgress] used to
 * `Files.copy` the temp-dir entries directly, which for a lazy VFS copies the sparse
 * zero-filled stubs instead of the real content. The fix routes each file through
 * [io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry.materializeIfNeeded] first.
 *
 * A real ISO image can't be authored in a test (the reader library is read-only), so the
 * lazy behaviour is reproduced with a minimal stub-based VFS registered under its own
 * fake extension.
 */
class ArchiveServiceLazyVfsExtractTest {

    companion object {
        private const val EXT = "lazystubtest"
        private const val ENTRY_NAME = "payload.bin"
        private val REAL_CONTENT = "real bytes, not zeros".toByteArray()

        init {
            VirtualFileSystemRegistry.register(LazyStubProvider())
        }
    }

    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (path in tempPaths.reversed()) {
            runCatching {
                if (Files.isDirectory(path)) {
                    Files.walk(path).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                } else {
                    Files.deleteIfExists(path)
                }
            }
        }
        tempPaths.clear()
    }

    @Test
    fun `extract materializes lazy VFS entries instead of copying sparse stubs`() {
        val archive = Files.createTempFile("lazy-extract-", ".$EXT").also { tempPaths.add(it) }
        val destination = Files.createTempDirectory("lazy-extract-dest-").also { tempPaths.add(it) }

        val errors = mutableListOf<Pair<Path, Exception>>()
        runBlocking {
            ArchiveService().extractArchiveWithProgress(
                archivePath = archive,
                destination = destination,
                initialPolicy = OverwritePolicy.OVERWRITE_ALL,
                onProgress = { _, _ -> },
                onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
                onError = { path, e -> errors.add(path to e) },
                isCancelled = { false },
            )
        }

        assertTrue("extraction reported errors: $errors", errors.isEmpty())
        val extracted = destination.resolve(ENTRY_NAME)
        assertTrue("expected $ENTRY_NAME to be extracted", Files.exists(extracted))
        assertArrayEquals(
            "extracted content must be the materialized bytes, not the zero-filled stub",
            REAL_CONTENT,
            Files.readAllBytes(extracted),
        )
    }

    private class LazyStubProvider : VirtualFileSystemProvider {
        override fun supportsExtension(ext: String): Boolean = ext == EXT
        override fun create(archivePath: Path): VirtualFileSystem = LazyStubVfs(archivePath)
    }

    /**
     * Mimics [io.github.jhspetersson.turtlecommander.vfs.IsoVirtualFileSystem]'s contract:
     * extract() populates the temp dir with a zero-filled stub of the right size, and only
     * materialize() replaces it with the real bytes.
     */
    private class LazyStubVfs(
        override val archivePath: Path,
    ) : AbstractTempDirVirtualFileSystem("turtle-lazystub-test-") {

        override val isReadOnly: Boolean get() = true

        private val pending = HashSet<Path>()

        init {
            openTempDir()
        }

        override fun extract(into: Path) {
            val stub = into.resolve(ENTRY_NAME)
            Files.write(stub, ByteArray(REAL_CONTENT.size))
            pending.add(stub.normalize())
        }

        @Synchronized
        override fun materialize(path: Path) {
            val normalized = path.normalize()
            if (pending.remove(normalized)) {
                Files.write(normalized, REAL_CONTENT)
            }
        }
    }
}
