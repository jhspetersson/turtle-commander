package io.github.jhspetersson.turtlecommander.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DriveRootsPollingIntegrationTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testThrowingListenerDoesNotKillPollingForOthers() {
        val svc = FileOperationService(scope)
        val tick = AtomicInteger()
        svc.rootsSourceForTesting = { listOf("root-${tick.incrementAndGet()}") }
        svc.rootsPollIntervalMsForTesting = 25

        val received = CopyOnWriteArrayList<List<String>>()
        runCatching { svc.subscribeToRoots { throw RuntimeException("listener failure for test") } }
        svc.subscribeToRoots { received.add(it) }

        val deadline = System.currentTimeMillis() + 15_000
        while (received.size < 3 && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }

        assertTrue(
            "recording listener must keep receiving updates despite a throwing sibling; got ${received.size}",
            received.size >= 3,
        )
        assertEquals(received.size, received.distinct().size)
    }
}
