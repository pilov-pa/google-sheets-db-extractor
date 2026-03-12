package org.pilov.scedel.googlesheets

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class GoogleSheetsSettingsConfigurable : Configurable {
    private val oauthClientIdField = JBPasswordField()
    private val oauthClientSecretField = JBPasswordField()
    private val oauthRedirectUriField = JBTextField()

    private var panel: JPanel? = null
    private var initialData = GoogleSheetsSettingsService.CredentialsData(
        privateKeyId = "",
        clientEmail = "",
        clientId = "",
        privateKey = "",
        oauthClientId = "",
        oauthClientSecret = "",
        oauthRefreshToken = "",
        oauthAccessToken = "",
        oauthAccessTokenExpiryEpochMs = 0L,
        oauthRedirectUri = "http://localhost",
        shareWithEmail = "",
        transferOwnership = false,
        delegatedUserEmail = "",
        useEnvFallback = true,
    )

    override fun getDisplayName(): String = "Google Sheets Export"

    override fun createComponent(): JComponent {
        if (panel != null) {
            return panel as JPanel
        }

        panel = FormBuilder.createFormBuilder()
            .addSeparator()
            .addComponent(JBLabel("<html><b>OAuth</b></html>"))
            .addLabeledComponent("OAuth Client ID:", oauthClientIdField)
            .addLabeledComponent("OAuth Client Secret:", oauthClientSecretField)
            .addLabeledComponent("OAuth Redirect URI:", oauthRedirectUriField)
            .addSeparator(8)
            .addComponent(
                JBLabel(
                    "<html>OAuth credentials can be taken from your installed-app JSON (`installed.client_id` and `installed.client_secret`). " +
                        "Redirect URI should stay <code>http://localhost</code>.<br/>" +
                        "On first export, browser authorization opens and refresh token is stored securely in IDE Password Safe.</html>",
                ),
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel as JPanel
    }

    override fun isModified(): Boolean {
        return currentDataFromFields() != initialData
    }

    override fun apply() {
        val service = service<GoogleSheetsSettingsService>()
        val current = currentDataFromFields()
        service.update(
            privateKeyId = initialData.privateKeyId,
            clientEmail = initialData.clientEmail,
            clientId = initialData.clientId,
            privateKey = initialData.privateKey,
            oauthClientId = current.oauthClientId,
            oauthClientSecret = current.oauthClientSecret,
            oauthRedirectUri = current.oauthRedirectUri,
            shareWithEmail = initialData.shareWithEmail,
            transferOwnership = initialData.transferOwnership,
            delegatedUserEmail = initialData.delegatedUserEmail,
            useEnvFallback = initialData.useEnvFallback,
        )
        initialData = service.getCredentialsData()
    }

    override fun reset() {
        val service = service<GoogleSheetsSettingsService>()
        initialData = service.getCredentialsData()
        oauthClientIdField.text = initialData.oauthClientId
        oauthClientSecretField.text = initialData.oauthClientSecret
        oauthRedirectUriField.text = initialData.oauthRedirectUri
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun read(field: JBPasswordField): String = String(field.password).trim()

    private fun currentDataFromFields(): GoogleSheetsSettingsService.CredentialsData {
        return GoogleSheetsSettingsService.CredentialsData(
            privateKeyId = initialData.privateKeyId,
            clientEmail = initialData.clientEmail,
            clientId = initialData.clientId,
            privateKey = initialData.privateKey,
            oauthClientId = read(oauthClientIdField),
            oauthClientSecret = read(oauthClientSecretField),
            oauthRefreshToken = initialData.oauthRefreshToken,
            oauthAccessToken = initialData.oauthAccessToken,
            oauthAccessTokenExpiryEpochMs = initialData.oauthAccessTokenExpiryEpochMs,
            oauthRedirectUri = oauthRedirectUriField.text.trim(),
            shareWithEmail = initialData.shareWithEmail,
            transferOwnership = initialData.transferOwnership,
            delegatedUserEmail = initialData.delegatedUserEmail,
            useEnvFallback = initialData.useEnvFallback,
        )
    }
}
