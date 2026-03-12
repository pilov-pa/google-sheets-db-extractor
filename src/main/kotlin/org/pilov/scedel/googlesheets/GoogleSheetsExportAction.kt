package org.pilov.scedel.googlesheets

import com.intellij.database.DatabaseDataKeys
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridHelper
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class GoogleSheetsExportAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY)
        val enabled = grid != null && grid.isReady && !grid.isEmpty
        e.presentation.isVisible = grid != null
        e.presentation.isEnabled = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return

        object : Task.Backgroundable(project, "Export to Google Sheets", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading Google credentials..."
                val requestInitializer = GoogleAuthProvider.createRequestInitializer()
                val settingsService = service<GoogleSheetsSettingsService>()
                val shareWithEmail = settingsService.getShareWithEmail()
                val transferOwnership = settingsService.shouldTransferOwnership()

                indicator.text = "Collecting table data..."
                val rows = GridDataCollector.collectRows(project, grid, indicator)
                if (rows.size <= 1) {
                    throw IllegalStateException("No rows to export.")
                }

                indicator.text = "Creating Google spreadsheet..."
                val title = buildSpreadsheetTitle(grid)
                val result = GoogleSheetsExporter.export(
                    requestInitializer = requestInitializer,
                    title = title,
                    rows = rows,
                    shareWithEmail = shareWithEmail,
                    transferOwnership = transferOwnership,
                )
                notifySuccess(project, result.spreadsheetUrl, result.warning)
            }

            override fun onThrowable(error: Throwable) {
                notifyError(project, error)
            }
        }.queue()
    }

    private fun buildSpreadsheetTitle(grid: DataGrid): String {
        val baseName = runCatching { GridHelper.get(grid).getNameForDump(grid) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Database Export"
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return "$baseName $timestamp"
    }

    private fun notifySuccess(project: Project, url: String, warning: String?) {
        val warningSuffix = warning?.let { "<br/><br/>$it" }.orEmpty()
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GoogleSheetsNotifications.GROUP_ID)
            .createNotification(
                "Export complete",
                "Spreadsheet created: <a href=\"$url\">$url</a>$warningSuffix",
                NotificationType.INFORMATION,
                NotificationListener.URL_OPENING_LISTENER,
            )
        notification.notify(project)
    }

    private fun notifyError(project: Project, error: Throwable) {
        val message = StringUtil.escapeXmlEntities(error.message ?: error.toString())
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GoogleSheetsNotifications.GROUP_ID)
            .createNotification("Export failed", message, NotificationType.ERROR)
            .notify(project)
    }
}
