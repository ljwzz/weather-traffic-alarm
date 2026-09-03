package com.ljwzz.weathertrafficalarm.core.data.local

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {

    @Test
    fun concurrentPartialSavesRetainBothProviders() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)

        coroutineScope {
            launch { store.save(CredentialInput(amapWebKey = "web-key")) }
            launch { store.save(CredentialInput(caiyunAppKey = "app-key", caiyunSecret = "secret")) }
        }

        val status = store.maskedValues()
        assertTrue(status.hasAmapWebKey)
        assertTrue(status.hasCaiyunAppKey)
        assertTrue(status.hasCaiyunSecret)
    }

    @Test
    fun failedWriteOrClearDoesNotPublishAFalseClearedState() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "web-key"))
        storage.failWrites = true

        runCatching { store.save(CredentialInput(caiyunAppKey = "app-key")) }
        assertTrue(store.state.value.hasAmapWebKey)
        assertFalse(store.state.value.hasCaiyunAppKey)

        storage.failWrites = false
        storage.failClear = true
        runCatching { store.clear() }
        assertTrue(store.state.value.hasAmapWebKey)
    }

    @Test
    fun replaceOverwritesEveryCredentialAndIsRetainedAfterReopen() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(
            CredentialInput(
                amapWebKey = "old-web",
                amapSdkKey = "old-sdk",
                caiyunAppKey = "old-caiyun",
                caiyunSecret = "old-secret",
            ),
        )

        store.replace(
            CredentialInput(
                amapWebKey = " new-web ",
                amapSdkKey = "new-sdk",
                caiyunAppKey = "new-caiyun",
                caiyunSecret = "new-secret",
            ),
        )

        val reopened = CredentialStore(storage, PlaintextCipher, backgroundScope)
        val credentials = reopened.credentialsForServiceUse()
        assertEquals("new-web", credentials?.amapWebKey)
        assertEquals("new-sdk", credentials?.amapSdkKey)
        assertEquals("new-caiyun", credentials?.caiyunAppKey)
        assertEquals("new-secret", credentials?.caiyunSecret)
    }

    @Test
    fun replaceClearsBlankOrMissingFieldsAndResetsCaiyunMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "old-web", amapSdkKey = "old-sdk"))
        store.saveVerifiedCaiyun(CaiyunCredentialInput("caiyun-key", "caiyun-secret"), testedAtEpochMillis = 123L)

        store.replace(
            CredentialInput(
                amapWebKey = "new-web",
                amapSdkKey = "  ",
                caiyunAppKey = "caiyun-key",
                caiyunSecret = "caiyun-secret",
            ),
        )

        val credentials = store.credentialsForServiceUse()
        assertEquals("new-web", credentials?.amapWebKey)
        assertNull(credentials?.amapSdkKey)
        assertEquals("caiyun-key", credentials?.caiyunAppKey)
        assertEquals("caiyun-secret", credentials?.caiyunSecret)
        assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, store.state.value.caiyunTestResult)
        assertNull(store.state.value.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun replaceAllBlankCredentialsClearsPersistedStorage() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "web-key", caiyunAppKey = "caiyun-key", caiyunSecret = "caiyun-secret"))

        store.replace(CredentialInput())

        assertNull(storage.value)
        assertNull(store.credentialsForServiceUse())
        assertFalse(store.state.value.hasAmapWebKey)
        assertFalse(store.state.value.hasCaiyunAppKey)
        assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, store.state.value.caiyunTestResult)
    }

    @Test
    fun failedReplaceWriteOrEncryptionKeepsPreviousPersistentCredentials() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "old-web"))
        storage.failWrites = true

        val writeFailure = runCatching { store.replace(CredentialInput(amapWebKey = "new-web")) }.exceptionOrNull()

        assertEquals("write failure", writeFailure?.message)
        assertTrue(store.state.value.hasAmapWebKey)
        assertEquals("old-web", CredentialStore(storage, PlaintextCipher, backgroundScope).credentialsForServiceUse()?.amapWebKey)

        storage.failWrites = false
        val failingCipherStore = CredentialStore(storage, FailingEncryptCipher, backgroundScope)
        failingCipherStore.maskedValues()
        val encryptionFailure = runCatching { failingCipherStore.replace(CredentialInput(amapWebKey = "newer-web")) }.exceptionOrNull()

        assertEquals("encryption failure", encryptionFailure?.message)
        assertTrue(failingCipherStore.state.value.hasAmapWebKey)
        assertEquals("old-web", CredentialStore(storage, PlaintextCipher, backgroundScope).credentialsForServiceUse()?.amapWebKey)
    }

    @Test
    fun failedReplaceClearKeepsPreviousPersistentCredentials() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "old-web"))
        storage.failClear = true

        val failure = runCatching { store.replace(CredentialInput()) }.exceptionOrNull()

        assertEquals("clear failure", failure?.message)
        assertEquals("old-web", CredentialStore(storage, PlaintextCipher, backgroundScope).credentialsForServiceUse()?.amapWebKey)
        assertTrue(store.state.value.hasAmapWebKey)
    }

    @Test
    fun verifiedCaiyunSavePreservesAmapAndRecordsPassedMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "amap-key"))

        store.saveVerifiedCaiyun(CaiyunCredentialInput("caiyun-key", "caiyun-secret"), testedAtEpochMillis = 123L)

        val status = store.maskedValues()
        assertTrue(status.hasAmapWebKey)
        assertTrue(status.hasCaiyunAppKey)
        assertTrue(status.hasCaiyunSecret)
        assertEquals(CaiyunConnectionTestResult.PASSED, status.caiyunTestResult)
        assertEquals(123L, status.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun failedCaiyunCandidateDoesNotOverwriteExistingCredential() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.saveVerifiedCaiyun(CaiyunCredentialInput("known-key", "known-secret"), testedAtEpochMillis = 1L)

        store.recordCaiyunTestFailure(testedAtEpochMillis = 2L)

        val serviceCredentials = store.credentialsForServiceUse()
        assertEquals("known-key", serviceCredentials?.caiyunAppKey)
        assertEquals("known-secret", serviceCredentials?.caiyunSecret)
        assertEquals(CaiyunConnectionTestResult.FAILED, store.state.value.caiyunTestResult)
        assertEquals(2L, store.state.value.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun storedCaiyunSuccessUpdatesOnlyMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "amap-key", caiyunAppKey = "caiyun-key", caiyunSecret = "caiyun-secret"))

        store.recordStoredCaiyunTestSuccess(testedAtEpochMillis = 321L)

        val serviceCredentials = store.credentialsForServiceUse()
        assertEquals("amap-key", serviceCredentials?.amapWebKey)
        assertEquals("caiyun-key", serviceCredentials?.caiyunAppKey)
        assertEquals("caiyun-secret", serviceCredentials?.caiyunSecret)
        assertEquals(CaiyunConnectionTestResult.PASSED, store.state.value.caiyunTestResult)
        assertEquals(321L, store.state.value.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun storedCaiyunSuccessRejectsMissingCredentialsWithoutWritingMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "amap-key"))

        val failure = runCatching { store.recordStoredCaiyunTestSuccess(testedAtEpochMillis = 321L) }.exceptionOrNull()

        assertEquals("No complete Caiyun credential is stored", failure?.message)
        assertTrue(store.state.value.hasAmapWebKey)
        assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, store.state.value.caiyunTestResult)
        assertNull(store.state.value.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun savingAmapPreservesPassedCaiyunMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.saveVerifiedCaiyun(CaiyunCredentialInput("caiyun-key", "caiyun-secret"), testedAtEpochMillis = 123L)

        store.save(CredentialInput(amapWebKey = "amap-key"))

        assertTrue(store.state.value.hasAmapWebKey)
        assertEquals(CaiyunConnectionTestResult.PASSED, store.state.value.caiyunTestResult)
        assertEquals(123L, store.state.value.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun lowLevelCaiyunChangeResetsPassedMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.saveVerifiedCaiyun(CaiyunCredentialInput("caiyun-key", "caiyun-secret"), testedAtEpochMillis = 123L)

        store.save(CredentialInput(caiyunAppKey = "updated-key"))

        val serviceCredentials = store.credentialsForServiceUse()
        assertEquals("updated-key", serviceCredentials?.caiyunAppKey)
        assertEquals("caiyun-secret", serviceCredentials?.caiyunSecret)
        assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, store.state.value.caiyunTestResult)
        assertNull(store.state.value.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun clearCaiyunKeepsAmapAndResetsOnlyCaiyunMetadata() = runTest {
        val storage = MemoryStorage()
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)
        store.save(CredentialInput(amapWebKey = "amap-key"))
        store.saveVerifiedCaiyun(CaiyunCredentialInput("caiyun-key", "caiyun-secret"), testedAtEpochMillis = 123L)

        store.clearCaiyun()

        val status = store.maskedValues()
        assertTrue(status.hasAmapWebKey)
        assertFalse(status.hasCaiyunAppKey)
        assertFalse(status.hasCaiyunSecret)
        assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, status.caiyunTestResult)
        assertNull(status.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun legacyPayloadDefaultsToNeverTestedMetadata() = runTest {
        val storage = MemoryStorage()
        val legacyPayload = """{"version":1,"caiyunAppKey":"legacy-key","caiyunSecret":"legacy-secret"}"""
        storage.value = Json.encodeToString(EncryptedPayload(iv = "test", ciphertext = legacyPayload))
        val store = CredentialStore(storage, PlaintextCipher, backgroundScope)

        val status = store.maskedValues()

        assertTrue(status.hasCaiyunAppKey)
        assertTrue(status.hasCaiyunSecret)
        assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, status.caiyunTestResult)
        assertNull(status.caiyunLastTestedAtEpochMillis)
    }

    @Test
    fun credentialInputToStringsNeverRevealSecrets() {
        val amapWebKey = "amap-web-key"
        val amapSdkKey = "amap-sdk-key"
        val caiyunAppKey = "caiyun-app-key"
        val caiyunSecret = "caiyun-secret"

        val allCredentialText = CredentialInput(amapWebKey, amapSdkKey, caiyunAppKey, caiyunSecret).toString()
        val caiyunCredentialText = CaiyunCredentialInput(caiyunAppKey, caiyunSecret).toString()

        listOf(amapWebKey, amapSdkKey, caiyunAppKey, caiyunSecret).forEach { secret ->
            assertFalse(allCredentialText.contains(secret))
            assertFalse(caiyunCredentialText.contains(secret))
        }
    }

    private class MemoryStorage : CredentialStorage {
        var value: String? = null
        var failWrites = false
        var failClear = false

        override fun read(): String? = value
        override fun writeAtomically(contents: String) {
            check(!failWrites) { "write failure" }
            value = contents
        }

        override fun clear() {
            check(!failClear) { "clear failure" }
            value = null
        }
    }

    private object PlaintextCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedPayload = EncryptedPayload(
            iv = "test",
            ciphertext = plaintext.decodeToString(),
        )

        override fun decrypt(payload: EncryptedPayload): String = payload.ciphertext
    }

    private object FailingEncryptCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedPayload = error("encryption failure")

        override fun decrypt(payload: EncryptedPayload): String = payload.ciphertext
    }
}
