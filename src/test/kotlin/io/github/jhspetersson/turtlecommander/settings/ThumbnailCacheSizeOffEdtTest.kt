package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.util.formatSize
import java.util.concurrent.CopyOnWriteArrayList

class ThumbnailCacheSizeOffEdtTest : BasePlatformTestCase() {

    fun `test cache size is measured off the EDT and shown when ready`() {
        val onEdt = CopyOnWriteArrayList<Boolean>()
        val configurable = TurtleCommanderConfigurable(project) {
            onEdt.add(ApplicationManager.getApplication().isDispatchThread)
            2048L
        }
        try {
            configurable.createComponent()
            val label = configurable.thumbnailCacheSizeLabel!!
            waitFor("cache size label") { label.text == formatSize(2048L) }
            assertEquals(listOf(false), onEdt)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description")
    }
}
