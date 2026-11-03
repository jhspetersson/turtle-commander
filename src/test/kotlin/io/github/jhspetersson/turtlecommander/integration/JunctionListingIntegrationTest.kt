package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class JunctionListingIntegrationTest : BasePlatformTestCase() {

    private lateinit var target: Path
    private lateinit var holder: Path
    private lateinit var link: Path

    override fun setUp() {
        super.setUp()
        target = Files.createTempDirectory("junction-list-target-")
        holder = Files.createTempDirectory("junction-list-holder-")
        link = holder.resolve("link")
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(link)
            holder.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun createJunction(): Boolean {
        if (!SystemInfo.isWindows) return false
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        if (process.waitFor() != 0) return false
        val attrs = Files.readAttributes(link, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return attrs.isDirectory && attrs.isOther
    }

    fun testListingThroughJunctionKeepsEntryPathsUnderJunction() = runBlocking {
        Files.createDirectories(target.resolve("sub"))
        Files.writeString(target.resolve("sub").resolve("file.txt"), "x")
        if (!createJunction()) return@runBlocking
        val fileOps: FileOperationService = project.service()

        val top = fileOps.listFiles(link, forceRefresh = true).filter { !it.isParentLink }
        assertEquals(listOf(link.resolve("sub")), top.map { it.path })

        val nested = fileOps.listFiles(link.resolve("sub"), forceRefresh = true).filter { !it.isParentLink }
        assertEquals(listOf(link.resolve("sub").resolve("file.txt")), nested.map { it.path })
    }
}
