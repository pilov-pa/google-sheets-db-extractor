package org.pilov.scedel.googlesheets

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridHelper
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.extractors.DataExtractor
import com.intellij.database.extractors.DatabaseObjectFormatterConfig
import com.intellij.database.extractors.ExtractionConfig
import com.intellij.database.util.Out

object GridDataCollector {

    fun collectRows(grid: DataGrid): List<List<Any>> {
        val extractor = InMemoryExtractor(grid)
        GridHelper.get(grid).extractValues(grid, extractor, Out.Readable(), false, true)
        return extractor.rows
    }

    private class InMemoryExtractor(private val grid: DataGrid) : DataExtractor {
        val rows: MutableList<List<Any>> = mutableListOf()

        override fun getFileExtension(): String = "txt"

        override fun supportsText(): Boolean = true

        override fun startExtraction(
            out: Out,
            columns: List<GridColumn>,
            tableName: String,
            config: ExtractionConfig,
            vararg selectedColumns: Int,
        ): DataExtractor.Extraction {
            val columnIndexes = if (selectedColumns.isNotEmpty()) {
                selectedColumns.toList()
            } else {
                columns.indices.toList()
            }

            rows += columnIndexes.map { idx ->
                columns[idx].name.ifBlank { "Column ${idx + 1}" }
            }

            return object : DataExtractor.Extraction {
                override fun updateColumns(columns: Array<GridColumn>) = Unit

                override fun addData(rowsChunk: List<GridRow>) {
                    rowsChunk.forEach { row ->
                        val rendered = ArrayList<Any>(columnIndexes.size)
                        columnIndexes.forEach { columnIndex ->
                            val column = columns[columnIndex]
                            val rawValue = row.getValue(columnIndex)
                            val value = runCatching {
                                val formatterConfig = grid.getFormatterConfig(ModelIndex.forColumn(grid, columnIndex))
                                    ?: DatabaseObjectFormatterConfig.DatabaseDisplayObjectFormatterConfig()
                                grid.objectFormatter.objectToString(rawValue, column, formatterConfig)
                            }.getOrElse {
                                rawValue?.toString()
                            }.orEmpty()
                            rendered += value
                        }
                        rows += rendered
                    }
                }

                override fun complete() = Unit
            }
        }
    }
}
