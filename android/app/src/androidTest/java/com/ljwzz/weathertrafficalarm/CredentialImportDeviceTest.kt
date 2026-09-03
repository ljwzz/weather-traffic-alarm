package com.ljwzz.weathertrafficalarm

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.JsonReader
import android.util.JsonToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileDescriptor
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug/test-APK-only credential importer. The host supplies JSON only through an app-private FIFO;
 * instrumentation arguments contain the opt-in flag and a random FIFO basename, never credentials.
 */
@RunWith(AndroidJUnit4::class)
class CredentialImportDeviceTest {
    @Test
    fun importFromPipe() = runBlocking {
        val (context, pipe) = requestedPipe()
        val dependencies = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        importFromPipe(context, pipe) { input ->
            dependencies.credentials().replace(input)
            dependencies.credentials().maskedValues()
        }
    }

    /** Exercises the complete host-to-FIFO transport without touching the user's credential file. */
    @Test
    fun importFromPipeIntoIsolatedStore() = runBlocking {
        val (context, pipe) = requestedPipe()
        val isolatedDirectory = File(context.cacheDir, "credential-import-isolated-${UUID.randomUUID()}")
        try {
            val store = CredentialStore(IsolatedNoBackupContext(context, isolatedDirectory))
            importFromPipe(context, pipe) { input ->
                store.replace(input)
                store.maskedValues()
            }
        } finally {
            isolatedDirectory.deleteRecursively()
        }
    }

    private fun requestedPipe(): Pair<Context, File> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires explicit -e importCredentials true opt-in",
            arguments.getString(ARG_IMPORT_CREDENTIALS) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context to credentialPipe(context.cacheDir, arguments.getString(ARG_CREDENTIAL_PIPE))
    }

    private suspend fun importFromPipe(
        context: Context,
        pipe: File,
        replace: suspend (CredentialInput) -> CredentialStatus,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var descriptor: FileDescriptor? = null
        try {
            verifyPrivatePipe(pipe, context.applicationInfo.uid)
            descriptor = Os.open(
                pipe.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,
                0,
            )
            instrumentation.sendStatus(0, Bundle().apply { putBoolean("credentialImportReady", true) })

            val status = replace(parseCredentialInput(readPipe(descriptor, IMPORT_TIMEOUT_MILLIS, MAX_PAYLOAD_BYTES)))
            check(!status.storageError) { "Credential import failed" }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean("credentialImportSuccess", true)
                    putBoolean("hasAmapWebKey", status.hasAmapWebKey)
                    putBoolean("hasAmapSdkKey", status.hasAmapSdkKey)
                    putBoolean("hasCaiyunAppKey", status.hasCaiyunAppKey)
                    putBoolean("hasCaiyunSecret", status.hasCaiyunSecret)
                },
            )
        } catch (_: Throwable) {
            throw IllegalStateException("Credential import failed")
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
            runCatching { pipe.delete() }
        }
    }

    private companion object {
        const val ARG_IMPORT_CREDENTIALS = "importCredentials"
        const val ARG_CREDENTIAL_PIPE = "credentialPipe"
        const val IMPORT_TIMEOUT_MILLIS = 30_000L
        const val MAX_PAYLOAD_BYTES = 8_192
    }
}

private class IsolatedNoBackupContext(base: Context, private val directory: File) : ContextWrapper(base) {
    override fun getNoBackupFilesDir(): File = directory
}

internal class CredentialImportFailure : IllegalStateException("Credential import failed")

internal fun credentialPipe(cacheDirectory: File, suppliedBasename: String?): File {
    val basename = suppliedBasename ?: throw CredentialImportFailure()
    val uuid = basename.removePrefix("credential-import-").removeSuffix(".fifo")
    val parsedUuid = runCatching { UUID.fromString(uuid) }.getOrNull()
    if (basename != "credential-import-$uuid.fifo" || parsedUuid?.toString() != uuid) {
        throw CredentialImportFailure()
    }
    return File(cacheDirectory, basename).also { pipe ->
        if (pipe.parentFile?.canonicalFile != cacheDirectory.canonicalFile) throw CredentialImportFailure()
    }
}

internal fun verifyPrivatePipe(pipe: File, expectedUid: Int) {
    val stat = try {
        Os.lstat(pipe.absolutePath)
    } catch (_: ErrnoException) {
        throw CredentialImportFailure()
    }
    val permissions = stat.st_mode and 0x1ff
    if (!OsConstants.S_ISFIFO(stat.st_mode) || stat.st_uid != expectedUid || permissions != 0x180) {
        throw CredentialImportFailure()
    }
}

internal fun readPipe(descriptor: FileDescriptor, timeoutMillis: Long, maxBytes: Int): ByteArray {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    val result = ByteArray(maxBytes + 1)
    var size = 0
    var receivedBytes = false
    val poll = StructPollfd().apply {
        fd = descriptor
        events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
    }
    while (System.nanoTime() < deadline) {
        val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L).coerceAtMost(250L).toInt()
        try {
            Os.poll(arrayOf(poll), remaining)
        } catch (_: ErrnoException) {
            throw CredentialImportFailure()
        }
        val events = poll.revents.toInt()
        if ((events and OsConstants.POLLNVAL) != 0 || (events and OsConstants.POLLERR) != 0) throw CredentialImportFailure()
        if ((events and OsConstants.POLLIN) != 0) {
            while (true) {
                val read = try {
                    Os.read(descriptor, result, size, result.size - size)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EAGAIN) break
                    throw CredentialImportFailure()
                }
                if (read == 0) {
                    if (!receivedBytes) break
                    return result.copyOf(size)
                }
                receivedBytes = true
                size += read
                if (size > maxBytes) throw CredentialImportFailure()
            }
        }
        if ((events and OsConstants.POLLHUP) != 0 && receivedBytes) {
            val read = try {
                Os.read(descriptor, result, size, result.size - size)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN) 0 else throw CredentialImportFailure()
            }
            if (read == 0) return result.copyOf(size)
            size += read
            if (size > maxBytes) throw CredentialImportFailure()
        }
        if ((events and OsConstants.POLLHUP) != 0 && !receivedBytes) Thread.sleep(10)
    }
    throw CredentialImportFailure()
}

internal fun parseCredentialInput(payload: ByteArray): CredentialInput {
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    } catch (_: Throwable) {
        throw CredentialImportFailure()
    }
    val values = try {
        val expected = setOf("amapWebKey", "amapSdkKey", "caiyunAppKey", "caiyunSecret")
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val fields = linkedMapOf<String, String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name !in expected || name in fields || reader.peek() != JsonToken.STRING) throw CredentialImportFailure()
                val value = reader.nextString()
                if (value.any { character -> character.code <= 0x1f || character.code == 0x7f }) throw CredentialImportFailure()
                fields[name] = value
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT || fields.keys != expected) throw CredentialImportFailure()
            fields
        }
    } catch (_: Throwable) {
        throw CredentialImportFailure()
    }
    return CredentialInput(
        amapWebKey = requireNotNull(values["amapWebKey"]),
        amapSdkKey = requireNotNull(values["amapSdkKey"]),
        caiyunAppKey = requireNotNull(values["caiyunAppKey"]),
        caiyunSecret = requireNotNull(values["caiyunSecret"]),
    )
}
