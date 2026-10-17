package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.components.State
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurtleCommanderSettingsAnnotationTest {

    @Test
    fun `state is declared as requiring the EDT because getState exposes lists the EDT mutates in place`() {
        val state = TurtleCommanderSettings::class.java.getAnnotation(State::class.java)
        assertNotNull(state)
        assertTrue(state!!.getStateRequiresEdt)
    }
}
