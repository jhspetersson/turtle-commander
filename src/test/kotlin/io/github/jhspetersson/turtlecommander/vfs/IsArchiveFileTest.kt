package io.github.jhspetersson.turtlecommander.vfs
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.ui.isArchiveFile

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class IsArchiveFileTest {

    private fun fileEntry(name: String, isDirectory: Boolean = false) = FileEntry(
        name = name,
        path = Path.of("/test/$name"),
        isDirectory = isDirectory,
        size = 100,
        lastModified = null,
        permissions = "",
    )

    @Test
    fun `zip file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.zip")))
    }

    @Test
    fun `jar file is archive`() {
        assertTrue(isArchiveFile(fileEntry("lib.jar")))
    }

    @Test
    fun `war file is archive`() {
        assertTrue(isArchiveFile(fileEntry("app.war")))
    }

    @Test
    fun `ear file is archive`() {
        assertTrue(isArchiveFile(fileEntry("enterprise.ear")))
    }

    @Test
    fun `directory is not archive`() {
        assertFalse(isArchiveFile(fileEntry("archive.zip", isDirectory = true)))
    }

    @Test
    fun `tar file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.tar")))
    }

    @Test
    fun `gz file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.gz")))
    }

    @Test
    fun `tgz file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.tgz")))
    }

    @Test
    fun `bz2 file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.bz2")))
    }

    @Test
    fun `tbz2 file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.tbz2")))
    }

    @Test
    fun `7z file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.7z")))
    }

    @Test
    fun `ar file is archive`() {
        assertTrue(isArchiveFile(fileEntry("lib.ar")))
    }

    @Test
    fun `a file is archive`() {
        assertTrue(isArchiveFile(fileEntry("libfoo.a")))
    }

    @Test
    fun `deb file is archive`() {
        assertTrue(isArchiveFile(fileEntry("package.deb")))
    }

    @Test
    fun `xz file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.xz")))
    }

    @Test
    fun `txz file is archive`() {
        assertTrue(isArchiveFile(fileEntry("data.txz")))
    }

    @Test
    fun `crx file is archive`() {
        assertTrue(isArchiveFile(fileEntry("extension.crx")))
    }

    @Test
    fun `xpi file is archive`() {
        assertTrue(isArchiveFile(fileEntry("addon.xpi")))
    }

    @Test
    fun `aab file is archive`() {
        assertTrue(isArchiveFile(fileEntry("app-release.aab")))
    }

    @Test
    fun `ipa file is archive`() {
        assertTrue(isArchiveFile(fileEntry("app.ipa")))
    }

    @Test
    fun `appx and msix files are archives`() {
        assertTrue(isArchiveFile(fileEntry("app.appx")))
        assertTrue(isArchiveFile(fileEntry("app.appxbundle")))
        assertTrue(isArchiveFile(fileEntry("app.msix")))
        assertTrue(isArchiveFile(fileEntry("app.msixbundle")))
    }

    @Test
    fun `xps and oxps files are archives`() {
        assertTrue(isArchiveFile(fileEntry("document.xps")))
        assertTrue(isArchiveFile(fileEntry("document.oxps")))
    }

    @Test
    fun `kmz file is archive`() {
        assertTrue(isArchiveFile(fileEntry("places.kmz")))
    }

    @Test
    fun `usdz file is archive`() {
        assertTrue(isArchiveFile(fileEntry("model.usdz")))
    }

    @Test
    fun `oxt file is archive`() {
        assertTrue(isArchiveFile(fileEntry("extension.oxt")))
    }

    @Test
    fun `pk3 and pk4 files are archives`() {
        assertTrue(isArchiveFile(fileEntry("pak0.pk3")))
        assertTrue(isArchiveFile(fileEntry("game00.pk4")))
    }

    @Test
    fun `cb7 and cbt files are archives`() {
        assertTrue(isArchiveFile(fileEntry("comic.cb7")))
        assertTrue(isArchiveFile(fileEntry("comic.cbt")))
    }

    @Test
    fun `vsix file is archive`() {
        assertTrue(isArchiveFile(fileEntry("extension.vsix")))
    }

    @Test
    fun `whl file is archive`() {
        assertTrue(isArchiveFile(fileEntry("package-1.0-py3-none-any.whl")))
    }

    @Test
    fun `egg file is archive`() {
        assertTrue(isArchiveFile(fileEntry("package-1.0-py3.8.egg")))
    }

    @Test
    fun `rpm file is archive`() {
        assertTrue(isArchiveFile(fileEntry("package.rpm")))
    }

    @Test
    fun `srpm file is archive`() {
        assertTrue(isArchiveFile(fileEntry("package.srpm")))
    }

    @Test
    fun `iso file is archive`() {
        assertTrue(isArchiveFile(fileEntry("disc.iso")))
    }

    @Test
    fun `txt file is not archive`() {
        assertFalse(isArchiveFile(fileEntry("readme.txt")))
    }

    @Test
    fun `file without extension is not archive`() {
        assertFalse(isArchiveFile(fileEntry("Makefile")))
    }

    @Test
    fun `uppercase ZIP extension is recognized`() {
        // Extension is lowercased before comparison
        assertTrue(isArchiveFile(fileEntry("DATA.ZIP")))
    }

    @Test
    fun `mixed case Jar extension is recognized`() {
        assertTrue(isArchiveFile(fileEntry("library.Jar")))
    }

    @Test
    fun `parent link is not archive`() {
        val entry = FileEntry(
            name = "..",
            path = Path.of("/test"),
            isDirectory = true,
            size = 0,
            lastModified = null,
            permissions = "",
            isParentLink = true,
        )
        assertFalse(isArchiveFile(entry))
    }
}
