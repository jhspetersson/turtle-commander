package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JmodVirtualFileSystemTest {

    private lateinit var jmodPath: Path
    private var vfs: JmodVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::jmodPath.isInitialized) Files.deleteIfExists(jmodPath)
    }

    private fun buildInnerZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("classes/module-info.class"))
            zos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("conf/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("conf/net.properties"))
            zos.write("java.net.useSystemProxies=false".toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun writeJmod(): Path {
        val path = Files.createTempFile("test-", ".jmod")
        Files.newOutputStream(path).use { os ->
            os.write(byteArrayOf('J'.code.toByte(), 'M'.code.toByte(), 1, 0))
            os.write(buildInnerZip())
        }
        return path
    }

    @Test
    fun `jmod file extracts inner zip entries`() = runBlocking {
        jmodPath = writeJmod()
        val jmodVfs = JmodVirtualFileSystem(jmodPath)
        vfs = jmodVfs

        val rootEntries = jmodVfs.listFiles(jmodVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "classes" })
        assertNotNull(rootEntries.find { it.name == "conf" })

        assertEquals(
            "java.net.useSystemProxies=false",
            Files.readString(jmodVfs.getPath("/conf/net.properties")),
        )

        val classEntries = jmodVfs.listFiles(jmodVfs.getPath("/classes")).filter { !it.isParentLink }
        assertNotNull(classEntries.find { it.name == "module-info.class" })
    }

    @Test
    fun `jmod vfs is read-only`() {
        jmodPath = writeJmod()
        val jmodVfs = JmodVirtualFileSystem(jmodPath)
        vfs = jmodVfs
        assertTrue(jmodVfs.isReadOnly)
    }

    @Test
    fun `non-jmod file is rejected`() {
        jmodPath = Files.createTempFile("not-a-jmod-", ".jmod")
        Files.write(jmodPath, buildInnerZip())
        try {
            JmodVirtualFileSystem(jmodPath)
            fail("Expected construction to fail for a non-JMOD file")
        } catch (_: Exception) {
        }
    }
}
