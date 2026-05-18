package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CrxVirtualFileSystemTest {

    private lateinit var crxPath: Path
    private var vfs: CrxVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::crxPath.isInitialized) Files.deleteIfExists(crxPath)
    }

    private fun buildInnerZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"name":"test","version":"1.0"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("icons/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("icons/icon.png"))
            zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun leUInt32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private fun writeCrx3(headerPayloadSize: Int, innerZip: ByteArray): Path {
        val path = Files.createTempFile("test-crx3-", ".crx")
        Files.newOutputStream(path).use { os ->
            val dos = DataOutputStream(os)
            dos.writeBytes("Cr24")
            dos.write(leUInt32(3))
            dos.write(leUInt32(headerPayloadSize.toLong()))
            dos.write(ByteArray(headerPayloadSize))
            dos.write(innerZip)
        }
        return path
    }

    private fun writeCrx2(pubKey: ByteArray, signature: ByteArray, innerZip: ByteArray): Path {
        val path = Files.createTempFile("test-crx2-", ".crx")
        Files.newOutputStream(path).use { os ->
            val dos = DataOutputStream(os)
            dos.writeBytes("Cr24")
            dos.write(leUInt32(2))
            dos.write(leUInt32(pubKey.size.toLong()))
            dos.write(leUInt32(signature.size.toLong()))
            dos.write(pubKey)
            dos.write(signature)
            dos.write(innerZip)
        }
        return path
    }

    @Test
    fun `crx3 file extracts inner zip entries`() = runBlocking {
        crxPath = writeCrx3(headerPayloadSize = 64, innerZip = buildInnerZip())
        val crxVfs = CrxVirtualFileSystem(crxPath)
        vfs = crxVfs

        val rootEntries = crxVfs.listFiles(crxVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "manifest.json" })
        assertNotNull(rootEntries.find { it.name == "icons" })

        val manifestPath = crxVfs.getPath("/manifest.json")
        assertEquals("""{"name":"test","version":"1.0"}""", Files.readString(manifestPath))

        val iconEntries = crxVfs.listFiles(crxVfs.getPath("/icons")).filter { !it.isParentLink }
        assertNotNull(iconEntries.find { it.name == "icon.png" })
    }

    @Test
    fun `crx2 file extracts inner zip entries`() = runBlocking {
        crxPath = writeCrx2(
            pubKey = ByteArray(128) { it.toByte() },
            signature = ByteArray(64) { (it * 2).toByte() },
            innerZip = buildInnerZip(),
        )
        val crxVfs = CrxVirtualFileSystem(crxPath)
        vfs = crxVfs

        val rootEntries = crxVfs.listFiles(crxVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "manifest.json" })
        assertEquals(
            """{"name":"test","version":"1.0"}""",
            Files.readString(crxVfs.getPath("/manifest.json")),
        )
    }

    @Test
    fun `crx vfs is read-only`() {
        crxPath = writeCrx3(headerPayloadSize = 16, innerZip = buildInnerZip())
        val crxVfs = CrxVirtualFileSystem(crxPath)
        vfs = crxVfs
        assertTrue(crxVfs.isReadOnly)
    }

    @Test
    fun `crx vfs root parent link points to archive parent`() = runBlocking {
        crxPath = writeCrx3(headerPayloadSize = 16, innerZip = buildInnerZip())
        val crxVfs = CrxVirtualFileSystem(crxPath)
        vfs = crxVfs
        val entries = crxVfs.listFiles(crxVfs.root)
        val parentLink = entries.find { it.isParentLink }
        assertNotNull(parentLink)
        assertEquals(crxPath.parent, parentLink!!.path)
    }

    @Test
    fun `crx vfs archivePath returns original path`() {
        crxPath = writeCrx3(headerPayloadSize = 16, innerZip = buildInnerZip())
        val crxVfs = CrxVirtualFileSystem(crxPath)
        vfs = crxVfs
        assertEquals(crxPath, crxVfs.archivePath)
    }

    @Test
    fun `non-crx file is rejected`() {
        // A plain ZIP (no Cr24 magic) must not pass header validation.
        crxPath = Files.createTempFile("not-a-crx-", ".crx")
        Files.write(crxPath, buildInnerZip())
        try {
            CrxVirtualFileSystem(crxPath)
            fail("Expected construction to fail for a non-CRX file")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `unsupported crx version is rejected`() {
        crxPath = Files.createTempFile("bad-crx-", ".crx")
        Files.newOutputStream(crxPath).use { os ->
            val dos = DataOutputStream(os)
            dos.writeBytes("Cr24")
            dos.write(leUInt32(99))
            dos.write(leUInt32(0))
            dos.write(buildInnerZip())
        }
        try {
            CrxVirtualFileSystem(crxPath)
            fail("Expected construction to fail for unsupported CRX version")
        } catch (_: Exception) {
            // expected
        }
    }
}
