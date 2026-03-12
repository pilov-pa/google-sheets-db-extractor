package org.pilov.scedel.googlesheets

import com.google.api.client.http.HttpRequestInitializer
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.intellij.openapi.components.service
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object GoogleServiceAccountAuth {
    private const val ENV_PRIVATE_KEY = "GOOGLE_API_PRIVATE_KEY"
    private const val ENV_PRIVATE_KEY_ID = "GOOGLE_API_PRIVATE_KEY_ID"
    private const val ENV_CLIENT_EMAIL = "GOOGLE_API_CLIENT_EMAIL"
    private const val ENV_CLIENT_ID = "GOOGLE_API_CLIENT_ID"
    private const val ENV_DELEGATED_USER_EMAIL = "GOOGLE_API_DELEGATED_USER_EMAIL"

    private const val SCOPE_SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets"
    private const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive"

    fun createRequestInitializer(): HttpRequestInitializer {
        val settings = service<GoogleSheetsSettingsService>().getCredentialsData()
        val parsedJson = parseServiceAccountJson(settings.privateKey)
        val privateKey = normalizePrivateKey(
            requiredValue(
                firstNonBlank(parsedJson?.privateKey, settings.privateKey),
                ENV_PRIVATE_KEY,
                settings.useEnvFallback,
            ),
        )
        val privateKeyId = requiredValue(
            firstNonBlank(settings.privateKeyId, parsedJson?.privateKeyId),
            ENV_PRIVATE_KEY_ID,
            settings.useEnvFallback,
        )
        val clientEmail = requiredValue(
            firstNonBlank(settings.clientEmail, parsedJson?.clientEmail),
            ENV_CLIENT_EMAIL,
            settings.useEnvFallback,
        )
        val clientId = firstNonBlank(
            settings.clientId,
            parsedJson?.clientId,
            optionalEnv(ENV_CLIENT_ID, settings.useEnvFallback),
        )
        val delegatedUserEmail = firstNonBlank(
            settings.delegatedUserEmail,
            optionalEnv(ENV_DELEGATED_USER_EMAIL, settings.useEnvFallback),
        )

        val credentials = createCredentials(clientId, clientEmail, privateKey, privateKeyId)
        val delegatedCredentials = delegatedUserEmail
            ?.takeIf { it.isNotBlank() }
            ?.let { credentials.createDelegated(it) }
            ?: credentials
        val scopedCredentials = delegatedCredentials.createScoped(listOf(SCOPE_SPREADSHEETS, SCOPE_DRIVE))

        return HttpCredentialsAdapter(scopedCredentials)
    }

    private fun createCredentials(
        clientId: String?,
        clientEmail: String,
        privateKey: String,
        privateKeyId: String,
    ): ServiceAccountCredentials {
        try {
            return ServiceAccountCredentials.fromPkcs8(
                clientId,
                clientEmail,
                privateKey,
                privateKeyId,
                listOf(SCOPE_SPREADSHEETS, SCOPE_DRIVE),
            )
        } catch (_: IllegalArgumentException) {
            val parsedKey = parsePrivateKeyFromPemOrBase64(privateKey)
            return ServiceAccountCredentials.newBuilder()
                .setClientId(clientId)
                .setClientEmail(clientEmail)
                .setPrivateKeyId(privateKeyId)
                .setPrivateKey(parsedKey)
                .setScopes(listOf(SCOPE_SPREADSHEETS, SCOPE_DRIVE))
                .build()
        } catch (_: Exception) {
            val parsedKey = parsePrivateKeyFromPemOrBase64(privateKey)
            return ServiceAccountCredentials.newBuilder()
                .setClientId(clientId)
                .setClientEmail(clientEmail)
                .setPrivateKeyId(privateKeyId)
                .setPrivateKey(parsedKey)
                .setScopes(listOf(SCOPE_SPREADSHEETS, SCOPE_DRIVE))
                .build()
        }
    }

    private fun requiredValue(localValue: String?, envName: String, allowEnvFallback: Boolean): String {
        return firstNonBlank(localValue, optionalEnv(envName, allowEnvFallback))
            ?: throw IllegalStateException(
                "Missing required Google service account credential '$envName'. " +
                    "Set it in Settings | Tools | Google Sheets Export" +
                    if (allowEnvFallback) " or environment variables." else ".",
            )
    }

    private fun optionalEnv(name: String, allowEnvFallback: Boolean): String? {
        if (!allowEnvFallback) return null
        return System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        values.forEach { value ->
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun parseServiceAccountJson(raw: String): ParsedServiceAccountJson? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.contains("\"private_key\"")) {
            return null
        }
        return ParsedServiceAccountJson(
            privateKey = extractJsonString(text, "private_key"),
            privateKeyId = extractJsonString(text, "private_key_id"),
            clientEmail = extractJsonString(text, "client_email"),
            clientId = extractJsonString(text, "client_id"),
        )
    }

    private fun extractJsonString(json: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL)
        val value = regex.find(json)?.groupValues?.get(1) ?: return null
        return unescapeJsonString(value)
    }

    private fun unescapeJsonString(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun normalizePrivateKey(raw: String): String {
        var key = raw.trim()
        if (key.startsWith("\"") && key.endsWith("\"") && key.length >= 2) {
            key = key.substring(1, key.length - 1)
        }
        if (key.contains("\\n")) {
            key = key.replace("\\n", "\n")
        }
        key = key.replace("\r\n", "\n").replace('\r', '\n').trim()

        if (key.contains("BEGIN RSA PRIVATE KEY")) {
            throw IllegalStateException(
                "Key format is PKCS#1 (BEGIN RSA PRIVATE KEY). " +
                    "Google service account requires PKCS#8 (BEGIN PRIVATE KEY).",
            )
        }

        val hasHeader = key.contains("-----BEGIN PRIVATE KEY-----")
        val hasFooter = key.contains("-----END PRIVATE KEY-----")

        if (hasHeader && hasFooter) {
            val body = key.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----").trim()
            return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        }
        if (!hasHeader && !hasFooter) {
            return "-----BEGIN PRIVATE KEY-----\n$key\n-----END PRIVATE KEY-----"
        }

        throw IllegalStateException(
            "Private key is incomplete. It must contain both BEGIN and END markers for PKCS#8 key.",
        )
    }

    private fun parsePrivateKeyFromPemOrBase64(value: String): PrivateKey {
        val normalized = value
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\\r", "")
            .replace("\\s+".toRegex(), "")

        val decoded = try {
            Base64.getDecoder().decode(normalized)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(
                "Invalid private key format. Use full service-account JSON or raw base64 key from `private_key`.",
            )
        }

        decodePkcs8Rsa(decoded)?.let { return it }

        // Fallback: some setups provide PKCS#1 DER, wrap into PKCS#8 and retry.
        val wrapped = wrapPkcs1ToPkcs8(decoded)
        decodePkcs8Rsa(wrapped)?.let { return it }

        throw IllegalStateException(
            "Invalid PKCS#8 data. Paste full service-account JSON or the exact base64 from `private_key`.",
        )
    }

    private fun decodePkcs8Rsa(bytes: ByteArray): PrivateKey? {
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Exception) {
            null
        }
    }

    private fun wrapPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val algorithmIdentifier = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09,
            0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00,
        )
        val privateKeyOctetString = derOctetString(pkcs1)
        return derSequence(version, algorithmIdentifier, privateKeyOctetString)
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val payload = elements.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return byteArrayOf(0x30) + derLength(payload.size) + payload
    }

    private fun derOctetString(data: ByteArray): ByteArray {
        return byteArrayOf(0x04) + derLength(data.size) + data
    }

    private fun derLength(length: Int): ByteArray {
        if (length < 0x80) {
            return byteArrayOf(length.toByte())
        }
        var value = length
        var count = 0
        val temp = ByteArray(4)
        while (value > 0) {
            temp[3 - count] = (value and 0xff).toByte()
            value = value ushr 8
            count++
        }
        return byteArrayOf((0x80 or count).toByte()) + temp.copyOfRange(4 - count, 4)
    }

    private data class ParsedServiceAccountJson(
        val privateKey: String?,
        val privateKeyId: String?,
        val clientEmail: String?,
        val clientId: String?,
    )
}
