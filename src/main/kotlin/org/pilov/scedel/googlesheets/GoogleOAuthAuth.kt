package org.pilov.scedel.googlesheets

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.intellij.openapi.components.service
import java.net.URI

object GoogleOAuthAuth {
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/drive",
    )

    fun createRequestInitializer(): Credential {
        val settingsService = service<GoogleSheetsSettingsService>()
        val settings = settingsService.getCredentialsData()
        val clientId = settings.oauthClientId.trim()
        val clientSecret = settings.oauthClientSecret.trim()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw IllegalStateException(
                "Missing OAuth client credentials. Set OAuth Client ID and OAuth Client Secret in " +
                    "Settings | Tools | Google Sheets Export.",
            )
        }

        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val redirectUri = settings.oauthRedirectUri.trim().ifBlank { "http://localhost" }

        val credential = if (settings.oauthRefreshToken.isNotBlank()) {
            runCatching {
                credentialFromRefreshToken(
                    clientId = clientId,
                    clientSecret = clientSecret,
                    refreshToken = settings.oauthRefreshToken,
                    transport = transport,
                    jsonFactory = jsonFactory,
                )
            }.getOrElse {
                authorizeInteractively(
                    clientId = clientId,
                    clientSecret = clientSecret,
                    redirectUri = redirectUri,
                    transport = transport,
                    jsonFactory = jsonFactory,
                )
            }
        } else {
            authorizeInteractively(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                transport = transport,
                jsonFactory = jsonFactory,
            )
        }

        val refreshToken = credential.refreshToken?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: settings.oauthRefreshToken.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "Google OAuth did not return refresh token. " +
                    "Remove this app in Google Account permissions and authorize again.",
            )

        settingsService.updateOAuthTokens(
            refreshToken = refreshToken,
            accessToken = credential.accessToken.orEmpty(),
            accessTokenExpiryEpochMs = credential.expirationTimeMilliseconds ?: 0L,
        )
        return credential
    }

    private fun credentialFromRefreshToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
        transport: com.google.api.client.http.HttpTransport,
        jsonFactory: com.google.api.client.json.JsonFactory,
    ): GoogleCredential {
        val credential = GoogleCredential.Builder()
            .setTransport(transport)
            .setJsonFactory(jsonFactory)
            .setClientSecrets(clientId, clientSecret)
            .setTokenServerEncodedUrl("https://oauth2.googleapis.com/token")
            .build()
            .setRefreshToken(refreshToken)

        if (!credential.refreshToken()) {
            throw IllegalStateException("Failed to refresh Google OAuth access token.")
        }
        return credential
    }

    private fun authorizeInteractively(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        transport: com.google.api.client.http.HttpTransport,
        jsonFactory: com.google.api.client.json.JsonFactory,
    ): Credential {
        val secrets = GoogleClientSecrets()
        secrets.installed = GoogleClientSecrets.Details().apply {
            this.clientId = clientId
            this.clientSecret = clientSecret
            this.authUri = "https://accounts.google.com/o/oauth2/auth"
            this.tokenUri = "https://oauth2.googleapis.com/token"
        }

        val flow = GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, secrets, SCOPES)
            .setAccessType("offline")
            // `approval_prompt=consent` is rejected by Google OAuth.
            // Legacy parameter accepts `force|auto`; `force` ensures refresh_token is returned.
            .setApprovalPrompt("force")
            .build()

        val receiver = buildReceiver(redirectUri)
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("default-user")
    }

    private fun buildReceiver(redirectUri: String): LocalServerReceiver {
        val parsed = runCatching { URI(redirectUri) }.getOrNull()
            ?: throw IllegalStateException("Invalid OAuth redirect URI: $redirectUri")
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: "localhost"
        val port = parsed.port
        val path = parsed.path?.takeIf { it.isNotBlank() } ?: "/"

        return LocalServerReceiver.Builder()
            .setHost(host)
            .setPort(port)
            .setCallbackPath(path)
            .build()
    }
}
