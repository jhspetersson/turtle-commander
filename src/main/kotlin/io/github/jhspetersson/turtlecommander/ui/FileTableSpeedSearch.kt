package io.github.jhspetersson.turtlecommander.ui

import com.intellij.ui.Cell
import com.intellij.ui.TableSpeedSearch
import com.intellij.util.PairFunction
import javax.swing.JTable

internal fun installFileTableSpeedSearch(table: JTable, model: FileTableModel): TableSpeedSearch =
    TableSpeedSearch.installOn(table, fileTableSpeedSearchConvertor(table, model))

internal fun fileTableSpeedSearchConvertor(table: JTable, model: FileTableModel): PairFunction<Any?, Cell, String> =
    PairFunction { _, cell ->
        if (cell.column !in 0 until table.columnCount || cell.row !in 0 until table.rowCount) return@PairFunction ""
        if (table.convertColumnIndexToModel(cell.column) != FileTableModel.COL_NAME) return@PairFunction ""
        model.getEntryAt(table.convertRowIndexToModel(cell.row))?.name.orEmpty()
    }
