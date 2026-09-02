package com.ljwzz.weathertrafficalarm.core.network.caiyun

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Credentials supplied at call time from encrypted storage. */
class CaiyunCredentials(
    val appKey: String,
    val appSecret: String,
) {
    init {
        require(appKey.isNotBlank()) { "Caiyun app key must not be blank" }
        require(appSecret.isNotBlank()) { "Caiyun app secret must not be blank" }
    }

    override fun toString(): String = "CaiyunCredentials(redacted)"
}

/** Keeps the network module independent of the credential-storage implementation. */
fun interface CaiyunCredentialsProvider {
    suspend fun currentCredentials(): CaiyunCredentials?
}

fun interface CaiyunNonceGenerator {
    fun next(): String
}

/**
 * Creates v2.6 request signatures.  Query values are encoded and ordered before signing so
 * request construction cannot change the signed representation.
 */
class CaiyunSigner {
    fun signedHeaders(
        credentials: CaiyunCredentials,
        method: String,
        path: String,
        query: Map<String, String>,
        nonce: String,
        timestampSeconds: Long,
    ): CaiyunSignedHeaders {
        require(method == "GET") { "Caiyun v2.6 currently supports GET only" }
        require(path.startsWith('/')) { "Caiyun request path must be absolute" }
        require(nonce.length in MIN_NONCE_LENGTH..MAX_NONCE_LENGTH) { "Caiyun nonce must be 16 to 40 characters" }
        val queryString = canonicalQuery(query)
        val stringToSign = listOf(method, path, queryString, credentials.appKey, nonce, timestampSeconds.toString()).joinToString(":")
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(credentials.appSecret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA_256))
        val signature = Base64.getUrlEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8)))
        return CaiyunSignedHeaders(nonce, timestampSeconds, signature)
    }

    fun canonicalQuery(query: Map<String, String>): String = query.toSortedMap().entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
        const val MIN_NONCE_LENGTH = 16
        const val MAX_NONCE_LENGTH = 40
    }
}

class CaiyunSignedHeaders(
    val nonce: String,
    val timestampSeconds: Long,
    val signature: String,
) {
    override fun toString(): String = "CaiyunSignedHeaders(redacted, timestampSeconds=$timestampSeconds)"
}
