package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.DefaultListSelectionModel
import javax.swing.ListSelectionModel

/**
 * Pure-Swing regression tests for the persistent-mark restore mechanism used by
 * `restoreTableMarks` / `restoreListMarks` after Swing's default key handlers wipe the
 * selection. These don't touch FileTab — they pin the underlying invariant the helper
 * relies on: `moveLeadSelectionIndex` relocates the lead WITHOUT extending the selection,
 * while the property-style assignment (`leadSelectionIndex = N`, which compiles to
 * `setLeadSelectionIndex`) DOES extend it. Mixing the two up was the original
 * "marks follow the cursor" bug.
 */
class SelectionMarkRestoreTest {

    private fun newModel(): DefaultListSelectionModel = DefaultListSelectionModel().apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    }

    @Test
    fun `setLeadSelectionIndex extends the selection between anchor and the new lead`() {
        // This is the Swing behavior we explicitly DON'T want when restoring marks: it's why
        // the cursor used to drag the marked range with it. The test pins the standard JDK
        // behavior so a future change here would make us re-evaluate restoreTableMarks.
        val sm = newModel()
        sm.setSelectionInterval(2, 2)        // selection={2}, anchor=2, lead=2
        sm.leadSelectionIndex = 5            // setLeadSelectionIndex — anchor (2) is selected,
                                             // so this selects the entire [2..5] range.

        val selected = (0..10).filter { sm.isSelectedIndex(it) }
        assertEquals("setLeadSelectionIndex should select the [anchor, newLead] range",
            listOf(2, 3, 4, 5), selected)
        assertEquals(5, sm.leadSelectionIndex)
    }

    @Test
    fun `moveLeadSelectionIndex relocates the lead but does not change the selection`() {
        // This is what restoreTableMarks / restoreListMarks rely on: the lead can be
        // repositioned to wherever Swing wanted to put the cursor without dragging any
        // selected rows along.
        val sm = newModel()
        sm.setSelectionInterval(2, 2)        // selection={2}, anchor=2, lead=2
        sm.moveLeadSelectionIndex(5)         // lead=5, selection unchanged

        val selected = (0..10).filter { sm.isSelectedIndex(it) }
        assertEquals("moveLeadSelectionIndex must leave the selection set untouched",
            listOf(2), selected)
        assertEquals(5, sm.leadSelectionIndex)
        assertEquals("Anchor must remain at the originally selected row", 2, sm.anchorSelectionIndex)
    }

    @Test
    fun `restore pattern reproduces the persistent-mark behavior end to end`() {
        // Simulates the exact sequence inside restoreTableMarks: capture lead/anchor that
        // Swing just set, rebuild the selection from the persistent mark set, then put the
        // lead back via moveLeadSelectionIndex. The mark stays at row 1 even though the
        // user has navigated to row 7.
        val sm = newModel()
        val toggledRows = sortedSetOf(1)

        // Initial state — user marked row 1 with INSERT.
        sm.setSelectionInterval(1, 1)

        // User now presses Down four times; BasicTableUI does setSelectionInterval(newLead, newLead),
        // which clears row 1 from selection. Reproduce one such jump:
        sm.setSelectionInterval(7, 7)

        // restoreTableMarks runs on the resulting selection event:
        val savedAnchor = sm.anchorSelectionIndex
        val savedLead = sm.leadSelectionIndex
        sm.clearSelection()
        for (row in toggledRows) sm.addSelectionInterval(row, row)
        sm.anchorSelectionIndex = savedAnchor
        sm.moveLeadSelectionIndex(savedLead)

        val selected = (0..10).filter { sm.isSelectedIndex(it) }
        assertEquals("Only the marked row should remain selected after restore",
            listOf(1), selected)
        assertEquals("Cursor must end up at the row Swing tried to navigate to",
            7, sm.leadSelectionIndex)
    }

    @Test
    fun `applyToggledSelection-style rebuild leaves no extra rows selected`() {
        // Mirrors applyToggledSelection: clear, then add each marked row individually.
        val sm = newModel()
        // Pretend a previous selection still held some unrelated rows.
        sm.addSelectionInterval(0, 0)
        sm.addSelectionInterval(4, 4)
        sm.addSelectionInterval(9, 9)

        val toggledRows = sortedSetOf(2, 5)
        sm.clearSelection()
        for (row in toggledRows) sm.addSelectionInterval(row, row)

        val selected = (0..10).filter { sm.isSelectedIndex(it) }
        assertEquals(listOf(2, 5), selected)
    }
}
