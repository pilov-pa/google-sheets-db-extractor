package org.pilov.scedel.googlesheets

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.Permission
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.Spreadsheet
import com.google.api.services.sheets.v4.model.SpreadsheetProperties
import com.google.api.services.sheets.v4.model.ValueRange

object GoogleSheetsExporter {

    data class ExportResult(
        val spreadsheetId: String,
        val spreadsheetUrl: String,
    )

    fun export(
        requestInitializer: HttpRequestInitializer,
        title: String,
        rows: List<List<Any>>,
        shareWithEmail: String? = null,
    ): ExportResult {
        require(rows.isNotEmpty()) { "Nothing to export." }

        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val appName = "JetBrains DB -> Google Sheets Export"
        val sheets = Sheets.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName(appName)
            .build()

        val createdSpreadsheet = sheets.spreadsheets().create(
            Spreadsheet().setProperties(SpreadsheetProperties().setTitle(title)),
        )
            .setFields("spreadsheetId,spreadsheetUrl,sheets.properties.title")
            .execute()

        val spreadsheetId = createdSpreadsheet.spreadsheetId
            ?: throw IllegalStateException("Google API did not return spreadsheetId.")
        val spreadsheetUrl = createdSpreadsheet.spreadsheetUrl
            ?: "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
        val sheetTitle = createdSpreadsheet.sheets
            ?.firstOrNull()
            ?.properties
            ?.title
            ?.ifBlank { null }
            ?: "Sheet1"

        val values = rows.map { row -> row.map { value -> value } }
        val range = "'${sheetTitle.replace("'", "''")}'!A1"
        sheets.spreadsheets().values()
            .update(spreadsheetId, range, ValueRange().setValues(values))
            .setValueInputOption("RAW")
            .execute()

        val email = shareWithEmail?.trim().orEmpty()
        if (email.isNotEmpty()) {
            val drive = Drive.Builder(transport, jsonFactory, requestInitializer)
                .setApplicationName(appName)
                .build()
            drive.permissions()
                .create(
                    spreadsheetId,
                    Permission().setType("user").setRole("writer").setEmailAddress(email),
                )
                .setSendNotificationEmail(false)
                .execute()
        }

        return ExportResult(spreadsheetId = spreadsheetId, spreadsheetUrl = spreadsheetUrl)
    }
}
