package com.ljwzz.weathertrafficalarm.core.network.caiyun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaiyunSignerTest {
    @Test
    fun officialVectorSortsAndSignsQueryWithUrlSafeBase64() {
        val headers = CaiyunSigner().signedHeaders(
            credentials = CaiyunCredentials("your_app_key", "your_app_secret"),
            method = "GET",
            path = "/v2.6/your_app_key/116.3176,39.9760/weather",
            query = mapOf("hourlysteps" to "24", "dailysteps" to "1", "alert" to "true"),
            nonce = "0195c68a-42e7-7243-bff2-ac97a78b837d",
            timestampSeconds = 1_742_791_910L,
        )

        assertEquals("KfHsk3z2XfX6Yxox4Uf_VgyM0wHk6bWEyRqZ9QOJUYw=", headers.signature)
    }

    @Test
    fun canonicalQuerySortsAndEncodesValues() {
        val query = CaiyunSigner().canonicalQuery(mapOf("z key" to "a/b", "alert" to "true"))

        assertEquals("alert=true&z+key=a%2Fb", query)
    }

    @Test
    fun rejectsNonceOutsideProviderRange() {
        val error = runCatching {
            CaiyunSigner().signedHeaders(
                CaiyunCredentials("key", "secret"),
                "GET",
                "/v2.6/key/1,2/weather",
                emptyMap(),
                "short",
                1L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun productionNonceGeneratorReturnsUniqueValuesInProviderRange() {
        val first = UuidCaiyunNonceGenerator.next()
        val second = UuidCaiyunNonceGenerator.next()

        assertTrue(first.length in 16..40)
        assertTrue(second.length in 16..40)
        assertFalse(first == second)
    }

    @Test
    fun credentialAndSignatureStringRepresentationsNeverExposeSecrets() {
        val credentials = CaiyunCredentials("app-key-that-must-not-leak", "app-secret-that-must-not-leak")
        val headers = CaiyunSignedHeaders(
            nonce = "0123456789abcdef",
            timestampSeconds = 1L,
            signature = "signature-that-must-not-leak",
        )

        assertFalse(credentials.toString().contains(credentials.appKey))
        assertFalse(credentials.toString().contains(credentials.appSecret))
        assertFalse(headers.toString().contains(headers.nonce))
        assertFalse(headers.toString().contains(headers.signature))
    }
}
