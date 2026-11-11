package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileSearchService
import io.github.jhspetersson.turtlecommander.ui.SearchResultsPanel
import java.nio.file.Files
import java.nio.file.Path

class SearchResultsErrorIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: SearchResultsPanel

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-search-error-")
        val criteria = FileSearchCriteria(
            rootPath = tempDir,
            namePattern = "*",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        panel = SearchResultsPanel(project, criteria)
        panel.createSearchService = { c ->
            object : FileSearchService(c) {
                override fun search(
                    onResult: (FileEntry) -> Unit,
                    isCancelled: () -> Boolean,
                    onProgress: (scannedCount: Int, currentDir: String) -> Unit,
                ) {
                    throw IllegalStateException("boom")
                }
            }
        }
    }

    override fun tearDown() {
        try {
            Disposer.dispose(panel)
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testUnexpectedSearchFailureEndsTheSearchWithAMessage() {
        panel.startSearch()

        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (panel.editButton.isEnabled) {
                assertEquals("Search failed: boom", panel.statusLabel.text)
                return
            }
            Thread.sleep(100)
        }
        fail("Search never finished: status is \"${panel.statusLabel.text}\"")
    }
}
