package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DateTimePatternValidationTest : BasePlatformTestCase() {

    private lateinit var savedDisplay: String
    private lateinit var savedExport: String

    override fun setUp() {
        super.setUp()
        val state = TurtleCommanderSettings.getInstance().state
        savedDisplay = state.dateTimeFormat
        savedExport = state.exportDateTimeFormat
    }

    override fun tearDown() {
        try {
            val state = TurtleCommanderSettings.getInstance().state
            state.dateTimeFormat = savedDisplay
            state.exportDateTimeFormat = savedExport
        } finally {
            super.tearDown()
        }
    }

    private fun withConfigurable(block: (TurtleCommanderConfigurable) -> Unit) {
        val configurable = TurtleCommanderConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            block(configurable)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test invalid display pattern is rejected and not saved`() {
        val before = TurtleCommanderSettings.getInstance().state.dateTimeFormat
        withConfigurable { c ->
            c.dateTimeFormatField!!.text = "yyyy-MM-dd {"
            assertThrows(ConfigurationException::class.java) { c.apply() }
        }
        assertEquals(before, TurtleCommanderSettings.getInstance().state.dateTimeFormat)
    }

    fun `test invalid export pattern is rejected and not saved`() {
        val before = TurtleCommanderSettings.getInstance().state.exportDateTimeFormat
        withConfigurable { c ->
            c.exportDateTimeFormatField!!.text = "bogus pattern"
            assertThrows(ConfigurationException::class.java) { c.apply() }
        }
        assertEquals(before, TurtleCommanderSettings.getInstance().state.exportDateTimeFormat)
    }

    fun `test valid patterns are saved`() {
        withConfigurable { c ->
            c.dateTimeFormatField!!.text = "dd/MM/yyyy HH:mm"
            c.exportDateTimeFormatField!!.text = "yyyyMMdd"
            c.apply()
        }
        val state = TurtleCommanderSettings.getInstance().state
        assertEquals("dd/MM/yyyy HH:mm", state.dateTimeFormat)
        assertEquals("yyyyMMdd", state.exportDateTimeFormat)
    }

    fun `test blank export pattern stays allowed`() {
        withConfigurable { c ->
            c.exportDateTimeFormatField!!.text = ""
            c.apply()
        }
        assertEquals("", TurtleCommanderSettings.getInstance().state.exportDateTimeFormat)
    }
}
