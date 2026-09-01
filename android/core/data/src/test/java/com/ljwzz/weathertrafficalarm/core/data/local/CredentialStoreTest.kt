package com.ljwzz.weathertrafficalarm.core.data.local

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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
}
