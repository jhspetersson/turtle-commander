package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.components.State
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagerStateServiceAnnotationTest {

    @Test
    fun `state is declared as requiring the EDT because getState reads Swing components`() {
        val state = FileManagerStateService::class.java.getAnnotation(State::class.java)
        assertNotNull(state)
        assertTrue(state!!.getStateRequiresEdt)
    }
}
