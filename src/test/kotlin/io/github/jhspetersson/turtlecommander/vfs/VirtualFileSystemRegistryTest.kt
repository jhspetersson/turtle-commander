package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class VirtualFileSystemRegistryTest {

    @Test
    fun `supports zip extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.zip"))
    }

    @Test
    fun `supports jar extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("library.jar"))
    }

    @Test
    fun `supports war extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("webapp.war"))
    }

    @Test
    fun `supports ear extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("enterprise.ear"))
    }

    @Test
    fun `supports tar extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.tar"))
    }

    @Test
    fun `supports gz extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("file.gz"))
    }

    @Test
    fun `supports tgz extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.tgz"))
    }

    @Test
    fun `supports bz2 extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("file.bz2"))
    }

    @Test
    fun `supports tbz2 extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.tbz2"))
    }

    @Test
    fun `supports tbz extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.tbz"))
    }

    @Test
    fun `supports 7z extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("archive.7z"))
    }

    @Test
    fun `supports crx extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("extension.crx"))
    }

    @Test
    fun `supports xpi extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("addon.xpi"))
    }

    @Test
    fun `supports whl extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("package-1.0-py3-none-any.whl"))
    }

    @Test
    fun `supports egg extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("package-1.0-py3.8.egg"))
    }

    @Test
    fun `supports rpm extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("package.rpm"))
    }

    @Test
    fun `supports srpm extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("package.srpm"))
    }

    @Test
    fun `supports iso extension`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("disc.iso"))
    }

    @Test
    fun `supports disk image extensions`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("disk.vhd"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("disk.vhdx"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("disk.vmdk"))
    }

    @Test
    fun `supports squashfs extensions`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("rootfs.squashfs"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("image.sqsh"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("module.sfs"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("app_1234.snap"))
    }

    @Test
    fun `supports arj extensions`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("backup.arj"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("comic.cba"))
    }

    @Test
    fun `does not support unknown extension`() {
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("document.pdf"))
    }

    @Test
    fun `does not support txt extension`() {
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("readme.txt"))
    }

    @Test
    fun `does not support exe extension`() {
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("program.exe"))
    }

    @Test
    fun `does not support file without extension`() {
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("Makefile"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create throws for unsupported path`() {
        VirtualFileSystemRegistry.create(Path.of("/nonexistent/file.txt"))
    }
}
