package com.ljwzz.weathertrafficalarm.core.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val CREDENTIAL_FORMAT_VERSION = 1

/** Complete replacement values from the credential editor. Values are never logged. */
class CredentialInput(
    val amapWebKey: String = "",
    val amapSdkKey: String = "",
    val caiyunAppKey: String = "",
    val caiyunSecret: String = "",
) {
    override fun toString(): String = "CredentialInput(redacted)"
}

/** A complete Caiyun credential candidate that has passed a connection test. */
class CaiyunCredentialInput(
    val appKey: String,
    val secret: String,
) {
    init {
        require(appKey.isNotBlank()) { "Caiyun App Key must not be blank" }
        require(secret.isNotBlank()) { "Caiyun Secret must not be blank" }
    }

    override fun toString(): String = "CaiyunCredentialInput(redacted)"
}

enum class CaiyunConnectionTestResult {
    PASSED,
    FAILED,
    NEVER_TESTED,
}

data class CredentialStatus(
    val amapWebKeyMask: String? = null,
    val amapSdkKeyMask: String? = null,
    val caiyunAppKeyMask: String? = null,
    val caiyunSecretMask: String? = null,
    val caiyunLastTestedAtEpochMillis: Long? = null,
    val caiyunTestResult: CaiyunConnectionTestResult = CaiyunConnectionTestResult.NEVER_TESTED,
    val loaded: Boolean = false,
    val storageError: Boolean = false,
) {
    val hasAmapWebKey: Boolean get() = amapWebKeyMask != null
    val hasAmapSdkKey: Boolean get() = amapSdkKeyMask != null
    val hasCaiyunAppKey: Boolean get() = caiyunAppKeyMask != null
    val hasCaiyunSecret: Boolean get() = caiyunSecretMask != null
}

/** Values returned only for the future API adapter; never expose these to Compose UI. */
class ServiceCredentials internal constructor(
    val amapWebKey: String?,
    val amapSdkKey: String?,
    val caiyunAppKey: String?,
    val caiyunSecret: String?,
)

@Serializable
private data class StoredCredentials(
    val version: Int = CREDENTIAL_FORMAT_VERSION,
    val amapWebKey: String? = null,
    val amapSdkKey: String? = null,
    val caiyunAppKey: String? = null,
    val caiyunSecret: String? = null,
    val caiyunLastTestedAtEpochMillis: Long? = null,
    val caiyunTestResult: CaiyunConnectionTestResult = CaiyunConnectionTestResult.NEVER_TESTED,
)

@Serializable
internal data class EncryptedPayload(
    val version: Int = CREDENTIAL_FORMAT_VERSION,
    val iv: String,
    val ciphertext: String,
)

@Singleton
class CredentialStore internal constructor(
    private val storage: CredentialStorage,
    private val cipher: CredentialCipher,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        storage = NoBackupCredentialStorage(context),
        cipher = AndroidKeyStoreCredentialCipher,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(CredentialStatus())
    val state: StateFlow<CredentialStatus> = _state.asStateFlow()

    init {
        scope.launch { withContext(Dispatchers.IO) { mutex.withLock { reloadLocked() } } }
    }

    /**
     * Merges nonblank editor values into the stored credentials after encrypting them.
     * Clearing a provider is deliberately an explicit [clear] operation, so opening an
     * editor and saving one provider cannot erase a second provider's key.
     */
    suspend fun save(input: CredentialInput) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val normalized = input.merge(readStored() ?: StoredCredentials())
                val plaintext = json.encodeToString(normalized).toByteArray(StandardCharsets.UTF_8)
                storage.writeAtomically(json.encodeToString(cipher.encrypt(plaintext)))
                publish(normalized, storageError = false)
            }
        }
    }

    /**
     * Replaces every credential with [input]. Blank values explicitly clear their field.
     * Caiyun connection-test metadata is reset because an imported credential must be tested
     * on this device, even when its values happen to match the previously stored candidate.
     */
    suspend fun replace(input: CredentialInput) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val replacement = input.replacement()
                if (replacement.hasNoCredentials()) {
                    storage.clear()
                    publish(null, storageError = false)
                } else {
                    writeStored(replacement)
                }
            }
        }
    }

    /**
     * Stores only a successfully tested Caiyun candidate and preserves all AMap values.
     * A failed candidate must use [recordCaiyunTestFailure] and cannot replace a working key.
     */
    suspend fun saveVerifiedCaiyun(input: CaiyunCredentialInput, testedAtEpochMillis: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = readStored() ?: StoredCredentials()
                val updated = previous.copy(
                    caiyunAppKey = input.appKey.trim(),
                    caiyunSecret = input.secret.trim(),
                    caiyunLastTestedAtEpochMillis = testedAtEpochMillis,
                    caiyunTestResult = CaiyunConnectionTestResult.PASSED,
                )
                writeStored(updated)
            }
        }
    }

    /** Marks the already stored Caiyun credential as successfully tested without rewriting it. */
    suspend fun recordStoredCaiyunTestSuccess(testedAtEpochMillis: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = readStored()
                    ?: throw IllegalStateException("No Caiyun credential is stored")
                check(!previous.caiyunAppKey.isNullOrBlank() && !previous.caiyunSecret.isNullOrBlank()) {
                    "No complete Caiyun credential is stored"
                }
                writeStored(
                    previous.copy(
                        caiyunLastTestedAtEpochMillis = testedAtEpochMillis,
                        caiyunTestResult = CaiyunConnectionTestResult.PASSED,
                    ),
                )
            }
        }
    }

    /** Records a rejected candidate without overwriting the currently stored Caiyun credential. */
    suspend fun recordCaiyunTestFailure(testedAtEpochMillis: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = readStored() ?: StoredCredentials()
                writeStored(
                    previous.copy(
                        caiyunLastTestedAtEpochMillis = testedAtEpochMillis,
                        caiyunTestResult = CaiyunConnectionTestResult.FAILED,
                    ),
                )
            }
        }
    }

    /** Removes only Caiyun's key, secret, and connection-test metadata. */
    suspend fun clearCaiyun() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = readStored() ?: return@withLock
                val updated = previous.copy(
                    caiyunAppKey = null,
                    caiyunSecret = null,
                    caiyunLastTestedAtEpochMillis = null,
                    caiyunTestResult = CaiyunConnectionTestResult.NEVER_TESTED,
                )
                if (updated.amapWebKey == null && updated.amapSdkKey == null) storage.clear()
                else writeStored(updated)
            }
        }
    }

    /** Deletes the no-backup ciphertext; the AndroidKeyStore key has no plaintext value. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage.clear()
                _state.value = CredentialStatus(loaded = true)
            }
        }
    }

    /** Re-reads ciphertext and returns only masked values suitable for editor placeholders. */
    suspend fun maskedValues(): CredentialStatus {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                reloadLocked()
                state.value
            }
        }
    }

    /** For a network adapter only. Never pass this object to UI state, logs, or analytics. */
    suspend fun credentialsForServiceUse(): ServiceCredentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readStored()?.let { stored ->
                ServiceCredentials(stored.amapWebKey, stored.amapSdkKey, stored.caiyunAppKey, stored.caiyunSecret)
            }
        }
    }

    private fun reloadLocked() {
        val stored = runCatching { readStored() }
        if (stored.isSuccess) publish(stored.getOrNull(), storageError = false)
        else _state.value = CredentialStatus(loaded = true, storageError = true)
    }

    private fun readStored(): StoredCredentials? {
        val payload = storage.read() ?: return null
        val envelope = json.decodeFromString<EncryptedPayload>(payload)
        require(envelope.version == CREDENTIAL_FORMAT_VERSION) { "Unsupported credential format" }
        val stored = json.decodeFromString<StoredCredentials>(cipher.decrypt(envelope))
        require(stored.version == CREDENTIAL_FORMAT_VERSION) { "Unsupported stored credential version" }
        return stored
    }

    private fun writeStored(stored: StoredCredentials) {
        val plaintext = json.encodeToString(stored).toByteArray(StandardCharsets.UTF_8)
        storage.writeAtomically(json.encodeToString(cipher.encrypt(plaintext)))
        publish(stored, storageError = false)
    }

    private fun publish(stored: StoredCredentials?, storageError: Boolean) {
        _state.value = CredentialStatus(
            amapWebKeyMask = CredentialMasker.mask(stored?.amapWebKey),
            amapSdkKeyMask = CredentialMasker.mask(stored?.amapSdkKey),
            caiyunAppKeyMask = CredentialMasker.mask(stored?.caiyunAppKey),
            caiyunSecretMask = CredentialMasker.mask(stored?.caiyunSecret),
            caiyunLastTestedAtEpochMillis = stored?.caiyunLastTestedAtEpochMillis,
            caiyunTestResult = stored?.caiyunTestResult ?: CaiyunConnectionTestResult.NEVER_TESTED,
            loaded = true,
            storageError = storageError,
        )
    }

    private fun CredentialInput.merge(previous: StoredCredentials): StoredCredentials {
        val updatedCaiyunAppKey = caiyunAppKey.trim().takeIf(String::isNotEmpty) ?: previous.caiyunAppKey
        val updatedCaiyunSecret = caiyunSecret.trim().takeIf(String::isNotEmpty) ?: previous.caiyunSecret
        val caiyunChanged = updatedCaiyunAppKey != previous.caiyunAppKey || updatedCaiyunSecret != previous.caiyunSecret
        return StoredCredentials(
            amapWebKey = amapWebKey.trim().takeIf(String::isNotEmpty) ?: previous.amapWebKey,
            amapSdkKey = amapSdkKey.trim().takeIf(String::isNotEmpty) ?: previous.amapSdkKey,
            caiyunAppKey = updatedCaiyunAppKey,
            caiyunSecret = updatedCaiyunSecret,
            caiyunLastTestedAtEpochMillis = if (caiyunChanged) null else previous.caiyunLastTestedAtEpochMillis,
            caiyunTestResult = if (caiyunChanged) CaiyunConnectionTestResult.NEVER_TESTED else previous.caiyunTestResult,
        )
    }

    private fun CredentialInput.replacement(): StoredCredentials = StoredCredentials(
        amapWebKey = amapWebKey.trim().takeIf(String::isNotEmpty),
        amapSdkKey = amapSdkKey.trim().takeIf(String::isNotEmpty),
        caiyunAppKey = caiyunAppKey.trim().takeIf(String::isNotEmpty),
        caiyunSecret = caiyunSecret.trim().takeIf(String::isNotEmpty),
        caiyunLastTestedAtEpochMillis = null,
        caiyunTestResult = CaiyunConnectionTestResult.NEVER_TESTED,
    )

    private fun StoredCredentials.hasNoCredentials(): Boolean =
        amapWebKey == null && amapSdkKey == null && caiyunAppKey == null && caiyunSecret == null

    private companion object {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    }
}

internal interface CredentialStorage {
    fun read(): String?
    fun writeAtomically(contents: String)
    fun clear()
}

internal interface CredentialCipher {
    fun encrypt(plaintext: ByteArray): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): String
}

internal class NoBackupCredentialStorage(context: Context) : CredentialStorage {
    private val directory = context.noBackupFilesDir
    private val file = File(directory, FILE_NAME)
    private val temporary = File(directory, "$FILE_NAME.tmp")

    override fun read(): String? = if (file.isFile) file.readText(StandardCharsets.UTF_8) else null

    override fun writeAtomically(contents: String) {
        check(directory.exists() || directory.mkdirs()) { "Cannot create credential directory" }
        temporary.outputStream().use { stream ->
            stream.write(contents.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun clear() {
        if (temporary.exists() && !temporary.delete()) throw IOException("Unable to clear credential temporary file")
        if (file.exists() && !file.delete()) throw IOException("Unable to clear credentials")
    }

    private companion object {
        const val FILE_NAME = "credentials.v1"
    }
}

internal object AndroidKeyStoreCredentialCipher : CredentialCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return EncryptedPayload(
            iv = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
        )
    }

    override fun decrypt(payload: EncryptedPayload): String {
        val iv = Base64.getDecoder().decode(payload.iv)
        require(iv.size == GCM_IV_BYTES) { "Invalid credential IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(Base64.getDecoder().decode(payload.ciphertext)).toString(StandardCharsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private const val KEY_ALIAS = "commute_alarm_credentials_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
}

internal object CredentialMasker {
    fun mask(value: String?): String? = value?.takeIf(String::isNotEmpty)?.let { secret ->
        when (secret.length) {
            1 -> "•"
            2 -> "••"
            3 -> "${secret.first()}••"
            4 -> "${secret.take(1)}••${secret.takeLast(1)}"
            else -> "${secret.take(2)}••••${secret.takeLast(2)}"
        }
    }
}
