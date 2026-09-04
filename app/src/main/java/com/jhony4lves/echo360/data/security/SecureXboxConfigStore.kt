package com.jhony4lves.echo360.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jhony4lves.echo360.domain.xbox.XboxCredentials
import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureXboxConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(profile: XboxProfile) {
        val validated = profile.copy(endpoint = profile.endpoint.validated())
        val serialized = toJson(validated).toString()
        preferences.edit()
            .putString(KEY_PROFILE, encrypt(serialized))
            .apply()
    }

    fun load(): XboxProfile? {
        val encrypted = preferences.getString(KEY_PROFILE, null) ?: return null
        return runCatching {
            fromJson(JSONObject(decrypt(encrypted)))
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().remove(KEY_PROFILE).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$iv.$encrypted"
    }

    private fun decrypt(value: String): String {
        val pieces = value.split('.', limit = 2)
        require(pieces.size == 2) { "Configuração segura inválida." }

        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, iv),
        )
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun toJson(profile: XboxProfile): JSONObject = JSONObject().apply {
        put("host", profile.endpoint.host)
        put("echoLinkPort", profile.endpoint.echoLinkPort)
        put("novaPort", profile.endpoint.novaPort)
        put("auroraFtpPort", profile.endpoint.auroraFtpPort)
        put("ftpDllPort", profile.endpoint.ftpDllPort)
        put("credentials", JSONObject().apply {
            put("echoCorePairingToken", profile.credentials.echoCorePairingToken)
            put("novaUsername", profile.credentials.novaUsername)
            put("novaPassword", profile.credentials.novaPassword)
            put("auroraFtpUsername", profile.credentials.auroraFtpUsername)
            put("auroraFtpPassword", profile.credentials.auroraFtpPassword)
            put("ftpDllUsername", profile.credentials.ftpDllUsername)
            put("ftpDllPassword", profile.credentials.ftpDllPassword)
        })
    }

    private fun fromJson(json: JSONObject): XboxProfile {
        val credentials = json.optJSONObject("credentials") ?: JSONObject()
        return XboxProfile(
            endpoint = XboxEndpoint(
                host = json.optString("host", ""),
                echoLinkPort = json.optInt("echoLinkPort", 36_000),
                novaPort = json.optInt("novaPort", 9999),
                auroraFtpPort = json.optInt("auroraFtpPort", 21),
                ftpDllPort = json.optInt("ftpDllPort", 7564),
            ),
            credentials = XboxCredentials(
                echoCorePairingToken = credentials.optString("echoCorePairingToken", ""),
                novaUsername = credentials.optString("novaUsername", ""),
                novaPassword = credentials.optString("novaPassword", ""),
                auroraFtpUsername = credentials.optString("auroraFtpUsername", ""),
                auroraFtpPassword = credentials.optString("auroraFtpPassword", ""),
                ftpDllUsername = credentials.optString("ftpDllUsername", ""),
                ftpDllPassword = credentials.optString("ftpDllPassword", ""),
            ),
        )
    }

    companion object {
        private const val PREFS_NAME = "echo360_secure_config"
        private const val KEY_PROFILE = "xbox_profile_v1"
        private const val KEY_ALIAS = "echo360_xbox_profile_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
