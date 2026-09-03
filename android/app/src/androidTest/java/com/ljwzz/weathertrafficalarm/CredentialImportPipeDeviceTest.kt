package com.ljwzz.weathertrafficalarm

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunConnectionTestResult
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only FIFO coverage using a separate no-backup directory, never the user's credential file. */
@RunWith(AndroidJUnit4::class)
class CredentialImportPipeDeviceTest {
    @Test
    fun fifoReadsCompleteJson() {
        withPipe { pipe ->
            verifyPrivatePipe(pipe, InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.uid)
            val writer = writePipe(pipe, json("first"))
            val descriptor = Os.open(pipe.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            try {
                assertEquals("first", parseCredentialInput(readPipe(descriptor, 1_000, 8_192)).amapWebKey)
            } finally {
                Os.close(descriptor)
                writer.join(1_000)
            }
        }
    }

    @Test
    fun fifoRejectsInterruptedAndOversizedPayloads() {
        withPipe { pipe ->
            val writer = writePipe(pipe, "{\"amapWebKey\":\"partial")
            val descriptor = Os.open(pipe.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            try {
                assertFails { parseCredentialInput(readPipe(descriptor, 1_000, 8_192)) }
            } finally {
                Os.close(descriptor)
                writer.join(1_000)
            }
        }
        withPipe { pipe ->
            val writer = writePipe(pipe, "x".repeat(8_193))
            val descriptor = Os.open(pipe.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            try {
                assertFails { readPipe(descriptor, 1_000, 8_192) }
            } finally {
                Os.close(descriptor)
                writer.join(1_000)
            }
        }
    }

    @Test
    fun fifoTimesOutWithoutWriter() {
        withPipe { pipe ->
            val descriptor = Os.open(pipe.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            try {
                assertFails { readPipe(descriptor, 40, 8_192) }
            } finally {
                Os.close(descriptor)
            }
        }
    }

    @Test
    fun parserRejectsNonCanonicalJsonAndControlCharacters() {
        val valid = json("value")
        listOf(
            valid + " trailing",
            valid.replace('"', '\''),
            "{\"amapWebKey\":\"first\",\"amapWebKey\":\"second\",\"amapSdkKey\":\"sdk\",\"caiyunAppKey\":\"app\",\"caiyunSecret\":\"secret\"}",
            "{\"amapWebKey\":1,\"amapSdkKey\":\"sdk\",\"caiyunAppKey\":\"app\",\"caiyunSecret\":\"secret\"}",
            "{\"amapWebKey\":\"\\u0001\",\"amapSdkKey\":\"sdk\",\"caiyunAppKey\":\"app\",\"caiyunSecret\":\"secret\"}",
        ).forEach { payload ->
            assertFails { parseCredentialInput(payload.toByteArray(StandardCharsets.UTF_8)) }
        }
    }

    @Test
    fun pipeRejectsWrongUidModeAndSymlink() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withPipe { pipe ->
            assertFails { verifyPrivatePipe(pipe, context.applicationInfo.uid + 1) }
            Os.chmod(pipe.absolutePath, 0x100)
            assertFails { verifyPrivatePipe(pipe, context.applicationInfo.uid) }
        }

        val target = File(context.cacheDir, "credential-import-target-${UUID.randomUUID()}")
        val symlink = File(context.cacheDir, "credential-import-link-${UUID.randomUUID()}")
        try {
            target.writeText("test", StandardCharsets.UTF_8)
            Os.symlink(target.absolutePath, symlink.absolutePath)
            assertFails { verifyPrivatePipe(symlink, context.applicationInfo.uid) }
        } finally {
            symlink.delete()
            target.delete()
        }
    }

    @Test
    fun pipeNameRequiresCanonicalLowercaseUuid() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val uppercase = UUID.randomUUID().toString().uppercase()
        assertFails { credentialPipe(cache, "credential-import-$uppercase.fifo") }
    }

    @Test
    fun replaceUsesIsolatedStorageAndRemovesOldValues() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "credential-import-test-${UUID.randomUUID()}")
        try {
            val store = CredentialStore(NoBackupContext(InstrumentationRegistry.getInstrumentation().targetContext, root))
            store.replace(CredentialInput("old-web", "old-sdk", "old-app", "old-secret"))
            store.replace(CredentialInput("new-web", "", "", ""))

            val replaced = store.credentialsForServiceUse()
            assertEquals("new-web", replaced?.amapWebKey)
            assertNull(replaced?.amapSdkKey)
            assertNull(replaced?.caiyunAppKey)
            assertNull(replaced?.caiyunSecret)
            assertFalse(store.maskedValues().storageError)
            assertEquals(CaiyunConnectionTestResult.NEVER_TESTED, store.maskedValues().caiyunTestResult)

            store.replace(CredentialInput())
            assertNull(store.credentialsForServiceUse())
            assertFalse(store.maskedValues().hasAmapWebKey)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withPipe(block: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val pipe = File(cache, "credential-import-${UUID.randomUUID()}.fifo")
        try {
            Os.mkfifo(pipe.absolutePath, 0x180)
            block(pipe)
        } finally {
            runCatching { pipe.delete() }
        }
    }

    private fun writePipe(pipe: File, contents: String): Thread = thread(isDaemon = true) {
        FileOutputStream(pipe).use { stream -> stream.write(contents.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun json(webKey: String): String =
        "{\"amapWebKey\":\"$webKey\",\"amapSdkKey\":\"sdk\",\"caiyunAppKey\":\"app\",\"caiyunSecret\":\"secret\"}"

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is CredentialImportFailure)
    }
}

private class NoBackupContext(base: Context, private val noBackupDirectory: File) : ContextWrapper(base) {
    override fun getNoBackupFilesDir(): File = noBackupDirectory
}
