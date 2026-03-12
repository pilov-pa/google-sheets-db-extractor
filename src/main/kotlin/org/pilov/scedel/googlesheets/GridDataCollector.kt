package org.pilov.scedel.googlesheets

import com.intellij.database.dump.ExtractionHelper
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridHelper
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.extractors.DataExtractorFactory
import com.intellij.database.extractors.ExtractorConfig
import com.intellij.database.extractors.DataExtractor
import com.intellij.database.extractors.DatabaseObjectFormatterConfig
import com.intellij.database.extractors.ExtractionConfig
import com.intellij.database.run.actions.DumpSource
import com.intellij.database.util.Out
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object GridDataCollector {

    fun collectRows(project: Project, grid: DataGrid, indicator: ProgressIndicator): List<List<Any>> {
        val extractor = InMemoryExtractor(grid)
        val helper = InMemoryExtractionHelper()
        val dumpSource = DumpSource.DataGridSource(grid)
        val dumpHandler = GridHelper.get(grid).createDumpHandler(
            dumpSource,
            helper,
            InMemoryExtractorFactory(extractor),
            ExtractionConfig(),
        )

        val latch = CountDownLatch(1)
        val startLatch = CountDownLatch(1)
        val errorRef = AtomicReference<String?>(null)

        helper.onFinished { _, info ->
            val error = info.errorSummary?.trim().orEmpty()
            if (error.isNotEmpty()) {
                errorRef.set(error)
            }
            latch.countDown()
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                dumpHandler.performDump(project)
            } finally {
                startLatch.countDown()
            }
        }

        while (!startLatch.await(100, TimeUnit.MILLISECONDS)) {
            indicator.checkCanceled()
        }
        while (!latch.await(200, TimeUnit.MILLISECONDS)) {
            indicator.checkCanceled()
        }

        errorRef.get()?.let { throw IllegalStateException("Database export failed: $it") }
        return extractor.rows
    }

    private class InMemoryExtractionHelper : ExtractionHelper.ExtractionHelperBase() {
        override fun createOut(name: String?, extractor: DataExtractor): Out = Out.Readable()

        override fun isSingleFileMode(): Boolean = false

        override fun sourceDumped(extractor: DataExtractor, out: Out) = Unit

        override fun getTitle(name: String): String = name

        @Throws(IOException::class)
        override fun after(project: Project, info: com.intellij.database.dump.DumpInfo) {
            super.after(project, info)
        }
    }

    private class InMemoryExtractorFactory(private val extractor: InMemoryExtractor) : DataExtractorFactory {
        override fun getName(): String = "GoogleSheetsInMemoryExtractor"

        override fun supportsText(): Boolean = true

        override fun createExtractor(config: ExtractorConfig): DataExtractor = extractor

        override fun getFileExtension(): String = "txt"
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
