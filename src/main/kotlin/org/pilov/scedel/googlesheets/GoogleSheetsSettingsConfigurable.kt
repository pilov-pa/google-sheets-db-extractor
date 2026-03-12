package org.pilov.scedel.googlesheets

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class GoogleSheetsSettingsConfigurable : Configurable {
    private val privateKeyIdField = JBPasswordField()
    private val clientEmailField = JBPasswordField()
    private val clientIdField = JBPasswordField()
    private val privateKeyField = JBPasswordField()
    private val shareWithEmailField = JBTextField()
    private val useEnvFallback = JBCheckBox("Use environment variables as fallback", true)

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Google Sheets Export"

    override fun createComponent(): JComponent {
        if (panel != null) {
            return panel as JPanel
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Private Key ID:", privateKeyIdField)
            .addLabeledComponent("Client Email:", clientEmailField)
            .addLabeledComponent("Client ID (optional):", clientIdField)
            .addLabeledComponent("Private Key:", privateKeyField)
            .addLabeledComponent("Share new sheet with email:", shareWithEmailField)
            .addComponent(useEnvFallback)
            .addSeparator(8)
            .addComponent(
                JBLabel(
                    "<html>Required for service account auth. " +
                        "Values are taken from this settings page, " +
                        "and optionally from env vars:<br/>" +
                        "GOOGLE_API_PRIVATE_KEY, GOOGLE_API_PRIVATE_KEY_ID, GOOGLE_API_CLIENT_EMAIL, GOOGLE_API_CLIENT_ID.</html>",
                ),
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel as JPanel
    }

    override fun isModified(): Boolean {
        val service = service<GoogleSheetsSettingsService>()
        val current = service.getCredentialsData()
        return read(privateKeyIdField) != current.privateKeyId.trim() ||
            read(clientEmailField) != current.clientEmail.trim() ||
            read(clientIdField) != current.clientId.trim() ||
            read(privateKeyField) != current.privateKey.trim() ||
            shareWithEmailField.text.trim() != current.shareWithEmail.trim() ||
            useEnvFallback.isSelected != current.useEnvFallback
    }

    override fun apply() {
        val service = service<GoogleSheetsSettingsService>()
        service.update(
            privateKeyId = read(privateKeyIdField),
            clientEmail = read(clientEmailField),
            clientId = read(clientIdField),
            privateKey = read(privateKeyField),
            shareWithEmail = shareWithEmailField.text,
            useEnvFallback = useEnvFallback.isSelected,
        )
    }

    override fun reset() {
        val service = service<GoogleSheetsSettingsService>()
        val current = service.getCredentialsData()
        privateKeyIdField.text = current.privateKeyId
        clientEmailField.text = current.clientEmail
        clientIdField.text = current.clientId
        privateKeyField.text = current.privateKey
        shareWithEmailField.text = current.shareWithEmail
        useEnvFallback.isSelected = current.useEnvFallback
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun read(field: JBPasswordField): String = String(field.password).trim()
}
