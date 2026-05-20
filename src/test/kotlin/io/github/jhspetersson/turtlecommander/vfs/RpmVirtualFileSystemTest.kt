package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class RpmVirtualFileSystemTest {

    private lateinit var rpmPath: Path
    private var vfs: RpmVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::rpmPath.isInitialized) Files.deleteIfExists(rpmPath)
    }

    private data class CpioFile(val name: String, val content: ByteArray, val mode: Long = 0x81A4L) // 0100644

    /** Builds a cpio archive (newc format, what RPM uses) from [files]. */
    private fun buildCpio(files: List<CpioFile>): ByteArray {
        val out = ByteArrayOutputStream()
        CpioArchiveOutputStream(out, CpioConstants.FORMAT_NEW).use { cpio ->
            for (f in files) {
                val entry = CpioArchiveEntry(CpioConstants.FORMAT_NEW, "./${f.name}", f.content.size.toLong())
                entry.mode = f.mode
                cpio.putArchiveEntry(entry)
                cpio.write(f.content)
                cpio.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun compress(payload: ByteArray, compressor: String): ByteArray {
        val out = ByteArrayOutputStream()
        val wrapped: OutputStream = when (compressor) {
            "gzip" -> GzipCompressorOutputStream(out)
            "bzip2" -> BZip2CompressorOutputStream(out)
            "xz" -> XZCompressorOutputStream(out)
            "zstd" -> ZstdCompressorOutputStream(out)
            else -> throw IllegalArgumentException("unknown compressor $compressor")
        }
        wrapped.use { it.write(payload) }
        return out.toByteArray()
    }

    /**
     * Builds a minimal but standards-compliant RPM. Both headers carry a real intro;
     * the signature header is empty (0 entries, 0 data store), and the main header
     * carries a single PAYLOADCOMPRESSOR string when [compressor] is non-null. When
     * [compressor] is null the tag is omitted entirely so we can exercise the
     * legacy gzip-default code path.
     */
    private fun buildRpm(compressor: String?, files: List<CpioFile>, payloadCompressor: String = compressor ?: "gzip"): Path {
        val path = Files.createTempFile("test-rpm-", ".rpm")
        val cpio = buildCpio(files)
        val payload = compress(cpio, payloadCompressor)

        Files.newOutputStream(path).use { os ->
            val dos = DataOutputStream(os)
            // Lead: magic + 92 zero bytes.
            dos.write(byteArrayOf(0xed.toByte(), 0xab.toByte(), 0xee.toByte(), 0xdb.toByte()))
            dos.write(ByteArray(92))

            // Signature header: empty. Intro = magic(3) + version(1) + reserved(4) + nindex(4=0) + hsize(4=0).
            writeHeaderIntro(dos, nindex = 0, hsize = 0)
            // 16 bytes consumed → padding to 8-byte boundary = 0 bytes.

            // Main header.
            if (compressor != null) {
                val tagName = compressor.toByteArray(Charsets.UTF_8) + 0.toByte()
                writeHeaderIntro(dos, nindex = 1, hsize = tagName.size)
                // Index entry: tag=1125, type=6 (STRING), offset=0, count=1
                dos.write(beUInt32(1125))
                dos.write(beUInt32(6))
                dos.write(beUInt32(0))
                dos.write(beUInt32(1))
                // Data store
                dos.write(tagName)
            } else {
                writeHeaderIntro(dos, nindex = 0, hsize = 0)
            }

            // Payload.
            dos.write(payload)
        }
        return path
    }

    private fun writeHeaderIntro(dos: DataOutputStream, nindex: Int, hsize: Int) {
        dos.write(byteArrayOf(0x8e.toByte(), 0xad.toByte(), 0xe8.toByte(), 0x01))
        dos.write(ByteArray(4)) // reserved
        dos.write(beUInt32(nindex))
        dos.write(beUInt32(hsize))
    }

    private fun beUInt32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    )

    private fun sampleFiles() = listOf(
        CpioFile("usr/bin/hello", "#!/bin/sh\necho hi\n".toByteArray()),
        CpioFile("etc/hello.conf", "verbose=true\n".toByteArray()),
    )

    @Test
    fun `gzip-compressed rpm extracts cpio entries`() = runBlocking {
        rpmPath = buildRpm(compressor = "gzip", files = sampleFiles())
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs

        val rootEntries = rpmVfs.listFiles(rpmVfs.root).filter { !it.isParentLink }
        // Top-level dirs (usr, etc) are created implicitly by Files.createDirectories.
        assertNotNull(rootEntries.find { it.name == "usr" })
        assertNotNull(rootEntries.find { it.name == "etc" })

        val confPath = rpmVfs.getPath("/etc/hello.conf")
        assertEquals("verbose=true\n", Files.readString(confPath))

        val binPath = rpmVfs.getPath("/usr/bin/hello")
        assertTrue(Files.readString(binPath).startsWith("#!/bin/sh"))
    }

    @Test
    fun `xz-compressed rpm extracts cpio entries`() = runBlocking {
        rpmPath = buildRpm(compressor = "xz", files = sampleFiles())
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs

        assertEquals("verbose=true\n", Files.readString(rpmVfs.getPath("/etc/hello.conf")))
    }

    @Test
    fun `bzip2-compressed rpm extracts cpio entries`() = runBlocking {
        rpmPath = buildRpm(compressor = "bzip2", files = sampleFiles())
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs

        assertEquals("verbose=true\n", Files.readString(rpmVfs.getPath("/etc/hello.conf")))
    }

    @Test
    fun `zstd-compressed rpm extracts cpio entries`() = runBlocking {
        rpmPath = buildRpm(compressor = "zstd", files = sampleFiles())
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs

        assertEquals("verbose=true\n", Files.readString(rpmVfs.getPath("/etc/hello.conf")))
    }

    @Test
    fun `missing payload compressor tag defaults to gzip`() = runBlocking {
        // compressor=null omits the tag from the main header; payload still gzipped.
        rpmPath = buildRpm(compressor = null, files = sampleFiles(), payloadCompressor = "gzip")
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs

        assertEquals("verbose=true\n", Files.readString(rpmVfs.getPath("/etc/hello.conf")))
    }

    @Test
    fun `rpm vfs is read-only`() {
        rpmPath = buildRpm(compressor = "gzip", files = sampleFiles())
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs
        assertTrue(rpmVfs.isReadOnly)
    }

    @Test
    fun `rpm vfs root parent link points to archive parent`() = runBlocking {
        rpmPath = buildRpm(compressor = "gzip", files = sampleFiles())
        val rpmVfs = RpmVirtualFileSystem(rpmPath)
        vfs = rpmVfs
        val entries = rpmVfs.listFiles(rpmVfs.root)
        val parentLink = entries.find { it.isParentLink }
        assertNotNull(parentLink)
        assertEquals(rpmPath.parent, parentLink!!.path)
    }

    @Test
    fun `non-rpm file is rejected`() {
        rpmPath = Files.createTempFile("not-an-rpm-", ".rpm")
        Files.write(rpmPath, "this is not an rpm file at all".toByteArray())
        try {
            RpmVirtualFileSystem(rpmPath)
            fail("Expected construction to fail for a non-RPM file")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `unknown compressor surfaces a clear error`() {
        rpmPath = buildRpm(compressor = "lzma2-imaginary", files = sampleFiles(), payloadCompressor = "gzip")
        try {
            RpmVirtualFileSystem(rpmPath)
            fail("Expected construction to fail for an unknown compressor")
        } catch (e: Exception) {
            // The IOException is wrapped by openTempDir's rethrow path; walk the cause chain.
            val messages = generateSequence(e as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(
                "expected message mentioning compressor; got: $messages",
                messages.any { "Unsupported RPM payload compressor" in it },
            )
        }
    }
}
