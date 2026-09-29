package com.ljwzz.weathertrafficalarm.core.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/** Debug-only stores with a private lifetime; never uses the application's files or Keystore. */
class IsolatedDeviceTestStores(private val directory: File) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    init { check(directory.mkdirs()) { "Test directory must be new" } }

    val settings = LocalSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") },
        scope,
    )
    private var encrypted: String? = null
    private val cipherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val credentials = CredentialStore(
        object : CredentialStorage {
            override fun read() = encrypted
            override fun writeAtomically(contents: String) { encrypted = contents }
            override fun clear() { encrypted = null }
        },
        object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray): EncryptedPayload {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, cipherKey)
                return EncryptedPayload(
                    iv = Base64.getEncoder().encodeToString(cipher.iv),
                    ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
                )
            }
            override fun decrypt(payload: EncryptedPayload): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, cipherKey, GCMParameterSpec(128, Base64.getDecoder().decode(payload.iv)))
                return cipher.doFinal(Base64.getDecoder().decode(payload.ciphertext)).toString(Charsets.UTF_8)
            }
        },
        scope,
    )

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        encrypted = null
        check(directory.deleteRecursively()) { "Test storage cleanup failed" }
    }
}
