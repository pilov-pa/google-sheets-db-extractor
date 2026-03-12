package org.pilov.scedel.googlesheets

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.charset.StandardCharsets
import java.util.Base64

@Service(Service.Level.APP)
@State(name = "GoogleSheetsSettingsService", storages = [Storage("googleSheetsDbExtractor.settings.xml")])
class GoogleSheetsSettingsService : PersistentStateComponent<GoogleSheetsSettingsService.State> {

    class State {
        var shareWithEmail: String = ""
        var transferOwnership: Boolean = false
        var delegatedUserEmail: String = ""
        var oauthClientId: String = ""
        var oauthClientSecret: String = ""
        var oauthRedirectUri: String = "http://localhost"
        var useEnvFallback: Boolean = true
    }

    data class CredentialsData(
        val privateKeyId: String,
        val clientEmail: String,
        val clientId: String,
        val privateKey: String,
        val oauthClientId: String,
        val oauthClientSecret: String,
        val oauthRefreshToken: String,
        val oauthAccessToken: String,
        val oauthAccessTokenExpiryEpochMs: Long,
        val oauthRedirectUri: String,
        val shareWithEmail: String,
        val transferOwnership: Boolean,
        val delegatedUserEmail: String,
        val useEnvFallback: Boolean,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getCredentialsData(): CredentialsData {
        val combined = getCombinedSecret()?.let(::decodeCombinedSecret)
        val oauth = getOAuthSecretData()
        if (combined != null) {
            return CredentialsData(
                privateKeyId = combined.privateKeyId,
                clientEmail = combined.clientEmail,
                clientId = combined.clientId,
                privateKey = combined.privateKey,
                oauthClientId = state.oauthClientId,
                oauthClientSecret = state.oauthClientSecret,
                oauthRefreshToken = oauth.refreshToken,
                oauthAccessToken = oauth.accessToken,
                oauthAccessTokenExpiryEpochMs = oauth.accessTokenExpiryEpochMs,
                oauthRedirectUri = state.oauthRedirectUri,
                shareWithEmail = state.shareWithEmail,
                transferOwnership = state.transferOwnership,
                delegatedUserEmail = state.delegatedUserEmail,
                useEnvFallback = state.useEnvFallback,
            )
        }

        // Legacy fallback for old versions that stored each field separately.
        return CredentialsData(
            privateKeyId = getSecret(PRIVATE_KEY_ID_SECRET_KEY),
            clientEmail = getSecret(CLIENT_EMAIL_SECRET_KEY),
            clientId = getSecret(CLIENT_ID_SECRET_KEY),
            privateKey = getPrivateKey(),
            oauthClientId = state.oauthClientId,
            oauthClientSecret = state.oauthClientSecret,
            oauthRefreshToken = oauth.refreshToken,
            oauthAccessToken = oauth.accessToken,
            oauthAccessTokenExpiryEpochMs = oauth.accessTokenExpiryEpochMs,
            oauthRedirectUri = state.oauthRedirectUri,
            shareWithEmail = state.shareWithEmail,
            transferOwnership = state.transferOwnership,
            delegatedUserEmail = state.delegatedUserEmail,
            useEnvFallback = state.useEnvFallback,
        )
    }

    fun update(
        privateKeyId: String,
        clientEmail: String,
        clientId: String,
        privateKey: String,
        oauthClientId: String,
        oauthClientSecret: String,
        oauthRedirectUri: String,
        shareWithEmail: String,
        transferOwnership: Boolean,
        delegatedUserEmail: String,
        useEnvFallback: Boolean,
    ) {
        val normalizedOauthClientId = oauthClientId.trim()
        val normalizedOauthClientSecret = oauthClientSecret.trim()
        val existingOAuth = getOAuthSecretData()
        val preserveTokens = state.oauthClientId == normalizedOauthClientId &&
            state.oauthClientSecret == normalizedOauthClientSecret
        setOAuthSecretData(
            OAuthSecretData(
                refreshToken = if (preserveTokens) existingOAuth.refreshToken else "",
                accessToken = if (preserveTokens) existingOAuth.accessToken else "",
                accessTokenExpiryEpochMs = if (preserveTokens) existingOAuth.accessTokenExpiryEpochMs else 0L,
            ),
        )

        setCombinedSecret(
            encodeCombinedSecret(
                privateKeyId = privateKeyId.trim(),
                clientEmail = clientEmail.trim(),
                clientId = clientId.trim(),
                privateKey = privateKey.trim(),
            ),
        )

        // Cleanup legacy keys to prevent extra keychain prompts.
        clearSecret(PRIVATE_KEY_ID_SECRET_KEY)
        clearSecret(CLIENT_EMAIL_SECRET_KEY)
        clearSecret(CLIENT_ID_SECRET_KEY)
        clearPrivateKey()

        state.useEnvFallback = useEnvFallback
        state.shareWithEmail = shareWithEmail.trim()
        state.transferOwnership = transferOwnership
        state.delegatedUserEmail = delegatedUserEmail.trim()
        state.oauthClientId = normalizedOauthClientId
        state.oauthClientSecret = normalizedOauthClientSecret
        state.oauthRedirectUri = oauthRedirectUri.trim().ifBlank { "http://localhost" }
    }

    fun updateOAuthTokens(
        refreshToken: String,
        accessToken: String,
        accessTokenExpiryEpochMs: Long,
    ) {
        if (state.oauthClientId.isBlank() || state.oauthClientSecret.isBlank()) {
            return
        }
        val existingOAuth = getOAuthSecretData()
        setOAuthSecretData(
            existingOAuth.copy(
                refreshToken = refreshToken.trim(),
                accessToken = accessToken.trim(),
                accessTokenExpiryEpochMs = accessTokenExpiryEpochMs,
            ),
        )
    }

    fun clearOAuthTokens() {
        val existingOAuth = getOAuthSecretData()
        if (existingOAuth.refreshToken.isBlank() && existingOAuth.accessToken.isBlank()) {
            return
        }
        setOAuthSecretData(
            existingOAuth.copy(
                refreshToken = "",
                accessToken = "",
                accessTokenExpiryEpochMs = 0L,
            ),
        )
    }

    private fun getPrivateKey(): String {
        return PasswordSafe.instance.get(privateKeyAttributes())?.getPasswordAsString().orEmpty()
    }

    private fun setPrivateKey(privateKey: String) {
        val credentials = if (privateKey.isBlank()) null else Credentials(PRIVATE_KEY_SECRET_KEY, privateKey)
        PasswordSafe.instance.set(privateKeyAttributes(), credentials)
    }

    private fun clearPrivateKey() {
        PasswordSafe.instance.set(privateKeyAttributes(), null)
    }

    private fun getSecret(secretKey: String): String {
        return PasswordSafe.instance.get(secretAttributes(secretKey))?.getPasswordAsString().orEmpty()
    }

    private fun setSecret(secretKey: String, value: String) {
        val normalized = value.trim()
        val credentials = if (normalized.isBlank()) null else Credentials(secretKey, normalized)
        PasswordSafe.instance.set(secretAttributes(secretKey), credentials)
    }

    private fun clearSecret(secretKey: String) {
        PasswordSafe.instance.set(secretAttributes(secretKey), null)
    }

    private fun getCombinedSecret(): String? {
        return PasswordSafe.instance.get(combinedSecretAttributes())?.getPasswordAsString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun setCombinedSecret(payload: String) {
        val credentials = if (payload.isBlank()) null else Credentials(COMBINED_SECRET_KEY, payload)
        PasswordSafe.instance.set(combinedSecretAttributes(), credentials)
    }

    private fun privateKeyAttributes(): CredentialAttributes {
        return secretAttributes(PRIVATE_KEY_SECRET_KEY)
    }

    private fun combinedSecretAttributes(): CredentialAttributes {
        return secretAttributes(COMBINED_SECRET_KEY)
    }

    private fun oauthSecretAttributes(): CredentialAttributes {
        return secretAttributes(OAUTH_COMBINED_SECRET_KEY)
    }

    private fun secretAttributes(secretKey: String): CredentialAttributes {
        return CredentialAttributes(generateServiceName("GoogleSheetsDbExtractor", secretKey))
    }

    fun getShareWithEmail(): String = state.shareWithEmail.trim()
    fun shouldTransferOwnership(): Boolean = state.transferOwnership

    private fun encodeCombinedSecret(
        privateKeyId: String,
        clientEmail: String,
        clientId: String,
        privateKey: String,
    ): String {
        fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            SECRET_VERSION,
            encode(privateKeyId),
            encode(clientEmail),
            encode(clientId),
            encode(privateKey),
        ).joinToString(SECRET_SEPARATOR)
    }

    private fun decodeCombinedSecret(payload: String): CombinedSecret? {
        val parts = payload.split(SECRET_SEPARATOR)
        if (parts.size != 5 || parts[0] != SECRET_VERSION) {
            return null
        }
        fun decode(value: String): String {
            val bytes = Base64.getDecoder().decode(value)
            return String(bytes, StandardCharsets.UTF_8)
        }
        return runCatching {
            CombinedSecret(
                privateKeyId = decode(parts[1]),
                clientEmail = decode(parts[2]),
                clientId = decode(parts[3]),
                privateKey = decode(parts[4]),
            )
        }.getOrNull()
    }

    private data class CombinedSecret(
        val privateKeyId: String,
        val clientEmail: String,
        val clientId: String,
        val privateKey: String,
    )

    private data class OAuthSecretData(
        val refreshToken: String,
        val accessToken: String,
        val accessTokenExpiryEpochMs: Long,
    )

    private fun getOAuthSecretData(): OAuthSecretData {
        val payload = PasswordSafe.instance.get(oauthSecretAttributes())?.getPasswordAsString().orEmpty().trim()
        if (payload.isBlank()) {
            return OAuthSecretData("", "", 0L)
        }
        return decodeOAuthSecret(payload) ?: OAuthSecretData("", "", 0L)
    }

    private fun setOAuthSecretData(data: OAuthSecretData) {
        val isEmpty = data.refreshToken.isBlank() &&
            data.accessToken.isBlank() &&
            data.accessTokenExpiryEpochMs == 0L
        val credentials = if (isEmpty) {
            null
        } else {
            Credentials(OAUTH_COMBINED_SECRET_KEY, encodeOAuthSecret(data))
        }
        PasswordSafe.instance.set(oauthSecretAttributes(), credentials)
    }

    private fun encodeOAuthSecret(data: OAuthSecretData): String {
        fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            OAUTH_SECRET_VERSION,
            encode(data.refreshToken),
            encode(data.accessToken),
            data.accessTokenExpiryEpochMs.toString(),
        ).joinToString(SECRET_SEPARATOR)
    }

    private fun decodeOAuthSecret(payload: String): OAuthSecretData? {
        val parts = payload.split(SECRET_SEPARATOR)
        fun decode(value: String): String {
            val bytes = Base64.getDecoder().decode(value)
            return String(bytes, StandardCharsets.UTF_8)
        }
        return when {
            // Current format: oauth-v1|refresh|access|expiry
            parts.size == 4 && parts[0] == OAUTH_SECRET_VERSION -> runCatching {
                OAuthSecretData(
                    refreshToken = decode(parts[1]),
                    accessToken = decode(parts[2]),
                    accessTokenExpiryEpochMs = parts[3].toLongOrNull() ?: 0L,
                )
            }.getOrNull()
            // Legacy format: oauth-v1|clientId|clientSecret|refresh|access|expiry
            parts.size == 6 && parts[0] == OAUTH_SECRET_VERSION -> runCatching {
                OAuthSecretData(
                    refreshToken = decode(parts[3]),
                    accessToken = decode(parts[4]),
                    accessTokenExpiryEpochMs = parts[5].toLongOrNull() ?: 0L,
                )
            }.getOrNull()
            else -> null
        }
    }

    companion object {
        private const val PRIVATE_KEY_SECRET_KEY = "google-service-account-private-key"
        private const val PRIVATE_KEY_ID_SECRET_KEY = "google-service-account-private-key-id"
        private const val CLIENT_EMAIL_SECRET_KEY = "google-service-account-client-email"
        private const val CLIENT_ID_SECRET_KEY = "google-service-account-client-id"
        private const val COMBINED_SECRET_KEY = "google-service-account-combined-secret"
        private const val OAUTH_COMBINED_SECRET_KEY = "google-oauth-combined-secret"
        private const val SECRET_VERSION = "v1"
        private const val OAUTH_SECRET_VERSION = "oauth-v1"
        private const val SECRET_SEPARATOR = "|"
    }
}
