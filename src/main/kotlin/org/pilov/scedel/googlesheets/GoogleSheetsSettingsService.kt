package org.pilov.scedel.googlesheets

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "GoogleSheetsSettingsService", storages = [Storage("googleSheetsDbExtractor.settings.xml")])
class GoogleSheetsSettingsService : PersistentStateComponent<GoogleSheetsSettingsService.State> {

    class State {
        var shareWithEmail: String = ""
        var useEnvFallback: Boolean = true
    }

    data class CredentialsData(
        val privateKeyId: String,
        val clientEmail: String,
        val clientId: String,
        val privateKey: String,
        val shareWithEmail: String,
        val useEnvFallback: Boolean,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getCredentialsData(): CredentialsData {
        return CredentialsData(
            privateKeyId = getSecret(PRIVATE_KEY_ID_SECRET_KEY),
            clientEmail = getSecret(CLIENT_EMAIL_SECRET_KEY),
            clientId = getSecret(CLIENT_ID_SECRET_KEY),
            privateKey = getPrivateKey(),
            shareWithEmail = state.shareWithEmail,
            useEnvFallback = state.useEnvFallback,
        )
    }

    fun update(
        privateKeyId: String,
        clientEmail: String,
        clientId: String,
        privateKey: String,
        shareWithEmail: String,
        useEnvFallback: Boolean,
    ) {
        setSecret(PRIVATE_KEY_ID_SECRET_KEY, privateKeyId)
        setSecret(CLIENT_EMAIL_SECRET_KEY, clientEmail)
        setSecret(CLIENT_ID_SECRET_KEY, clientId)
        state.useEnvFallback = useEnvFallback
        state.shareWithEmail = shareWithEmail.trim()
        setPrivateKey(privateKey.trim())
    }

    private fun getPrivateKey(): String {
        return PasswordSafe.instance.get(privateKeyAttributes())?.getPasswordAsString().orEmpty()
    }

    private fun setPrivateKey(privateKey: String) {
        val credentials = if (privateKey.isBlank()) null else Credentials(PRIVATE_KEY_SECRET_KEY, privateKey)
        PasswordSafe.instance.set(privateKeyAttributes(), credentials)
    }

    private fun getSecret(secretKey: String): String {
        return PasswordSafe.instance.get(secretAttributes(secretKey))?.getPasswordAsString().orEmpty()
    }

    private fun setSecret(secretKey: String, value: String) {
        val normalized = value.trim()
        val credentials = if (normalized.isBlank()) null else Credentials(secretKey, normalized)
        PasswordSafe.instance.set(secretAttributes(secretKey), credentials)
    }

    private fun privateKeyAttributes(): CredentialAttributes {
        return secretAttributes(PRIVATE_KEY_SECRET_KEY)
    }

    private fun secretAttributes(secretKey: String): CredentialAttributes {
        return CredentialAttributes(generateServiceName("GoogleSheetsDbExtractor", secretKey))
    }

    companion object {
        private const val PRIVATE_KEY_SECRET_KEY = "google-service-account-private-key"
        private const val PRIVATE_KEY_ID_SECRET_KEY = "google-service-account-private-key-id"
        private const val CLIENT_EMAIL_SECRET_KEY = "google-service-account-client-email"
        private const val CLIENT_ID_SECRET_KEY = "google-service-account-client-id"
    }
}
